# Changelog

All notable FloatClip development changes are recorded here.

## 2026-09-11

### 0.5.5 visual polish and restored swipe actions
- Restored swipe pin/unpin and delete with icon/label action cells in overlay and in-app history; kept right-swipe, front-tap, other-row and idle closure.
- Preserved action clipping and DOWN ownership to prevent covered buttons receiving taps.
- Kept the 0.5.4 retained-window/motion implementation unchanged after the user confirmed jitter was gone.
- Replaced full-width navigation strips with header back/category icons and reduced heading size.
- Made settings rows whole-row targets with plain status/chevron or switch controls, removing repeated blue button blocks.
- Flattened cards and search; added keyboard search and an icon action; simplified selected navigation styling.
- Kept long-press menus single-column, added line icons and a delete separator, narrowed width and positioned near the selected entry.
- Version 0.5.5 / code 10; new visual/gesture acceptance remains pending.



### 0.5.4 retained windows and reduced UI
- Replaced close-time window removal/cross-fade/scale with attached-window visibility and touchability switching.
- Kept bubble dock coordinates unchanged on close; removed delayed post-close edge-hide and ignored sub-slop bubble MOVE events.
- Switched long-press actions and category selection to scrollable vertical lists; disabled panel dragging while menus are open.
- Removed swipe action machinery; deletion is available from long press.
- Reduced Home to enable/disable and shortcuts; moved category management and settings groups into nested views.
- Removed repeated permission/background explanations, daily gesture banners and the unused sync editor.
- Added nested Back handling and saved navigation state. Preserved essential backup write-protection indicators.
- Version 0.5.4 / code 9; device jitter acceptance remains pending.



### 0.5.3 touch routing, stable dismissal and simplified controls
- Fixed covered swipe buttons receiving taps through translucent rows; action layers now clip to the revealed area and disappear when closed.
- Restored single-tap copy, double-tap pin and platform long-press menus; removed swipe pin buttons.
- Added right-swipe/front-tap/other-row/idle closure and retained vertical list scrolling.
- Reused the bubble surface during panel display, prevented close-time input/redraw, and removed close translation/scale changes.
- Replaced category drag shadows and repeated reparenting with finger-following rows, animated gaps, edge scrolling, commit-on-release and cancellation rollback.
- Moved in-app delete controls to swipe; added category/history deletion confirmation.
- Added special-access status and settings guidance for overlay, optional accessibility, battery optimisation and manual OriginOS policies.
- Version 0.5.3 / code 8. Device visual/gesture acceptance remains pending manual overwrite installation.

## 2026-09-10

### 0.5.1 visual continuity, dock persistence and keep-alive refinement
- Raised app version to `0.5.1` (`versionCode=6`).
- Replaced the visible normal-mode dim scrim with a fully transparent outside-touch catcher so app content, status-bar and navigation regions no longer show different brightness while the panel is open; removing the catcher therefore cannot produce the previous luminance flash.
- Lengthened panel dismissal to 380 ms, reduced close scale/translation amplitude and retimed the bubble cross-fade for a continuous handoff.
- Centered the clipboard title vertically and removed the redundant right-side collapse chevron/resource.
- Persisted floating-ball side/Y both at finger release and immediately before panel expansion, preventing an interrupted fling/spring from restoring an older docking position after collapse.
- Added a 30–120% floating-ball motion sensitivity setting (default 65%); post-release velocity and friction now respond to this setting while direct dragging remains 1:1.
- Added best-effort overlay keep-alive state plus task-removal restart scheduling and `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` recovery. Explicit Stop disables recovery.
- Added an app-details shortcut for OriginOS background/autostart policy because OEM force-stop remains outside normal foreground-service restart guarantees.
- Made system `uiMode` authoritative for overlay light/dark mode, reject stale OEM semantic colors that conflict with the current luminance direction, and re-resolve OriginOS package resources against the current configuration.
- Migrated category persistence from unordered `StringSet` storage to an ordered JSON list and added in-app up/down ordering controls shared with the floating category strip.
- Added `docs/INTERACTION_0.5.1.md` with the continuity/persistence model and device acceptance checklist.
- Initial debug build `task-floatclip_debug-0c8e4ab7639645c08caf` completed successfully; final clean-commit build/lint and signed GitHub Release evidence are recorded after publication.

