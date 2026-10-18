# FloatClip near-field P0 (Windows)

This is a foreground, generated-data Bluetooth test. It is not the production
clipboard client. It never reads/writes the clipboard, edits routing/DNS/proxy
settings, opens an IP listener, or transfers user files. No administrator rights
are required. Both sides must use this P0 UUID/protocol, not a serial COM profile.

## Test with an Android phone

1. Pair the phone and PC in their system Bluetooth settings. Keep the original
   internet connection. Leave the phone's **FloatClip 连接测试** app open.
2. In that app, select this PC, then tap **等待所选设备连接**.
3. In Windows PowerShell 5.1, from the extracted `windows` directory:

   ```powershell
   .\Start-NearbyProbe.ps1 -List
   .\Start-NearbyProbe.ps1 -SelfTest
   .\Start-NearbyProbe.ps1 -PeerAddress AA:BB:CC:DD:EE:FF
   ```

   Use the phone's paired address reported by `-List`; do not use the Android
   BluetoothAdapter local address, which may be a privacy placeholder. Do not
   commit or share real Bluetooth addresses in bug reports.

4. The phone sends ten generated probes, and the PC sends ten. Each direction
   acknowledges the original SHA-256. A final `PASS` means all twenty synthetic
   messages were verified. The PC closes the session at completion. Both sides
   report a failure instead of silently retrying corrupted or partial data.
5. Repeat with headphones connected, then after a disconnect/reconnect. Test
   Android-to-Android by having one phone listen and the other connect.

The PowerShell route compiles `Probe.cs` with the system C# compiler; it requires
no separate .NET SDK or downloaded third-party Bluetooth package. A compiled
console tool can instead use `--connect ADDRESS` / `--self-test`. Its actual
Win10/Win11 execution is a separate device acceptance requirement.

## Double-click and error diagnosis

Double-clicking the EXE now opens an interactive console. Enter `test` for a
local protocol self-test, a phone Bluetooth address to connect, or `q` to exit.
The interactive console stays open after connection failures. The previous
build printed usage and immediately returned exit code 0 when launched without
arguments, which made a double-click look like a crash.

`Start-NearbyProbe.cmd` also holds the console open if the EXE fails before its
managed entry point. Command-line `--connect` and `--self-test` keep their exit
codes. Exceptions include stack traces, the failing socket option name, and an
error log under `%LOCALAPPDATA%\FloatClip\NearbyProbe\Logs`. Logs do not read
clipboard data. These startup fixes do not establish Windows hardware acceptance.

## Protocol and security boundary

- UUID: `db7aa0e6-0ccd-47d0-b749-80f17b9bfc21` (P0-only; not a permanent product ABI).
- Frame: 4-byte `FCP0`, version byte `1`, kind byte (`1` probe / `2` ACK), 16-byte
  UUID in RFC 4122 network order, 4-byte big-endian payload length, 32-byte
  SHA-256, then payload. Maximum payload 16 KiB; ACK payload exactly 32 bytes.
- Android and C# agree on raw UTF-8, including emoji, tabs, spaces and CRLF.
- Only the exact generated payload for an event ID is accepted as a probe.
- Android uses `listenUsingRfcommWithServiceRecord` and secure client sockets;
  it accepts only the explicitly selected, already bonded peer.
- Windows requires `SO_BTH_AUTHENTICATE` and `SO_BTH_ENCRYPT`. It will fail when
  these cannot be applied; it never downgrades to insecure RFCOMM.
- SHA-256 is an integrity check, **not authentication**. This P0 relies on the
  system's Bluetooth pairing and link security. App-level device identities,
  transport-independent authenticated encryption and revocation must be designed
  and reviewed before real clipboard content is enabled.
- The Android test closes on backgrounding. It does not prove background capture,
  idle power, BLE performance, Wi-Fi Direct concurrency, or iOS compatibility.
- This P0 only implements Windows as the connector; PC-to-PC/Windows listener
  and multi-peer orchestration are later product work.

## Developer verification

```bash
bash scripts/test-nearby-wire.sh
.toolchains/nearby-dotnet/dotnet run --project windows/FloatClip.NearbyProbe -- \
  --self-test analysis/nearby-p0/wire-tests/vectors
```

The Java tests reject every partial-frame prefix and malformed lengths. The C#
self-test checks native structure packing and compares Java/C# probe and ACK
vectors byte-for-byte. These tests do not exercise any Bluetooth hardware.
