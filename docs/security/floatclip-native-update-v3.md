# FloatClip signed native updates (v3 key rotation)

As of 2026-11-14 Android build57, the Orange Pi owns the **new v3 RS256 private update-manifest key**.  The application only embeds its public key. This is *not* the Android APK signing key, which must remain the same as installed `com.floatclip.probe` packages.

## Trust and compatibility

* Previous v2 update feed (branch `native-updates-v2`) remains unchanged and continues to verify under its original key. Do not replace it or rename it.
* Build57+ attempts the v3 authenticated update feed (`native-updates-v3` branch, path `updates/native-updates-v3.json`), then the old authenticated v2 feed only if v3 is unreachable. An invalid reachable v3 signature fails closed. An independently labeled GitHub candidate can be listed, but the APK installer always validates the actual downloaded SHA-256, package name, versionCode and original Android signing certificate.
* **An old installed APK does not know the v3 public key.** It must be upgraded using an older recognized signed bridge or by one consented manual in-place APK install. The private manifest key alone cannot fix build50 discovery on old clients.
* This rotation is scoped to Android. Windows' existing trust settings were not silently altered.

## Key storage / do not leak

* Private key (only on Orange Pi, outside every Git worktree): `PROJECT_ROOT/.mcp/secrets/floatclip-update-20261114-v3/signing-private.pem`; owner orangepi, file mode `0600`, directory mode `0700`. Never commit or print the private key. Avoid handing it to a public GitHub workflow.
* Public PEM: `updates/native-update-v3-public.pem` (public; may be in Git); SHA-256 of SPKI DER: `9b6580695419652cca33be86600085c50f964b01c8a543767385da8189300bb7`.
* Client trust: `nearby-probe/src/main/java/com/floatclip/probe/UpdateTrust.kt`, pinned `NEW_KEY_ID`/`NEW_PUBLIC_KEY`; old key remains for compatibility.
* This is one on-host copy. An encrypted, off-host recovery backup is still required: do not count any unencrypted copy as cloud backup. Make it with a user-owned offline encryption recipient and test recovery before deleting/changing the primary signing key.

## Publishing a new signed release

1. Build, test and publish an APK with unchanged Android app-signing certificate. Record its exact HTTPS download URL, size, SHA-256, package and build number. Never publish a manifest first.
2. Write `updates/share-preview-v3.json` containing `schema=1`, `channel=share-preview` and the verified Android/Windows metadata. Use authorized HTTPS hosts only.
3. On the Orange Pi only, sign from `PROJECT_ROOT/analysis/...` worktree with the existing vetted script (example paths are relative to that worktree):

   `python3 scripts/sign-native-updates.py --key ../../.mcp/secrets/floatclip-update-20261114-v3/signing-private.pem --key-id floatclip-updates-20261114-v3 --source updates/share-preview-v3.json --output updates/native-updates-v3.json --days 90`

4. Validate the JWS with the pinned Android verifier, including tampering and expiry tests. Check monotonic version/build and verify download hash again.
5. Update only the `native-updates-v3` branch's signed JSON at its immutable URL; do not rewrite v2 feed. Test GitHub RAW and jsDelivr fallback from a network without a GitHub proxy.
6. Rotate/re-sign the v3 feed **before** expiry. Preserve already distributed APK signing identity. Uploading a new JSON cannot make an old APK accept a new public key.

Do not bind the signing key to an internet-facing API: download service should serve **only public signed metadata and packages**, and the signing tool should run as a restricted local publishing step. No extra SSH, ADB, MCP or inbound web ports are needed for release checks.