## 2026-09-09

### 0.5.0 physics, gestures, categories and encrypted vault
- Raised app version to `0.5.0` (`versionCode=5`).
- Reworked floating-ball release handling around gesture velocity, inertial continuation, friction and soft edge settling so fast/slow throws no longer feel identical or instantly magnetized.
- Preserved delayed edge half-hide while removing intentional position teleports between direct drag, inertial motion, settle and idle states.
- Reworked panel open/close into overlapping bubble/panel transitions to avoid blank frames between windows.
- Reworked fixed mode so it changes the existing scrim/touch state in-place instead of destroying and recreating the panel; this removes the known fixed-mode flash.
- Expanded panel dragging to border zones and header empty space instead of a tiny dedicated drag handle.
- Added progressive clipboard-row gestures: single-tap paste/copy, double-tap pin, left-swipe reveal actions and long-press extended actions.
- Added a shared category model and bottom category bar, in-app manual category assignment, safe category deletion and basic semantic auto-classification when matching categories exist.
- Removed the temporary overlay focus-grab clipboard path that could force the active IME/keyboard to collapse.
- Migrated primary clipboard history from plaintext preferences to an Android Keystore + AES-GCM app-private vault.
- Added a user-selected Storage Access Framework portable `FloatClip.vault` encrypted backup with six-digit PIN support and reinstall-safe write protection before authenticated restore.
- Added a user-configured HTTPS sync endpoint field. No sync hostname is built into the application, and 0.5.0 does not automatically upload clipboard content.
- Reserved ciphertext-only end-to-end sync semantics for future Windows support; the server is not intended to receive plaintext clipboard entries or client decryption keys.
- Reduced the foreground-service notification to a minimum-importance silent/no-badge channel while preserving Android's required foreground-service notification contract.
- Added `SECURITY.md` and `docs/INTERACTION_0.5.0.md` covering storage, backup, sync, IPC and motion/gesture invariants.
- Protected the internal appearance-refresh broadcast with an app-defined signature permission.
- Public tracked-file domain scan is clean for the private deployment hostname patterns; public documentation uses generic user-configured endpoint language.
- Final clean-build, signing and GitHub Release evidence is recorded after the release asset is generated.

### 0.4.0 adaptive theme / native edge interaction
- Raised app version to `0.4.0` (`versionCode=4`).
- Added `跟随系统 / 浅色 / 深色` appearance modes for both the app and floating clipboard, with normalized foreground colors so OEM semantic resources cannot produce white-on-white or dark-on-dark content.
- Split the main app into three bottom-navigation pages: `状态`, `剪贴板`, and `设置`; appearance, permissions, theme and overlay controls now live on the final Settings page.
- Added configurable floating-ball opacity (35–100%, default 76%) and configurable edge half-hide depth (0–55%, default 38%).
- Replaced instantaneous edge snapping with distance-aware eased motion, press/release scale feedback and a subtle landing animation.
- Added OriginOS-style delayed edge half-hide: after settling on the nearest edge, the bubble waits briefly and smoothly moves partially off-screen; touching/dragging restores direct interaction.
- Added smooth fade/scale transitions for opening and closing the expanded clipboard panel.
- Replaced the fragile normal-mode outside-click listener with a dedicated touch-consuming scrim dispatcher so taps outside the panel deterministically collapse it without passing through to the underlying app.
- Preserved fixed mode as a panel-only/non-modal overlay for repeated paste workflows.
- Added an appearance-refresh broadcast so changing theme/opacity/size settings can rebuild a currently running overlay without restarting the foreground service.
- Preserved automatic clipboard synchronization and added generation guards so delayed clipboard reads cannot act on a panel that has already been removed/rebuilt.
- Pre-commit debug: `task-floatclip_debug-00bbe66b99254d1794a3` — **BUILD SUCCESSFUL** — Artifact `artifact-a00d77ca1a674edd9b951e47b6e5042e`.
- Pre-commit lint: `task-floatclip_lint-f743f8dcb8724625ab41` — **BUILD SUCCESSFUL** — Artifact `artifact-c16f2b10f7664e5aa9ea16978c7df168`; lint report contains 5 warnings and no errors.
- Pre-commit APK: 2,582,629 bytes; SHA-256 `9fd3613d493294efcb76f82ab13c5ddb91959b94c0294353d675891785484959`.
- Clean distribution source commit: `d9d20d531bd84006160014b41e3bad0f7eea6835`.
- Clean distribution rebuild: `task-floatclip_debug-cc9d040e57ba41d49a7c` — **BUILD SUCCESSFUL** — Artifact `artifact-e2f31f436a744c7f8ca071e9c2d10f58`.
- Clean distribution APK: 2,582,629 bytes; SHA-256 `ae8ba13289d1880b6b4fff46b8dc8ab9ce41831d2b6382b4b94fc6e4508f9ddf`.
- Publication is temporarily blocked because this MCP connection is attached to a secondary runtime whose configured artifact server is not owned/running; the APK itself is complete and registered as a first-class Artifact.

