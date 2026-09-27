# SoftRF BLE -> TCP Bridge (Android)

Minimal Android app that:

- starts a foreground service,
- scans for BLE devices whose name starts with `SoftRF`,
- subscribes to Nordic UART Service TX notifications,
- forwards received bytes to a local TCP server on `127.0.0.1:12345`,
- starts an internal NMEA mux that reads `127.0.0.1:12345` and publishes framed output on `127.0.0.1:10112`.

## App flow

1. Launcher `PermissionActivity` requests runtime permissions.
2. If granted, it starts `BleTcpBridgeService` and exits.
3. Service opens TCP listener (`12345`), scans BLE, connects, subscribes, and streams data.
4. Service starts NMEA mux (`LocalNmeaMux`) that reconnects to `12345`, rebuilds NMEA sentences, adds heartbeat `PFLAU`, and broadcasts on `10112`.

## Mux configuration

Configure source count and heartbeat in `app/src/main/java/com/atmixer/softrfbridge/BleTcpBridgeService.kt`:

- `MUX_TCP_SOURCES`: list of input TCP sources for `LocalNmeaMux.runTcpSource(...)`.
- `MUX_HEARTBEAT_ENABLED`: `true` to emit synthetic `PFLAU`, `false` to disable during testing.

Example with two sources:

```kotlin
private val MUX_TCP_SOURCES = listOf(
	LocalNmeaMux.TcpSourceConfig(name = "softrf", host = "127.0.0.1", port = 12345),
	LocalNmeaMux.TcpSourceConfig(name = "xctrack", host = "127.0.0.1", port = 10110),
)
```

## Required Android permissions

- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `POST_NOTIFICATIONS` (Android 13+)
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- `INTERNET`

## Build

From the `android` folder:

```powershell
.\gradlew.bat assembleDebug
```

## Quick local check

Install and launch app, grant permissions, then connect to local stream from the same device (for example from Termux):

```bash
nc 127.0.0.1 12345
```

You should see raw NMEA bytes received from SoftRF.

For the muxed/framed stream (the equivalent of the provided Go bridge), connect to:

```bash
nc 127.0.0.1 10112
```

## Optional: dump TCP output to Logcat

For detailed debug, you can log each TCP payload from `BleTcpBridgeService` to Android Logcat.

1. Open `app/src/main/java/com/atmixer/softrfbridge/BleTcpBridgeService.kt`.
2. Find `DEBUG_TCP_OUTPUT_DUMP_LOGCAT` in the `companion object`.
3. Set it to `true` to enable detailed output, or `false` to disable it.

Example:

```kotlin
private const val DEBUG_TCP_OUTPUT_DUMP_LOGCAT = true
```

Then read logs with:

```powershell
adb logcat -s BleTcpBridgeDump
```

Logged output is escaped text (`\r`, `\n`, `\t`, and `\xHH` for non-printable bytes), so you can inspect exactly what is sent to the TCP broadcast server.

