package com.atmixer.softrfbridge

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private data class MuxStats(
    var aircraft: Long = 0,
    var barometer: Long = 0,
    var gnss: Long = 0,
    var status: Long = 0,
    var other: Long = 0,
    var checksumError: Long = 0,
    var framingError: Long = 0,
    var bytesIn: Long = 0,
    var sentences: Long = 0,
)

private class NmeaFramer(
    private val stats: MuxStats,
    private val onSentence: (ByteArray) -> Unit,
) {
    private var buffer = ByteArray(0)

    @Synchronized
    fun feed(data: ByteArray) {
        buffer += data
        synchronized(stats) { stats.bytesIn += data.size.toLong() }

        val maxBufferSize = 4096
        if (buffer.size < 5) {
            return
        }

        var nextStart = buffer.indexOfFrom('$'.code.toByte(), 1)
        while (nextStart >= 0) {
            val potentialSentence = buffer.copyOfRange(0, nextStart)
            buffer = buffer.copyOfRange(nextStart, buffer.size)
            processSentence(potentialSentence)
            nextStart = buffer.indexOfFrom('$'.code.toByte(), 1)
        }

        if (buffer.size > maxBufferSize) {
            buffer = ByteArray(0)
            synchronized(stats) { stats.framingError++ }
            return
        }

        if (extractNmeaMessage(buffer) != null) {
            processSentence(buffer)
            buffer = ByteArray(0)
        }
    }

    private fun processSentence(sentence: ByteArray) {
        val message = extractNmeaMessage(sentence)
        if (message == null) {
            if (sentence.isNotEmpty() && sentence[0] == '$'.code.toByte()) {
                synchronized(stats) { stats.checksumError++ }
            } else {
                synchronized(stats) { stats.framingError++ }
            }
            return
        }

        val kind = classifyMessage(message)

        synchronized(stats) {
            stats.sentences++
            when (kind) {
                "aircraft" -> stats.aircraft++
                "barometer" -> stats.barometer++
                "gnss" -> stats.gnss++
                "status" -> stats.status++
                else -> stats.other++
            }
        }

        onSentence(buildNmeaSentence(message))
    }

    private fun classifyMessage(message: ByteArray): String {
        val payload = message.decodeToString()
        val cut = payload.indexOf(',')
        val header = if (cut >= 0) payload.substring(0, cut) else payload

        return when (header) {
            "PFLAA" -> "aircraft"
            "PFLAU" -> "status"
            "LK8EX1" -> "barometer"
            "GGA", "GSA", "GSV", "RMC", "VTG", "GLL" -> "gnss"
            else -> "other"
        }
    }

    private fun extractNmeaMessage(input: ByteArray): ByteArray? {
        if (input.size < 5) return null
        if (input[0] != '$'.code.toByte()) return null

        val star = input.indexOf('*'.code.toByte())
        if (star <= 1) return null
        if (input.indexOfFrom('*'.code.toByte(), star + 1) >= 0) return null

        val message = input.copyOfRange(1, star)
        val checksumText = input.copyOfRange(star + 1, input.size).decodeToString().trim()
        val receivedChecksum = checksumText.toIntOrNull(16) ?: return null

        var computedChecksum = 0
        for (byte in message) {
            computedChecksum = computedChecksum xor (byte.toInt() and 0xFF)
        }

        if (receivedChecksum != computedChecksum) return null
        return message
    }

    private fun buildNmeaSentence(message: ByteArray): ByteArray {
        var checksum = 0
        for (byte in message) {
            checksum = checksum xor (byte.toInt() and 0xFF)
        }
        val checksumText = "%02X".format(checksum).toByteArray()
        return byteArrayOf('$'.code.toByte()) + message + byteArrayOf('*'.code.toByte()) + checksumText +
            byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    }
}