### 0.3.0 native-style UI / movable panel
- Raised app version to `0.3.0` (`versionCode=3`).
- Replaced the launcher artwork with a white-background, flat black copy/paste icon.
- Replaced the text-glyph floating bubble with a circular semi-transparent white bubble and black vector copy/paste icon.
- Added a unified monochrome Fluent-like icon set for overlay drag, pin/fixed mode, collapse and delete actions.
- Added persisted in-app sliders for panel opacity (45–100%), panel width (280–420dp), panel height (300–620dp) and bubble size (42–64dp), plus a reset-to-default action.
- Added free dragging of the expanded panel from its header with persisted x/y position and screen-bound clamping.
- Preserved safe normal-mode scrim behavior while fixed mode remains non-modal for repeated paste operations.
- Flattened the main settings UI by removing card elevation and using subtle outlined surfaces.
- Updated empty-history messaging for automatic clipboard synchronization.
- Pre-commit debug: `task-floatclip_debug-ec6d3df36d6f4afdb7d2` — **BUILD SUCCESSFUL** — Artifact `artifact-3eae05648825475c9cc11a1ae321b2a0`.
- Pre-commit lint: `task-floatclip_lint-47ed77f737ef48b494f3` — **BUILD SUCCESSFUL** — Artifact `artifact-3e654c73500746979df0d232d7fbf161`.


### UI and runtime iteration
- Reworked the floating overlay toward a denser OriginOS-style interaction model: 48dp bubble, compact panel, tighter action controls and reduced default Android button styling.
- Reworked `MainActivity` from a developer/test panel into a compact card-style settings/status page.
- Removed the large manual "read current clipboard" action from the normal overlay flow.
- Added automatic system-clipboard synchronization whenever the floating panel is opened.
- Device validation confirmed deduplication behavior: history remained at 3 items, IDs/text/category/pin state stayed unchanged, and one existing item's timestamp was refreshed.
- Investigated the target vivo input method `com.vivo.ai.ime/.main.IMEService`; no public clipboard/history Provider or Service suitable for a normal APK was found. Full vivo-IME history integration is therefore deferred to a future optional privileged/Shizuku/LSPosed bridge.
- Identified an OriginOS outside-touch problem in the first overlay revision: `FLAG_NOT_TOUCH_MODAL + WATCH_OUTSIDE_TOUCH` could allow the collapse tap to reach the app underneath.
- Changed normal mode to use a transparent full-screen scrim that consumes outside taps before collapsing.
- Preserved panel-only/non-modal behavior in fixed mode so the underlying app can remain interactive for repeated paste workflows.

