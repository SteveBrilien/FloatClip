# FloatClip Web 0.2.0

Small-node hosted PWA for iPad Safari and current desktop/mobile browsers. The
production origin is `https://clip.wmy-cloud.cn`. The owner provisions the account
outside Git; neither the password nor its verifier belongs in the source tree.

## Build and test

Use Node 24 and Python 3.10+. Install frontend dependencies from `package.json`,
then run `npm run build` and `python3 ops/finalize-build.py`. Run TypeScript checking,
`node --experimental-transform-types --test tests/*.test.ts`, and
`python3 -m unittest discover -s tests -p 'test_*.py' -v` with the Python dependencies.
The build directory contains only public application assets. Source maps are off.

## Node service

`server/requirements.txt` pins the HTTP/WebSocket runtime. Start `server/app.py`
with `--config`, `--state`, `--static` and `--port`. It binds only to 127.0.0.1.
The service config is owner-readable mode 0600 and contains `origin`, `username`
and a PBKDF2-SHA256 verifier (`algorithm`, `iterations`, `salt`, `hash`). Use
`server.app.password_hash` to generate it. State is owner-private and outside the
published build. Do not expose source, state, wheelhouse, or Git directories.

The reverse proxy must preserve Host and overwrite `X-Real-IP` with the socket's
remote address. It must terminate HTTPS and support WebSocket upgrades. The
production route uses the existing Azure Caddy ingress and a dedicated SSH
reverse tunnel to the OrangePi. The OrangePi runs authentication, signaling and
opaque packet forwarding; Azure is only ingress. No instrument LAN is used.

## Authentication

Secure, HttpOnly, SameSite=Strict, host-only cookies. Remembered sessions last
30 days; other sessions last at most 12 hours. Server state stores only a hash
of the random session token. Authentication survives node restarts. Origin and
per-session CSRF checks protect mutations. Logout revokes the current session
and closes its relay sockets. Login attempts and pairing guesses are limited.
PWA storage contains public code/assets and theme/device-name preferences, never
credentials, received content, pairing tokens, or shared files.

## Connection modes

- Device direct: WebRTC DTLS DataChannel; STUN for address discovery, no TURN.
- Via node: ephemeral P-256 ECDH, directional HKDF-SHA256/AES-256-GCM keys.
  Nonces use a monotonically increasing 64-bit counter with separate directional
  keys. AAD binds version, payload type and sequence. Out-of-order, replayed or
  altered frames fail closed. Both users compare a 6-digit key-bound code and
  confirm before sharing. The node forwards bounded ciphertext without storing
  it or receiving the private ECDH keys. Normal web-origin trust still applies.

The modes are explicit and must match on both ends. Node relay is a centralized
path with end-to-end encryption; it must never be described as local P2P.
Room expiry is 120 seconds, foreground sessions last at most 10 minutes. Manual
text is limited to 64 KiB, files to 64 MiB, one file at a time. File acceptance,
SHA256 verification, bounded send buffering and 1 MiB/s pacing are retained.

Only PWA/browser peers use this web protocol today. Native Android and Windows
0.4.0 use FCP2/FCS1 over TLS and need a protocol adapter for native/web sharing.
Their browsers can already use this PWA. Bluetooth and Wi-Fi Direct are separate
native research paths, not enabled capabilities of this PWA.

## Offline and updates

The full public shell is cached for launch. Login and new pairing require the
node to be reachable; cached code does not grant authentication offline. Service
worker updates wait for an explicit update action, which is disabled during a
connection. Authentication/API responses and shared content are never cached.

This module derives the owned PWA UI/protocol from the previous Sites source
commit `6fab0593bd9bf501678a2314a3719c990f021204`; its original private publication
is not changed by this self-hosted deployment.
