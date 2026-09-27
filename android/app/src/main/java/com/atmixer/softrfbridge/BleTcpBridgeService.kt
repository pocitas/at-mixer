package com.atmixer.softrfbridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class BleTcpBridgeService : Service() {

    companion object {
        private const val TAG = "BleTcpBridge"
        private const val TAG_TCP_DUMP = "BleTcpBridgeDump"
        private const val DEVICE_NAME_PREFIX = "SoftRF"
        private const val TCP_PORT = 12345
        // Set to true to log every TCP payload (very verbose, debug only).
        private const val DEBUG_TCP_OUTPUT_DUMP_LOGCAT = false

        private val NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NUS_TX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val NOTIFICATION_CHANNEL_ID = "ble_bridge"
        private const val NOTIFICATION_ID = 1

        private const val SCAN_RETRY_DELAY_MS = 5_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val STATUS_WINDOW_MS = 30_000L
        private const val NOTIFICATION_REFRESH_MS = 3_000L

        // Configure mux inputs here. Add more entries for additional TCP sources.
        private val MUX_TCP_SOURCES = listOf(
            LocalNmeaMux.TcpSourceConfig(name = "xcguide", host = "127.0.0.1", port = 10110),
            LocalNmeaMux.TcpSourceConfig(name = "softrf", host = "127.0.0.1", port = 12345),
        )

        // Enable once you verify your consumer needs synthetic PFLAU heartbeat.
        private const val MUX_HEARTBEAT_ENABLED = true
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()

    // Forces the Bluetooth stack to deliver all GATT callbacks on one thread,
    // preventing concurrent onCharacteristicChanged calls that otherwise
    // interleave and corrupt the outgoing TCP stream.
    private val bleCallbackThread = HandlerThread("BleGattCallback").apply { start() }
    private val bleCallbackHandler = Handler(bleCallbackThread.looper)

    private var gatt: BluetoothGatt? = null
    private var localNmeaMux: LocalNmeaMux? = null
    private var scanning = false
    @Volatile
    private var softRfConnected = false

    @Volatile
    private var shuttingDown = false

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()
    private val broadcastLock = Any()
    private val messageWindowLock = Any()
    private val messageTimestampsMs = ArrayDeque<Long>()

    private val notificationUpdater = object : Runnable {
        override fun run() {
            if (shuttingDown) return
            updateForegroundNotification()
            handler.postDelayed(this, NOTIFICATION_REFRESH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter

        startForeground(NOTIFICATION_ID, buildNotification())
        handler.postDelayed(notificationUpdater, NOTIFICATION_REFRESH_MS)
        startTcpServer()
        localNmeaMux = LocalNmeaMux(
            logTag = "NmeaMux",
            sources = MUX_TCP_SOURCES,
            heartbeatEnabled = MUX_HEARTBEAT_ENABLED,
        ).also { it.start() }
        maybeStartScan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        shuttingDown = true
        super.onDestroy()

        handler.removeCallbacksAndMessages(null)
        stopScan()
        disconnectGatt()

        clients.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { serverSocket?.close() }
        localNmeaMux?.stop()
        localNmeaMux = null

        ioExecutor.shutdownNow()
        bleCallbackThread.quitSafely()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "SoftRF Bridge",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)

        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = openIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SoftRF Bridge running")
            .setContentText(buildStatusText())
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun buildStatusText(): String {
        val marker = if (softRfConnected) "✓" else "✗"
        return "SoftRF $marker | RX 30s: ${countMessagesLast30Seconds()}"
    }

    private fun updateForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun recordBleMessage() {
        val now = System.currentTimeMillis()
        synchronized(messageWindowLock) {
            messageTimestampsMs.addLast(now)
            trimOldMessages(now)
        }
    }

    private fun countMessagesLast30Seconds(): Int {
        val now = System.currentTimeMillis()
        synchronized(messageWindowLock) {
            trimOldMessages(now)
            return messageTimestampsMs.size
        }
    }

    private fun trimOldMessages(nowMs: Long) {
        while (messageTimestampsMs.isNotEmpty() && nowMs - messageTimestampsMs.first() > STATUS_WINDOW_MS) {
            messageTimestampsMs.removeFirst()
        }
    }

    private fun startTcpServer() {
        ioExecutor.execute {
            try {
                val socket = ServerSocket(TCP_PORT, 50, InetAddress.getByName("127.0.0.1"))
                serverSocket = socket
                Log.i(TAG, "TCP server listening on 127.0.0.1:$TCP_PORT")

                while (!socket.isClosed) {
                    val client = socket.accept()
                    clients.add(client)
                    Log.i(TAG, "TCP client connected: ${client.remoteSocketAddress}")

                    ioExecutor.execute {
                        val buf = ByteArray(256)
                        while (true) {
                            try {
                                if (client.getInputStream().read(buf) < 0) break
                            } catch (_: IOException) {
                                break
                            }
                        }

                        clients.remove(client)
                        runCatching { client.close() }
                        Log.i(TAG, "TCP client disconnected")
                    }
                }
            } catch (e: IOException) {
                if (!shuttingDown) {
                    Log.e(TAG, "TCP server error", e)
                }
            }
        }
    }

    private fun broadcast(data: ByteArray) {
        logTcpOutputDump(data)
        synchronized(broadcastLock) {
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

    private fun logTcpOutputDump(data: ByteArray) {
        if (!DEBUG_TCP_OUTPUT_DUMP_LOGCAT) return

        val escaped = buildString(data.size * 2) {
            for (byte in data) {
                val value = byte.toInt() and 0xFF
                when (value) {
                    0x0D -> append("\\r")
                    0x0A -> append("\\n")
                    0x09 -> append("\\t")
                    in 0x20..0x7E -> append(value.toChar())
                    else -> append("\\x%02X".format(value))
                }
            }
        }

        Log.d(TAG_TCP_DUMP, "TCP OUT ${data.size}B: $escaped")
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBleScanPermission() = hasPermission(Manifest.permission.BLUETOOTH_SCAN)

    private fun hasBleConnectPermission() = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    private fun maybeStartScan() {
        if (!hasBleScanPermission()) {
            Log.w(TAG, "BLUETOOTH_SCAN not granted, stopping service")
            stopSelf()
            return
        }
        startScan()
    }

    private fun startScan() {
        if (scanning || shuttingDown || !hasBleScanPermission()) return

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            scheduleScanRetry()
            return
        }

        scanning = true
        try {
            scanner.startScan(emptyList(), ScanSettings.Builder().build(), scanCallback)
            Log.i(TAG, "starting BLE scan for devices named \"$DEVICE_NAME_PREFIX*\"")
        } catch (e: SecurityException) {
            scanning = false
            Log.w(TAG, "startScan denied", e)
            scheduleScanRetry()
        }
    }

    private fun stopScan() {
        if (!scanning) return

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner != null && hasBleScanPermission()) {
            try {
                scanner.stopScan(scanCallback)
            } catch (_: SecurityException) {
                Log.w(TAG, "stopScan denied")
            }
        }
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: return
            if (!name.startsWith(DEVICE_NAME_PREFIX) || !hasBleConnectPermission()) return

            // stopScan() is asynchronous, so duplicate results for the same
            // device can still arrive while a connection is already in flight.
            if (gatt != null) return

            Log.i(TAG, "found $name")
            stopScan()
            connectToDevice(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scanning = false
            scheduleScanRetry()
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasBleConnectPermission()) return

        try {
            gatt = device.connectGatt(
                this,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                bleCallbackHandler,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "connectGatt denied", e)
            scheduleReconnect()
        }
    }

    private fun disconnectGatt() {
        val current = gatt
        gatt = null
        softRfConnected = false
        if (current == null) return

        if (hasBleConnectPermission()) {
            try {
                current.disconnect()
            } catch (_: SecurityException) {
                Log.w(TAG, "gatt.disconnect denied")
            }
            try {
                current.close()
            } catch (_: SecurityException) {
                Log.w(TAG, "gatt.close denied")
            }
            return
        }

        try {
            current.close()
        } catch (_: SecurityException) {
            Log.w(TAG, "gatt.close denied")
        }
    }

    private fun scheduleReconnect() {
        if (shuttingDown) return
        disconnectGatt()
        handler.postDelayed({ maybeStartScan() }, RECONNECT_DELAY_MS)
    }

    private fun scheduleScanRetry() {
        if (shuttingDown) return
        handler.postDelayed({ maybeStartScan() }, SCAN_RETRY_DELAY_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(bluetoothGatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT state change error: $status")
                softRfConnected = false
                updateForegroundNotification()
                scheduleReconnect()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    softRfConnected = true
                    updateForegroundNotification()
                    if (!hasBleConnectPermission()) return
                    try {
                        bluetoothGatt.discoverServices()
                        Log.i(TAG, "GATT connected")
                    } catch (e: SecurityException) {
                        Log.w(TAG, "discoverServices denied", e)
                        scheduleReconnect()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected, will rescan")
                    softRfConnected = false
                    updateForegroundNotification()
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(bluetoothGatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "service discovery failed: $status")
                scheduleReconnect()
                return
            }
            if (!hasBleConnectPermission()) return

            val characteristic = bluetoothGatt
                .getService(NUS_SERVICE_UUID)
                ?.getCharacteristic(NUS_TX_CHAR_UUID)

            if (characteristic == null) {
                Log.e(TAG, "NUS TX characteristic not found")
                scheduleReconnect()
                return
            }

            try {
                bluetoothGatt.setCharacteristicNotification(characteristic, true)
            } catch (e: SecurityException) {
                Log.w(TAG, "setCharacteristicNotification denied", e)
                scheduleReconnect()
                return
            }

            val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bluetoothGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    bluetoothGatt.writeDescriptor(descriptor)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "writeDescriptor denied", e)
                scheduleReconnect()
            }
        }

        // On API 33+ the platform calls both overloads below for the same notification,
        // so each one must only act when the other one won't be invoked.
        override fun onCharacteristicChanged(
            bluetoothGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

            recordBleMessage()
            broadcast(value)
        }

        @Deprecated("Use onCharacteristicChanged(gatt, characteristic, value)")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            bluetoothGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return

            characteristic.value?.let {
                recordBleMessage()
                broadcast(it)
            }
        }
    }
}