### Device migration and acceptance
- Resolved `INSTALL_FAILED_UPDATE_INCOMPATIBLE` caused by a one-time debug signing-key mismatch.
- Backed up the existing FloatClip SharedPreferences privately, migrated to the current signing identity, restored `floatclip_store.xml` and `floatclip_overlay.xml`, and verified device-side SHA values against the backups.
- Restored FloatClip AccessibilityService and overlay permission after migration.
- Confirmed MainActivity launch, foreground overlay service, system overlay window and automatic clipboard synchronization on the vivo target.
- The latest second-interaction candidate passed debug and lint; final scrim/fixed-mode device confirmation is pending a manual overwrite install / next available device session.

### Latest candidate
- Debug job: `task-floatclip_debug-6b8a334bafb243eaa7fd` — **BUILD SUCCESSFUL**.
- APK Artifact: `artifact-a17025eff83d4f949defea6f866158ce`.
- APK size: `2,524,512` bytes.
- APK SHA-256: `262a6a0a1c2a00b1262b0138c73814f1b4526867ad8453407d08555da23865c2`.
- Lint job: `task-floatclip_lint-9d7b411d3b304cfca304` — **BUILD SUCCESSFUL**.
- Lint Artifact: `artifact-2919a3f89c2246258c556a76f98d0559`.
- Clean distribution rebuild source commit: `1fd7b7f0c307de655af4bf31e9f5591e1ebbaeec`.
- Clean distribution rebuild job: `task-floatclip_debug-262b23f6b47a41ae82cb` — **BUILD SUCCESSFUL**.
- Clean distribution Artifact: `artifact-1b00d27060b54df0ba4bebcc9473ca19`.
- Clean distribution APK SHA-256: `9272d41f07aebc0f41b535bb79026ebdcf4ddde8beb5acd59d727ce2d0f8fc54`.

### MCP integration notes — current
- Recorded MCP issue `MCP-20260909-034520-adb-sandbox-server-hijack`: a sandbox adb daemon can bind the shared host `5037` while lacking USB visibility, replacing a working host daemon and making USB devices disappear from MCP ADB calls.
- Removed the temporary sandbox ADB TaskProfiles used during diagnosis.
- Narrowed the `adb` HostCapability back to fixed FloatClip install/launch/permission actions and removed one-time migration actions after the data migration completed.

### Build and validation
- Added Android 11 package visibility for `com.vivo.floatingball` through the manifest `<queries>` declaration.
- `floatclip_debug` completed successfully with job `task-floatclip_debug-b7b8f0e363a34d4c92af`.
- Produced `app/build/outputs/apk/debug/app-debug.apk` as Artifact `artifact-1a81ea498bf341c1985fa68d76f50463`.
- APK size: `2,512,988` bytes.
- APK SHA-256: `2fa88da2ea12dcaa81b15144a1ebdf9dce4a574a0253946ea6afc73d9ff9efdb`.
- `zipalign -c -v 4` passed.
- `apksigner verify` passed; the current deliverable is Android debug-signed with APK Signature Scheme v2.
- `floatclip_lint` completed successfully with job `task-floatclip_lint-46957a67acb2455d9da2`: 0 errors and 2 intentional/non-blocking `DiscouragedApi` warnings for ROM semantic-resource lookup.

### MCP integration notes
- Worked around MCP 2.1.0 durable-task HostCapability mount failure by using FloatClip's existing workspace-local JDK/Android SDK and removing the affected build profiles' `required_capabilities` entries; sandbox isolation was not weakened.
- Independent FloatClip reproduction evidence was appended to MCP issue `MCP-20260908-192700-hostcap-mountpoint`.
- MCP Artifact publication succeeded but currently returns a loopback-only URL; the limitation is tracked as `MCP-20260908-192701-artifact-external-url`.

### Device acceptance status
- Orange Pi ADB device enumeration is currently empty.
- Explicit connection to the laboratory address `<target-device-address>:5555` returns `No route to host` because the target vivo is no longer on a network routable from the Orange Pi.
- Final install/launch/OriginOS runtime acceptance remains pending only on restoration of a routable device path; no build or lint blocker remains.

