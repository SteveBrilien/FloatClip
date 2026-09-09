# FloatClip

FloatClip is an Android 11 / vivo OriginOS-first floating clipboard utility focused on native-feeling overlay interaction, repeated paste workflows, local-first encrypted storage and fail-closed ROM-specific adaptation.

Current source/release line: **0.5.0** (`versionCode=5`).

## What 0.5.0 includes

- Three-page app structure: `状态`, `剪贴板`, `设置`, with system/light/dark appearance modes.
- Velocity-sensitive floating-ball motion: direct drag, inertial continuation, friction, soft edge settling and delayed configurable edge half-hide.
- Continuous panel/bubble transitions with overlap so open/close does not intentionally introduce a blank frame.
- Fixed mode changes the existing panel/scrim state in-place; it no longer destroys and recreates the panel window when pinning/unpinning.
- Expanded panel dragging from border zones and header empty space instead of a tiny dedicated drag handle.
- Clipboard row gestures: single tap paste/copy, double tap pin/unpin, left-swipe reveal actions, long-press extended menu.
- Shared category model across the app and overlay, including `全部`, custom categories, manual reassignment, safe category deletion and basic semantic classification for common data types.
- Overlay windows remain non-focusable, avoiding the previous temporary focus grab that could collapse the active keyboard.
- Reliable normal-mode outside dismissal without touch-through, while fixed mode remains non-modal for repeated paste.
- Encrypted app-private clipboard vault using Android Keystore + AES-GCM, including migration away from the previous plaintext preference store.
- Optional user-selected portable encrypted `FloatClip.vault` backup that can survive app uninstall because it lives in a Storage Access Framework directory chosen by the user.
- Six-digit PIN workflow for the portable backup, with reinstall-safe write protection until an existing backup has been authenticated/restored.
- A user-configured HTTPS sync endpoint placeholder. No private sync hostname is embedded, and 0.5.0 does not automatically upload clipboard data.
- Minimum-importance silent foreground-service notification channel with a shortcut to Android's notification settings.
- ROM-locked OriginOS semantic-resource bridge; the standalone `TYPE_APPLICATION_OVERLAY` renderer remains the permanent recovery path.

See `docs/INTERACTION_0.5.0.md` for gesture/motion invariants and the device acceptance checklist, and `SECURITY.md` for the storage, backup, sync and release-signing model.

## Security and privacy

Clipboard content is treated as sensitive user data. The default design is local-first. The main history is encrypted in app-private storage, and portable backup is separately encrypted before being written to a user-selected shared directory.

The application contains no built-in private synchronization hostname. The endpoint setting is empty by default and only accepts an HTTPS URL entered by the user. Future cross-platform synchronization is intended to be client-side encrypted so a relay server stores/transports ciphertext rather than plaintext clipboard entries or client decryption keys.

A six-digit PIN is convenient but has limited entropy; it protects the current portable-backup format but is not intended to be the sole cryptographic identity for future multi-device end-to-end synchronization. The planned cross-platform design should use a random high-entropy master key with wrapped recovery credentials.

Public tracked files are scanned before release for private deployment hostname patterns. Avoiding infrastructure-name disclosure is only defense-in-depth; authentication, TLS, least privilege and ciphertext-only server handling remain the actual security controls.

## OriginOS integration

The analysed target is vivo PD2115 / V2115A on Android 11 / OriginOS Ocean. `OriginOsRomLock` requires the expected device fingerprint, `com.vivo.floatingball` package version and OEM APK hash to match before the semantic-resource bridge activates.

The current bridge only reads selected semantic resources from the verified OEM package. FloatClip does **not** call private vivo AIDL, request signature-only OEM permissions, inject into SystemUI, or bundle proprietary OriginOS artwork.

Runtime diagnostics use the log tag `FloatClipOriginOS`.

## Android clipboard / foreground-service behavior

Android 10+ limits background clipboard access for normal apps. FloatClip uses its Accessibility integration as the clipboard-observation path when available and does not make the overlay window focusable just to read the clipboard.

The floating overlay runs as a foreground service. Android requires a foreground-service notification; 0.5.0 minimizes that notification instead of attempting to hide it by violating the foreground-service contract.

The target device is intentionally fixed on Android 11, so `targetSdk = 30` remains deliberate for this deployment line.

## Build

MCP TaskProfiles:

- `floatclip_debug` → `scripts/build-orangepi.sh :app:assembleDebug`
- `floatclip_lint` → `scripts/build-orangepi.sh :app:lintDebug`

Outputs:

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- lint report: `app/build/reports/lint-results-debug.html`

The Orange Pi build path uses JDK 17 and a project-local Android 35 toolchain. Release APKs use the established stable FloatClip signing identity so they can overwrite prior accepted installations.

## GitHub CI / release path

Repository: `SteveBrilien/FloatClip`.

`.github/workflows/android.yml` performs independent build/lint verification on GitHub-hosted runners. Runner-generated debug APKs are CI evidence only because their debug signing identity is ephemeral. Tagged public releases fail closed to the preverified stable-signature artifact committed under `dist/` with its checksum.

Release download pattern:

`https://github.com/SteveBrilien/FloatClip/releases/download/v0.5.0/FloatClip-0.5.0-debug.apk`

The exact release SHA-256 and source/build evidence are recorded in `docs/STATUS.md` and `dist/release.env`.

## Device acceptance for 0.5.0

After overwrite-installing the candidate on the target vivo, verify:

1. slow and fast floating-ball releases have visibly different inertia/travel;
2. the ball does not feel strongly magnetized from the middle of the display;
3. vertical release velocity naturally influences the final resting Y position;
4. half-hide contains no jump or discontinuity;
5. panel open/close has no blank-frame flash;
6. repeated fixed/unfixed toggles cause no flash or position reset;
7. opening FloatClip over an active input field does not force the keyboard closed;
8. border/header empty areas can drag the panel without fighting row gestures;
9. single/double/long-press/left-swipe interactions remain distinguishable during normal scrolling;
10. category filters and manual category assignment work in both the app page and floating panel;
11. normal-mode outside tap collapses without touching the underlying app;
12. fixed mode allows repeated paste while the underlying app remains interactive;
13. exact supported OriginOS build still reports ROM lock `MATCHED`.

## Project documentation

- `docs/STATUS.md` — current continuation point, build/release evidence and device acceptance state.
- `docs/INTERACTION_0.5.0.md` — 0.5.0 motion, transition and gesture model.
- `SECURITY.md` — local vault, portable backup, sync and signing security model.
- `docs/ARCHITECTURE.md` — standalone and enhanced-integration architecture.
- `docs/ORIGINOS_ANALYSIS.md` — OriginOS/FloatingBall findings and ROM lock.
- `docs/ROADMAP.md` — milestones and remaining work.
- `CHANGELOG.md` — chronological development record.
- `analysis/originos/` — local captured snapshots/static-analysis material; sensitive or binary analysis data remains Git-ignored where appropriate.
