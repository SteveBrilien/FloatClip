# Changelog

All notable FloatClip development changes are recorded here.

## 2026-09-09

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