## 2026-09-08

### Added
- Added the first ROM-locked OriginOS integration implementation in `app/src/main/java/com/floatclip/app/integration/OriginOsRomBridge.kt`.
- Added `OriginOsRomLock`, which fail-closes unless all three target-ROM checks match:
  - full Android build fingerprint;
  - `com.vivo.floatingball` version name/code;
  - SHA-256 of the installed `FloatingBall.apk`.
- Added `LockedOriginOsSystemBridge`, a semantic-resource-only bridge that reads selected public package resources from the exact analysed `com.vivo.floatingball` build.
- Added ROM-lock state/result/identity models for diagnostics.
- Added `FloatClipOriginOS` runtime logging for ROM-lock and bridge activation diagnostics.
- Added OriginOS Bridge / ROM-lock status display to the main application UI.
- Initialized the integration registry from both `MainActivity` and `ClipboardOverlayService`, so the standalone renderer can automatically consume a verified OriginOS theme snapshot.

### OriginOS / FloatingBall static-analysis findings
- Target ROM fingerprint:
  - `vivo/PD2115/PD2115:11/RP1A.200720.012/compiler1018205834:user/release-keys`
- OriginOS family observed on device: `OriginOS Ocean`.
- Native floating-ball package: `com.vivo.floatingball`.
- FloatingBall version: `2.5.32.0` (`versionCode=253200`).
- FloatingBall APK SHA-256:
  - `e24c914cc6e74f01922cd89385b2168226e5c425a5b212543ff4800ffdd0989e`
- Native window/surface classes observed on the target device:
  - `FloatingBallIdleView`
  - `FloatingBallDragView`
  - `FloatingBallEdgeView`
  - `FloatingBallExpandedView`
- Static analysis found private OEM binder/service surfaces including:
  - `com.vivo.floatingball.aidl.IFloatingBallService`
  - `com.vivo.floatingball.aidl.IFloatingBallServiceForSettings`
  - `com.vivo.floatingball.aidl.IUpSlideServiceForFloatingBall`
- The OEM package requests privileged/signature-level capabilities such as `INTERNAL_SYSTEM_WINDOW`, `WRITE_SECURE_SETTINGS`, `INJECT_EVENTS`, and other vivo-only permissions. FloatClip therefore does **not** attempt to impersonate or directly drive those private services from the normal app process.
- Identified semantic resource names suitable for safe ROM-locked lookup, including floating-ball colors, dimensions, corner radii, skin/layout resources, and edge/idle geometry.

### Design decisions
- The standalone `TYPE_APPLICATION_OVERLAY` implementation remains the permanent recovery path.
- ROM-specific integration is strictly optional and fail-closed.
- No vivo proprietary artwork is copied into the FloatClip APK.
- No private vivo AIDL is called by the current application build.
- The first bridge level is limited to semantic package-resource reuse. Native controller reuse remains a future privileged/root module concern.
- Any fingerprint/version/hash mismatch disables the OriginOS bridge and leaves the normal FloatClip palette active.

### Build / MCP configuration
- Registered `floatclip_debug` TaskProfile using the v2 TaskProfile schema.
- Registered `floatclip_lint` TaskProfile using the v2 TaskProfile schema.
- Corrected the artifact schema to the v2 form: `{path, name, mime, required}` after an initial compatibility-path `AttributeError`.

### Pending validation
The following steps were not completed in this development pass because the Codex MCP Dev tool registry entered a persistent `TOOL_REGISTRY_NOT_READY` state immediately after TaskProfile configuration reload:
- run `floatclip_debug`;
- run `floatclip_lint`;
- fix any resulting compiler/lint errors;
- install the APK to `<target-device-address>:5555`;
- launch `com.floatclip.app/.MainActivity`;
- verify the ROM lock through UI and `FloatClipOriginOS` logs;
- validate overlay permission, bubble launch, expand/collapse, edge snap, fixed mode, and multi-paste on the target vivo device.

