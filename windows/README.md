# FloatClip near-field P0 (Windows)

This is a generated-data LAN / Bluetooth test. It is not the production
clipboard client. It never reads/writes the clipboard, edits routing/DNS/proxy
settings, opens an IP listener, or transfers user files. No administrator rights
are required. Bluetooth uses the P0 service UUID. LAN uses pinned TLS and a one-use invitation.

## LAN over an existing Wi-Fi / Ethernet network

1. Keep the phone and PC on a mutually reachable network (the phone's existing
   hotspot also works). In **FloatClip 连接测试**, select the phone's private
   Wi-Fi/hotspot/Ethernet IPv4 address and tap the LAN listen button.
2. Keep the app in the foreground. The invitation expires after 90 seconds.
   Copy the displayed address, port, certificate SHA-256 and one-use invitation.
3. Double-click the EXE, enter `lan`, and fill those fields. Or run:

   ```powershell
   .\FloatClip.NearbyProbe.exe --lan-connect PRIVATE_IP 46237 CERT_SHA256 INVITATION
   .\Start-NearbyProbe.ps1 -Lan -HostAddress PRIVATE_IP -Port 46237 -CertificatePin CERT_SHA256 -Invitation INVITATION
   ```

4. A pinned TLS 1.2 session authenticates the phone certificate. A random 32-byte
   invitation, sent inside TLS, authenticates the connecting test client. Take
   the fingerprint and invitation directly from the selected phone. The key
   stays in Android Keystore. A wrong pin/invitation fails; no plaintext fallback.
5. Both ends verify ten generated probes in each direction, then close. The
   invitation is single-use; start a new listener for another test. Backgrounding
   the phone, stopping the test, expiry or a 30-second session limit closes it.

This LAN P0 binds only an existing private IPv4 address on a Wi-Fi, hotspot or
Ethernet interface. It does not create Wi-Fi Direct groups or edit networking,
proxy settings, routing or Windows network filters. It currently implements
Android listener -> Windows connector, with no PC-to-PC or real-content sync.

The LAN greeting inside TLS is four bytes `FCL1`, followed by the 32 raw invite
bytes; the server accepts with one byte `1`. Then each round is Android PROBE,
Windows ACK, Windows PROBE, Android ACK using the existing FCP0 frame.
Persistent product identities, revocation, discovery and automatic reconnect
remain later work.

The debug APK includes `LanProbeInstrumentation` to exercise this same server
without navigating the UI. It emits readiness to the invoking ADB session and
never writes invitations to logcat. The bounded listener still expires. The
harness does not establish background-service support.


For an authorized USB ADB debug session, the packaged `Test-LanProbe.ps1`
starts the same-app instrumentation and runs valid, wrong-pin,
wrong-invitation, early-disconnect and valid-retry cases. For example:

```powershell
.\Test-LanProbe.ps1 -AdbPath C:\Android\platform-tools\adb.exe -Serial USB_SERIAL -PhoneAddress PRIVATE_IP
```

It captures invitation credentials in memory, prints only case results, and
writes generated-data evidence to `lan-acceptance.json`. It force-stops only
`com.floatclip.probe` between cases. Installing the debug APK and authorizing
ADB remain prerequisites. This harness is separate from manual UI acceptance.

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
