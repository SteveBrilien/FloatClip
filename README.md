# FloatClip

FloatClip is an Android 11 / vivo OriginOS-first floating clipboard utility focused on native-feeling overlay interaction, repeated paste workflows and safe ROM-specific adaptation.

Current release: **0.4.0** (`versionCode=4`).

## What 0.4.0 includes

- Three-page app structure: `状态`, `剪贴板`, `设置`.
- Appearance modes: `跟随系统`, `浅色`, `深色`.
- Light/dark-safe surface and foreground color pairing so OEM semantic colors cannot produce white-on-white or dark-on-dark content.
- Configurable expanded-panel opacity, width and height.
- Configurable floating-ball size and opacity.
- Configurable edge half-hide depth; `0%` disables half-hide.
- Distance-aware eased edge snapping instead of instant attachment.
- Press/release/landing scale feedback and short panel fade/scale transitions.
- Delayed OriginOS-style edge half-hide after the bubble settles.
- Reliable normal-mode outside dismissal through a dedicated scrim touch dispatcher; outside taps collapse FloatClip without touching the app underneath.
- Fixed mode remains panel-only/non-modal for repeated paste workflows.
- Text clipboard history with de-duplication, pinning, deletion, search and custom categories.
- Automatic synchronization of the current Android system clipboard when the floating panel opens.
- Optional AccessibilityService for one-tap paste into the focused editable field.
- Runtime appearance refresh without restarting the foreground overlay service.

## OriginOS integration

The standalone `TYPE_APPLICATION_OVERLAY` renderer is the permanent recovery path. ROM-specific integration is isolated behind `OriginOsSystemBridge` and is strictly fail-closed.

The analysed target is:

- vivo PD2115 / V2115A
- Android 11
- OriginOS Ocean
- fingerprint: `vivo/PD2115/PD2115:11/RP1A.200720.012/compiler1018205834:user/release-keys`
- native floating-ball package: `com.vivo.floatingball`
- FloatingBall version: `2.5.32.0` (`253200`)
- FloatingBall APK SHA-256: `e24c914cc6e74f01922cd89385b2168226e5c425a5b212543ff4800ffdd0989e`

`OriginOsRomLock` requires fingerprint + package version + APK hash to match before the ROM bridge can activate.

The current bridge only reads semantic resources from the verified OEM package. FloatClip does **not** call vivo private AIDL, request signature-only permissions, inject into SystemUI, or bundle proprietary OriginOS artwork.

Runtime diagnostics use the log tag `FloatClipOriginOS`.

## Android 11 clipboard behavior

Android 10+ limits background clipboard reads for normal apps. FloatClip therefore synchronizes the current system clipboard when its floating panel is actively opened rather than attempting unrestricted always-on background polling.

The current target device is fixed on Android 11, so `targetSdk = 30` remains intentional for this deployment.

## Build

MCP TaskProfiles:

- `floatclip_debug` → `scripts/build-orangepi.sh :app:assembleDebug`
- `floatclip_lint` → `scripts/build-orangepi.sh :app:lintDebug`

Outputs:

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- lint report: `app/build/reports/lint-results-debug.html`

The Orange Pi build path uses JDK 17 and the project-local Android 35 toolchain.

Latest clean verification from release source commit `effe2409b99cc124801ed5a5b2269a797c9cf380`:

- debug job `task-floatclip_debug-4b06e1d958b0433bbc61` — **BUILD SUCCESSFUL**
- APK Artifact `artifact-07ac59e239e046acb3703212562b29da`
- APK size `2,582,629` bytes
- APK SHA-256 `4aa2e1dfbaedc02ee9d2ca63328d128b8235d6861735869d151c33827a084182`
- lint job `task-floatclip_lint-781f539504ae4d7e8a3b` — **BUILD SUCCESSFUL**
- lint Artifact `artifact-e907432f6e764a4c89e6332f3ddedc7e`

## GitHub CI / release path

`.github/workflows/android.yml` is ready for a FloatClip GitHub repository:

- pushes / pull requests / manual runs build and lint on GitHub-hosted Ubuntu;
- APK and lint report are uploaded as Actions artifacts;
- tags matching `v*` publish the APK through GitHub Releases.

The local annotated release tag `v0.4.0` points to commit `effe2409b99cc124801ed5a5b2269a797c9cf380`.

The GitHub repository is `SteveBrilien/FloatClip`. `main` and annotated tag `v0.4.0` are published, and GitHub Actions completed both build/lint and release automation successfully. The release APK is deliberately the stable-signature artifact produced from source commit `effe2409b99cc124801ed5a5b2269a797c9cf380`, not the GitHub runner's ephemeral debug-signed CI APK.

Release APK: `https://github.com/SteveBrilien/FloatClip/releases/download/v0.4.0/FloatClip-0.4.0-debug.apk`

- size: `2,582,629` bytes
- SHA-256: `4aa2e1dfbaedc02ee9d2ca63328d128b8235d6861735869d151c33827a084182`
- signing certificate SHA-256: `9e476b6a60e08a14ec0d563ee1dde772145c70ea0463ce227b9b6e9e50275056`

GitHub CI builds remain compile/lint evidence only until protected signing credentials are provisioned in Actions. The release workflow fails closed to a preverified stable-signature artifact under `dist/`.

## Device acceptance

The 0.4.0 candidate still needs the user's manual overwrite-install check on the vivo target. The important acceptance points are:

1. light-mode and dark-mode text/background contrast;
2. drag feel and edge-snap timing;
3. delayed half-hidden resting position;
4. normal-mode outside tap collapses without touch-through;
5. fixed mode keeps the underlying app usable for repeated paste;
6. opening the panel still synchronizes the current system clipboard;
7. the exact supported OriginOS build still reports ROM lock `MATCHED`.

## Project documentation

- `docs/STATUS.md` — current continuation point and acceptance state.
- `docs/ARCHITECTURE.md` — standalone and enhanced-integration architecture.
- `docs/ORIGINOS_ANALYSIS.md` — OriginOS/FloatingBall findings and ROM lock.
- `docs/ROADMAP.md` — milestones and remaining work.
- `CHANGELOG.md` — chronological development record.
- `analysis/originos/` — local captured snapshots/static-analysis material; sensitive or binary analysis data remains Git-ignored where appropriate.