### Known follow-up
- Add an explicit Android package-visibility `<queries>` declaration for `com.vivo.floatingball` before final device acceptance, so `PackageManager.getPackageInfo()` remains deterministic on Android 11 package-visibility implementations.
- Re-run debug + lint after that manifest change, then perform the complete ADB acceptance sequence.

## 2026-09-09T08:20:20.356362Z — UPDATE: Prepare GitHub CI and tagged APK release publishing

Added .github/workflows/android.yml. Main/PR/manual runs build and lint on GitHub-hosted Ubuntu with JDK 17 and Android 35, uploads APK and lint artifacts, and v* tags publish the debug APK through GitHub Releases using the repository GITHUB_TOKEN. This provides a GitHub-backed distribution path once a FloatClip repository is connected.

Files:
- `.github/workflows/android.yml`
- `CHANGELOG.md`

## 2026-09-09T08:55:36.454038Z — RELEASE: Finalize local 0.4.0 release source and GitHub handoff

Revalidated the GitHub-ready release source commit effe2409b99cc124801ed5a5b2269a797c9cf380 with floatclip_debug and floatclip_lint. Debug job task-floatclip_debug-4b06e1d958b0433bbc61 succeeded and produced Artifact artifact-07ac59e239e046acb3703212562b29da (2,582,629 bytes, SHA-256 4aa2e1dfbaedc02ee9d2ca63328d128b8235d6861735869d151c33827a084182). Lint job task-floatclip_lint-781f539504ae4d7e8a3b succeeded and produced Artifact artifact-e907432f6e764a4c89e6332f3ddedc7e. Created annotated local tag v0.4.0 on effe2409b99cc124801ed5a5b2269a797c9cf380. Refreshed README for the 0.4.0 feature set, device acceptance checklist and GitHub CI/release workflow. GitHub connector inspection confirms the connected account currently has no FloatClip repository, so remote creation is the only remaining GitHub handoff prerequisite.

Files:
- `README.md`
- `.github/workflows/android.yml`

## 2026-09-09T09:05:00.958690Z — RELEASE: Provision GitHub remote and repo-scoped deploy authentication

The FloatClip GitHub repository now exists at wmy7512/FloatClip. The controlled project was updated to allow non-force pushes only to main. Origin is configured as ssh://git@ssh.github.com:443/wmy7512/FloatClip.git because outbound TCP/22 is blocked from the build sandbox while ssh.github.com:443 is reachable. A dedicated ED25519 deploy key was generated under the Git-ignored .mcp/github/ directory. The first remote read correctly reaches GitHub but is denied until the public deploy key is granted write access. Local main and annotated tag v0.4.0 remain ready for publication.

Files:
- `README.md`
- `.git/config`

## 2026-09-09T09:25:57.622068Z — RELEASE: Publish FloatClip 0.4.0 to GitHub with stable update signature

Published main to SteveBrilien/FloatClip and pushed annotated tag v0.4.0. GitHub Actions run 34333602665 completed build and release successfully. A signing audit then detected the GitHub runner's ephemeral debug certificate did not match the stable FloatClip update certificate, so release repair commit 45426765dbbe0d601c988d90d6c234f0dd1344bc added the preverified stable-signature APK and a fail-closed release path. Repair workflow run 34334404446 completed build and sync_release successfully, replacing the public v0.4.0 asset in place. The final GitHub asset was downloaded and verified at 2,582,629 bytes with SHA-256 4aa2e1dfbaedc02ee9d2ca63328d128b8235d6861735869d151c33827a084182, matching local Artifact artifact-07ac59e239e046acb3703212562b29da. Signing certificate SHA-256 is 9e476b6a60e08a14ec0d563ee1dde772145c70ea0463ce227b9b6e9e50275056.

Files:
- `README.md`
- `docs/STATUS.md`
- `.github/workflows/android.yml`
- `dist/FloatClip-0.4.0-debug.apk`
- `dist/FloatClip-0.4.0-debug.apk.sha256`
- `dist/release.env`

