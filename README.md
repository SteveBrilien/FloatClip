# FloatClip

Android 11 / vivo OriginOS-first floating clipboard application.

Current stage: standalone MVP + first ROM-locked OriginOS semantic-resource bridge implemented; debug/lint and final device acceptance are still pending.

## Standalone functionality

- Edge-snapping floating bubble with remembered side/Y position.
- Tap to expand; outside touch collapses the panel unless fixed mode is enabled.
- Fixed mode supports repeated multi-paste without closing the panel.
- Text clipboard history with de-duplication, pinning, deletion, search and custom categories.
- Optional AccessibilityService for one-tap paste into the currently focused editable field.
- Explicit "read current clipboard" action for Android 11/OEM clipboard validation.
- Native Android Views only; the core APK does not depend on Compose or AndroidX.

## OriginOS integration

The standalone renderer is the permanent recovery path. ROM-specific integration is isolated behind `OriginOsSystemBridge` and is strictly fail-closed.

The currently analysed target is:

- vivo PD2115
- Android 11
- OriginOS Ocean
- fingerprint: `vivo/PD2115/PD2115:11/RP1A.200720.012/compiler1018205834:user/release-keys`
- native floating-ball package: `com.vivo.floatingball`
- FloatingBall version: `2.5.32.0` (`253200`)
- FloatingBall APK SHA-256: `e24c914cc6e74f01922cd89385b2168226e5c425a5b212543ff4800ffdd0989e`

`OriginOsRomLock` requires fingerprint + version + APK hash to match before the ROM bridge is allowed to activate.

The first bridge level is intentionally limited to semantic resource lookup from the installed FloatingBall package. It does **not** call vivo private AIDL, request signature permissions, inject into SystemUI, or bundle vivo proprietary "未来科技" artwork.

Static analysis confirmed the native OEM implementation uses privileged system windows and private services, including `FloatingBallIdleView`, `FloatingBallEdgeView`, `FloatingBallExpandedView`, `IFloatingBallService` and related interfaces. Any future native-controller reuse must therefore live in a separate privileged/root/LSPosed-side adapter.

Runtime OriginOS diagnostics use log tag:

`FloatClipOriginOS`

## Android 11 clipboard policy

A normal background app cannot continuously read clipboard contents on Android 10+ unless it is the default IME or has privileged/system capabilities. FloatClip therefore keeps automatic background capture behind future privilege/SystemUI bridge work and retains an explicit import flow in standalone mode.

`targetSdk = 30` is intentional for this fixed Android 11 deployment target. The expired-target Play lint rule is disabled specifically; other lint checks remain enabled.

## Build

MCP v2 TaskProfiles:

- `floatclip_debug` → `scripts/build-orangepi.sh :app:assembleDebug`
- `floatclip_lint` → `scripts/build-orangepi.sh :app:lintDebug`

APK output:

`app/build/outputs/apk/debug/app-debug.apk`

Lint report:

`app/build/reports/lint-results-debug.html`

The build uses JDK 17 and the ARM64 Android toolchain available to the Orange Pi / project workspace.

## Target-device acceptance

Expected ADB target:

`192.168.3.44:5555`

Before the current milestone is complete:

1. add Android 11 package visibility for `com.vivo.floatingball` via `<queries>`;
2. run debug build and fix all compiler failures;
3. run lint and address blocking findings;
4. install/upgrade the APK on the target vivo device;
5. launch `com.floatclip.app/.MainActivity`;
6. verify ROM-lock status and `FloatClipOriginOS` logs;
7. validate overlay permission, bubble start, drag/snap, expand/collapse, fixed mode, clipboard import and accessibility paste;
8. inspect runtime logs/window state for crashes or permission loops.

The previous attempt stopped before this acceptance sequence because the Codex MCP Dev registry remained in `TOOL_REGISTRY_NOT_READY` after TaskProfile configuration reload. This is recorded as a control-plane blocker, not a known FloatClip build/runtime failure.

## Project documentation

- `docs/STATUS.md` — exact current continuation point and acceptance checklist.
- `docs/ARCHITECTURE.md` — standalone and enhanced-integration architecture.
- `docs/ORIGINOS_ANALYSIS.md` — completed FloatingBall/OriginOS findings and ROM lock.
- `docs/ROADMAP.md` — milestone status and remaining work.
- `CHANGELOG.md` — chronological development/update record.
- `analysis/originos/` — captured snapshots, static-analysis outputs and pulled system APK hashes.
