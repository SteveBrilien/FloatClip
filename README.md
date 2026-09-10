# FloatClip

FloatClip is an Android 11 / vivo OriginOS-first floating clipboard utility focused on native-feeling overlay interaction, repeated paste workflows, local-first encrypted storage and fail-closed ROM-specific adaptation.

Current source line: **0.5.2** (`versionCode=7`). The currently published release remains 0.5.1 until the final clean-build/signing/tag gate completes.

## What 0.5.2 changes

- Replaces the close-time delayed bubble insertion with one pre-created hidden destination bubble and a single 420 ms panel-to-bubble animator, avoiding a WindowManager surface insertion in the middle of the visible transition.
- Removes double-tap-to-pin. Single tap has one meaning only (paste/copy), left swipe retains pin/delete, and long press opens extended actions.
- Replaces the platform PopupMenu with an in-panel action sheet that follows the active FloatClip/OriginOS light/dark palette and exposes copy, pin/unpin, delete and category assignment.
- Uses a 360 ms pressure-independent long-press recognizer with expanded motion tolerance and haptic confirmation so a normal light hold is reliable.
- Replaces category up/down buttons with drag-handle ordering using Android's platform drag-and-drop API; the stored order is shared with the floating category strip.
- Adds an accessibility-hosted overlay runtime: when one-key paste is enabled, the system-managed AccessibilityService binds and hosts the overlay runtime, allowing FloatClip to leave foreground-service state and remove its own persistent FGS notification.
- Retains the foreground-service runtime as a fallback when Accessibility is disabled. Explicit Android/OriginOS Force stop still cannot be self-bypassed by an ordinary app.

See `docs/INTERACTION_0.5.2.md` for the gesture/runtime model, prior-art review and device acceptance checklist.

## What 0.5.1 changes

- The normal-mode outside-touch layer is now visually transparent instead of dimming app content; this removes the OriginOS status/navigation-bar brightness mismatch and the fast luminance flash when dismissing the panel.
- Panel dismissal is lengthened to 380 ms with a low-amplitude scale/translation curve and a timed bubble cross-fade; fixed/unfixed mode no longer animates the whole panel/scrim.
- The title is vertically centered and the redundant right-side collapse chevron is removed.
- Bubble side/Y are persisted at finger release and again immediately before panel expansion, so interrupting a fling/spring cannot restore an older docking position after collapse.
- Added a 30–120% motion-sensitivity control (default 65%). Lower values scale launch velocity down and increase fling friction while direct dragging remains 1:1.
- Added best-effort keep-alive recovery for task removal, reboot and package replacement, while explicit Stop disables recovery. OriginOS force-stop/autostart policy can still override normal Android restart semantics.
- System `uiMode` is now authoritative for light/dark selection; stale OEM semantic colors are rejected when their luminance conflicts with the current system mode, and OriginOS resources are re-resolved against the current configuration.
- Category storage is ordered rather than set-based, with in-app up/down controls; the same order feeds the app selector and the floating panel category strip.

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

FloatClip now has two runtime paths. With one-key-paste Accessibility enabled, the system-managed AccessibilityService binds the overlay runtime and FloatClip can leave foreground-service state, so its own persistent FGS notification is removed. Without Accessibility, the overlay uses the standard foreground-service fallback and Android requires its notification.

An explicit Force stop puts the package into Android's stopped state; neither runtime path is intended to bypass that user/system action. OriginOS autostart/background-power policy can also remain stricter than AOSP, so background survival is treated as best-effort rather than guaranteed.

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

`https://github.com/SteveBrilien/FloatClip/releases/download/v0.5.1/FloatClip-0.5.1-debug.apk`

The exact release SHA-256 and source/build evidence are recorded in `docs/STATUS.md` and `dist/release.env`.

## Device acceptance for 0.5.2

After overwrite-installing the candidate on the target vivo, verify the ten checks in `docs/INTERACTION_0.5.2.md`, especially: continuous close motion with no twitch, single tap never pinning, reliable light long-press, themed action sheet, drag-to-reorder categories, notification-free accessibility-hosted runtime, and foreground-service fallback when Accessibility is disabled. Explicit Force stop remains an expected hard stop.

## Project documentation

- `docs/STATUS.md` — current continuation point, build/release evidence and device acceptance state.
- `docs/INTERACTION_0.5.2.md` — current gesture, close-transition, background-runtime and prior-art decisions.
- `docs/INTERACTION_0.5.0.md` — 0.5.0 motion, transition and gesture model.
- `SECURITY.md` — local vault, portable backup, sync and signing security model.
- `docs/ARCHITECTURE.md` — standalone and enhanced-integration architecture.
- `docs/ORIGINOS_ANALYSIS.md` — OriginOS/FloatingBall findings and ROM lock.
- `docs/ROADMAP.md` — milestones and remaining work.
- `CHANGELOG.md` — chronological development record.
- `analysis/originos/` — local captured snapshots/static-analysis material; sensitive or binary analysis data remains Git-ignored where appropriate.