## 2026-09-09T14:39:53.909938Z — RELEASE: Prepare FloatClip 0.5.0 with new persistent signing identity

At the user's direction, the clean 0.5.0 APK from source commit 13b3102290d5f788f99f89f649d6dfb49b31f650 was re-signed with a new persistent FloatClip release key stored outside the Git repository. Final asset: dist/FloatClip-0.5.0-debug.apk, SHA-256 6c1821d8ca2ce58b81005e832d37083e396c3b7ef0f9608b571797f3f3d5d71f, signing certificate SHA-256 89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3. apksigner verification reports one signer using APK Signature Scheme v3. This signer intentionally differs from 0.4.0, so the 0.4.0 package must be uninstalled before the first 0.5.0 install; subsequent releases using this identity can update normally.

Files:
- `dist/FloatClip-0.5.0-debug.apk`
- `dist/FloatClip-0.5.0-debug.apk.sha256`
- `dist/release.env`
- `dist/README.md`
- `docs/STATUS.md`

## 2026-09-09T14:49:43.523898Z — RELEASE: Publish and verify FloatClip 0.5.0 on GitHub

Tag v0.5.0 was pushed at release commit f9e1289e5b8e72872f001cfe5150e54926f8c966. GitHub Actions main run 34365181024 and tag/release run 34365223029 both completed successfully. The public GitHub Release asset FloatClip-0.5.0-debug.apk was downloaded back and independently verified at 2,676,869 bytes, SHA-256 6c1821d8ca2ce58b81005e832d37083e396c3b7ef0f9608b571797f3f3d5d71f, signing certificate SHA-256 89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3. This exactly matches the locally prepared distribution asset.

Files:
- `docs/STATUS.md`
- `CHANGELOG.md`

## 2026-09-10T16:31:26.620710Z — RELEASE: Stage stable-signed FloatClip 0.5.1 release asset

Clean source commit a3a9db0e02a8c767c8f0c39f4a70ab7cfc8874e2 passed debug build task-floatclip_debug-92a16e7d87a64586bc00 and lint task-floatclip_lint-b0fadcefa29c4b3d9242 with No issues found. Prepared dist/FloatClip-0.5.1-debug.apk (2,689,079 bytes, SHA-256 a5bd45526903a15ebec12833fee68b49c0e6383af9e6659d1244057b11e8da58), verified package com.floatclip.app versionCode 6/versionName 0.5.1/minSdk 28/targetSdk 30, zip alignment, APK Signature Scheme v3 with one signer, and reuse of the 0.5.0 persistent signing certificate SHA-256 89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3. This preserves direct overwrite upgrade from 0.5.0.

Files:
- `dist/FloatClip-0.5.1-debug.apk`
- `dist/FloatClip-0.5.1-debug.apk.sha256`
- `dist/release.env`
- `dist/README.md`
- `docs/STATUS.md`

## 2026-09-10T16:37:22.484920Z — RELEASE: Publish and verify FloatClip 0.5.1 on GitHub

Published annotated tag v0.5.1 at release staging commit c3b21bc437c017df0aadf93aecb412e47ec889a2. GitHub main workflow run 34502691296 completed build successfully; tag workflow run 34502710334 completed build and release successfully. Public asset FloatClip-0.5.1-debug.apk is 2,689,079 bytes with SHA-256 a5bd45526903a15ebec12833fee68b49c0e6383af9e6659d1244057b11e8da58 and signing certificate SHA-256 89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3. The public APK was downloaded back and verified byte-identical to the local preverified asset; package metadata is com.floatclip.app versionCode 6/versionName 0.5.1. This signer matches 0.5.0, so direct overwrite upgrade is preserved.

Files:
- `docs/STATUS.md`
- `CHANGELOG.md`
- `dist/README.md`
- `dist/release.env`
- `dist/FloatClip-0.5.1-debug.apk`
- `dist/FloatClip-0.5.1-debug.apk.sha256`