class LocalNmeaMux(
    private val logTag: String = "NmeaMux",
    private val outputHost: String = "127.0.0.1",
    private val outputPort: Int = 10112,
    private val sources: List<TcpSourceConfig> = listOf(
        TcpSourceConfig(name = "softrf", host = "127.0.0.1", port = 12345),
    ),
    private val heartbeatEnabled: Boolean = true,
) {
    data class TcpSourceConfig(
        val name: String,
        val host: String,
        val port: Int,
    )

    private val running = AtomicBoolean(false)
    private val io = Executors.newCachedThreadPool()
    private val scheduled: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    private val stats = MuxStats()

    private val framersLock = Any()
    private val framers = mutableMapOf<String, NmeaFramer>()

    private val clientsLock = Any()
    private val clients = CopyOnWriteArrayList<Socket>()

    @Volatile
    private var outputServer: ServerSocket? = null

    private val sourceSockets = ConcurrentHashMap<String, Socket>()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (sources.isEmpty()) {
            Log.w(logTag, "No TCP sources configured, only output server will run")
        }

        runServer()
        sources.forEach { source ->
            runTcpSource(source)
        }
        if (heartbeatEnabled) {
            heartbeatLoop()
        }
        statsLoop()

        Log.i(logTag, "NMEA mux started, output=$outputHost:$outputPort, heartbeat=$heartbeatEnabled, sources=${sources.size}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return

        runCatching { outputServer?.close() }
        sourceSockets.values.forEach { socket -> runCatching { socket.close() } }
        sourceSockets.clear()

        clients.forEach { runCatching { it.close() } }
        clients.clear()

        io.shutdownNow()
        scheduled.shutdownNow()

        Log.i(logTag, "NMEA mux stopped")
    }

    fun feed(source: String, data: ByteArray) {
        val framer = synchronized(framersLock) {
            framers.getOrPut(source) { NmeaFramer(stats, ::broadcast) }
        }
        framer.feed(data)
    }

    private fun runServer() {
        io.execute {
            try {
                val server = ServerSocket(outputPort, 50, InetAddress.getByName(outputHost))
                outputServer = server
                Log.i(logTag, "output listening on $outputHost:$outputPort")

                while (running.get() && !server.isClosed) {
                    val client = server.accept()
                    clients.add(client)
                    Log.i(logTag, "output client connected: ${client.remoteSocketAddress}")

                    io.execute {
                        val buf = ByteArray(256)
                        try {
                            while (running.get() && client.getInputStream().read(buf) >= 0) {
                                // Read side only detects disconnect.
                            }
                        } catch (_: IOException) {
                        } finally {
                            clients.remove(client)
                            runCatching { client.close() }
                            Log.i(logTag, "output client disconnected")
                        }
                    }
                }
            } catch (e: IOException) {
                if (running.get()) {
                    Log.e(logTag, "cannot listen on $outputHost:$outputPort", e)
                }
            }
        }
    }

    private fun runTcpSource(source: TcpSourceConfig) {
        io.execute {
            var backoffMs = 1_000L
            val name = source.name
            val host = source.host
            val port = source.port

            while (running.get()) {
                try {
                    Log.i(logTag, "$name: connecting to $host:$port")
                    val conn = Socket(host, port)
                    sourceSockets[name] = conn
                    backoffMs = 1_000L
                    Log.i(logTag, "$name: connected")

                    val buf = ByteArray(4096)
                    while (running.get()) {
                        val n = conn.getInputStream().read(buf)
                        if (n < 0) {
                            Log.i(logTag, "$name: disconnected")
                            break
                        }
                        if (n > 0) {
                            try {
                                feed(name, buf.copyOfRange(0, n))
                            } catch (e: Exception) {
                                // Keep this source isolated; other sources continue unaffected.
                                Log.w(logTag, "$name: parser error on chunk (${n}B): ${e.message}")
                            }
                        }
                    }

                    runCatching { conn.close() }
                    sourceSockets.remove(name, conn)
                } catch (e: IOException) {
                    if (!running.get()) break
                    Log.w(logTag, "$name: connection/read error: ${e.message}")
                    try {
                        Thread.sleep(backoffMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (backoffMs < 30_000L) backoffMs *= 2
                }
            }
        }
    }

    private fun heartbeatLoop() {
        scheduled.scheduleWithFixedDelay(
            { if (running.get()) broadcast(nmeaSentence("PFLAU,0,1,2,1,0,,0,,")) },
            1,
            1,
            TimeUnit.SECONDS,
        )
    }

    private fun statsLoop() {
        scheduled.scheduleWithFixedDelay(
            {
                if (!running.get()) return@scheduleWithFixedDelay
                val snapshot = synchronized(stats) {
                    "aircraft=${stats.aircraft} baro=${stats.barometer} gnss=${stats.gnss} " +
                        "status=${stats.status} other=${stats.other} checksum_error=${stats.checksumError} " +
                        "framing_error=${stats.framingError} sentences=${stats.sentences} bytes=${stats.bytesIn}"
                }
                Log.i(logTag, "stats: $snapshot")
            },
            5,
            5,
            TimeUnit.SECONDS,
        )
    }

    private fun broadcast(data: ByteArray) {
        synchronized(clientsLock) {
            for (client in clients) {
                try {
                    client.getOutputStream().write(data)
                } catch (_: IOException) {
                    clients.remove(client)
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun nmeaSentence(body: String): ByteArray {
        var checksum = 0
        for (c in body.toByteArray()) {
            checksum = checksum xor (c.toInt() and 0xFF)
        }
        return "$${body}*%02X\r\n".format(checksum).toByteArray()
    }
}

private fun ByteArray.indexOfFrom(element: Byte, startIndex: Int): Int {
    if (startIndex >= size) return -1
    val from = if (startIndex < 0) 0 else startIndex
    for (i in from until size) {
        if (this[i] == element) return i
    }
    return -1
}

