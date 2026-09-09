# Changelog

All notable FloatClip development changes are recorded here.

## 2026-09-09

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
- Explicit connection to the laboratory address `192.168.3.44:5555` returns `No route to host` because the target vivo is no longer on a network routable from the Orange Pi.
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
- install the APK to `192.168.3.44:5555`;
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