## 2026-09-10T17:19:45.352278Z — UPDATE: Prepare FloatClip 0.5.2 interaction and background-runtime refinement

Raised source version to 0.5.2 (versionCode 7). Replaced the delayed close-time bubble insertion with one pre-created hidden destination bubble driven by the same 420 ms transition as the panel, removed double-tap pinning, added a pressure-independent 360 ms long press with haptic confirmation, replaced the legacy PopupMenu with an in-panel palette-aware action sheet, and replaced category up/down controls with drag-handle ordering. Added a dual runtime strategy: when the existing one-key-paste AccessibilityService is enabled it binds and hosts the overlay runtime so FloatClip can leave foreground-service state and remove its own FGS notification; without Accessibility the standard foreground-service path remains as fallback. Explicit Force stop remains a hard stop. Initial implementation debug job task-floatclip_debug-9fb2a8a9e8de4dae9477 and lint job task-floatclip_lint-3d67628a4cab459b8a80 both succeeded; lint reported No Issues Found. Prior-art review and acceptance checks are recorded in docs/INTERACTION_0.5.2.md.

Files:
- `app/build.gradle.kts`
- `app/src/main/java/com/floatclip/app/MainActivity.kt`
- `app/src/main/java/com/floatclip/app/accessibility/PasteAccessibilityService.kt`
- `app/src/main/java/com/floatclip/app/overlay/ClipboardOverlayService.kt`
- `app/src/main/java/com/floatclip/app/overlay/OverlayKeepAliveReceiver.kt`
- `app/src/main/java/com/floatclip/app/overlay/ui/SwipeRevealRow.kt`
- `app/src/main/java/com/floatclip/app/prefs/CategoryStore.kt`
- `app/src/main/res/values/strings.xml`
- `docs/INTERACTION_0.5.2.md`
- `docs/STATUS.md`
- `README.md`

## 2026-09-11 — RELEASE: Stage stable-signed FloatClip 0.5.2 release asset

Final implementation source commit `892e9cad6c769d36e7c641ef978964b5f42b9b65` passed clean debug job `task-floatclip_debug-9e8d0f17963f490d8cd9` and clean lint job `task-floatclip_lint-befd3b29ee5e4b0faa96`; lint reports `No issues found`. Prepared `dist/FloatClip-0.5.2-debug.apk` at 2,709,559 bytes with SHA-256 `19b529c9a3da9cff45a4334646e1d1bffc75cdf6043427350f8ad2f0a16a8d45`. Verified package `com.floatclip.app` versionCode 7/versionName 0.5.2/minSdk 28/targetSdk 30, zip alignment, APK Signature Scheme v3 with one signer, and exact reuse of the 0.5.0/0.5.1 persistent signing certificate SHA-256 `89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3`. This preserves direct overwrite upgrade from 0.5.1.

Files:
- `dist/FloatClip-0.5.2-debug.apk`
- `dist/FloatClip-0.5.2-debug.apk.sha256`
- `dist/release.env`
- `dist/README.md`
- `docs/STATUS.md`
- `README.md`

## 2026-09-11 — RELEASE: Publish and verify FloatClip 0.5.2 on GitHub

Published annotated tag `v0.5.2` at release staging commit `8dead2250308b524c3777e69fc18eb7ee66a7b5e`. GitHub main workflow run `34510367812` and tag/release workflow run `34510391315` both completed successfully. The public release asset `FloatClip-0.5.2-debug.apk` is 2,709,559 bytes with SHA-256 `19b529c9a3da9cff45a4334646e1d1bffc75cdf6043427350f8ad2f0a16a8d45` and signing certificate SHA-256 `89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3`. The public APK was downloaded back and verified byte-identical to the locally staged release asset; package metadata is `com.floatclip.app` versionCode 7/versionName 0.5.2. The signer matches 0.5.0/0.5.1, preserving direct overwrite upgrades.

Files:
- `README.md`
- `docs/STATUS.md`
- `CHANGELOG.md`
- `dist/README.md`
