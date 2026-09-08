# FloatClip development status

Last updated: 2026-09-09

## Current phase

FloatClip has moved beyond the initial feasibility prototype and now has a working standalone architecture plus the first ROM-specific OriginOS integration layer. OriginOS/FloatingBall static analysis, ROM-lock implementation, Android 11 package visibility, debug build and lint have all completed successfully. The project is now at the **device-runtime acceptance** stage; automatic installation/launch is blocked only because the target vivo is no longer reachable from the Orange Pi network.

## Target device

- Device family: vivo PD2115
- Android: 11
- ROM family: OriginOS Ocean
- Expected ADB serial/address: `192.168.3.44:5555`
- Supported build fingerprint:
  - `vivo/PD2115/PD2115:11/RP1A.200720.012/compiler1018205834:user/release-keys`

The user expects this ROM to remain effectively fixed, therefore ROM-locked adapters are acceptable as long as they fail closed.

## Implemented standalone functionality

- `TYPE_APPLICATION_OVERLAY` floating bubble.
- Dragging and left/right edge snapping.
- Persisted bubble side and vertical position.
- Expand/collapse clipboard panel.
- Outside-touch collapse.
- Fixed-open mode for repeated multi-paste.
- Text history storage.
- Pin/unpin.
- Delete.
- Search.
- Custom categories.
- Category cycling from the overlay.
- Accessibility-based one-tap paste through `ACTION_PASTE`.
- Explicit current-clipboard import path for Android 11/OEM validation.
- Foreground overlay service.
- Adaptive standalone light/dark palette.

## OriginOS analysis completed

Read-only snapshots and static analysis established that the native OriginOS floating ball is owned by `com.vivo.floatingball`, running as a system component rather than a normal application overlay.

Observed native windows/surfaces include:

- `FloatingBallIdleView`
- `FloatingBallDragView`
- `FloatingBallEdgeView`
- `FloatingBallExpandedView`

Analysed package identity:

- package: `com.vivo.floatingball`
- versionName: `2.5.32.0`
- versionCode: `253200`
- APK SHA-256: `e24c914cc6e74f01922cd89385b2168226e5c425a5b212543ff4800ffdd0989e`

The APK contains OEM/private interfaces such as `IFloatingBallService`, `IFloatingBallServiceForSettings`, and `IUpSlideServiceForFloatingBall`, and requests signature/system permissions including `INTERNAL_SYSTEM_WINDOW`, `WRITE_SECURE_SETTINGS`, and `INJECT_EVENTS`.

### Consequence

A normal FloatClip APK must not attempt to impersonate the OEM floating-ball service or depend on those private privileged APIs. Doing so would be fragile and could destabilize the system UI path. Native controller reuse, if pursued later, belongs in an explicitly privileged/root/LSPosed-side adapter.

## OriginOS Bridge implemented

File:

`app/src/main/java/com/floatclip/app/integration/OriginOsRomBridge.kt`

### ROM lock

`OriginOsRomLock` activates only when all of the following match the analysed specimen:

1. full `Build.FINGERPRINT`;
2. FloatingBall version name/code;
3. SHA-256 of the installed FloatingBall APK.

Possible diagnostic states include:

- `MATCHED`
- `FINGERPRINT_MISMATCH`
- `PACKAGE_MISSING`
- `VERSION_MISMATCH`
- `APK_PATH_UNAVAILABLE`
- `APK_HASH_MISMATCH`
- `HASH_READ_FAILED`

Any state other than `MATCHED` disables the ROM bridge and leaves FloatClip in standalone mode.

### Semantic-resource bridge

`LockedOriginOsSystemBridge` currently does **not** call private vivo AIDL. After the ROM lock matches, it attempts to read semantic resources from the installed `com.vivo.floatingball` package, such as:

- floating-ball background color;
- panel/list background color;
- expanded-function label color;
- expanded-outline corner dimension.

These values are mapped to `OriginOsThemeSnapshot` and then consumed by `AdaptiveOverlayThemeProvider`.

This is intentionally a low-risk first integration level: the system package still owns its resources, no OEM artwork is redistributed, and FloatClip falls back automatically if lookup fails.

## Integration registry

`IntegrationRegistry` now initializes the locked OriginOS bridge from the application context. Both `MainActivity` and `ClipboardOverlayService` initialize the integration before using it.

The application UI exposes the current OriginOS Bridge / ROM-lock state for device-side debugging.

Runtime diagnostics use log tag:

`FloatClipOriginOS`

Expected successful path:

- ROM lock reports `MATCHED`;
- bridge reports semantic resource activation;
- overlay palette source changes from standalone to OriginOS-derived semantic resources.

## Build configuration

Registered MCP v2 TaskProfiles:

### `floatclip_debug`

- project: `floatclip`
- command: `scripts/build-orangepi.sh :app:assembleDebug`
- artifact: `app/build/outputs/apk/debug/app-debug.apk`
- JDK: 17
- Android SDK: project/host ARM64 toolchain

### `floatclip_lint`

- project: `floatclip`
- command: `scripts/build-orangepi.sh :app:lintDebug`
- report: `app/build/reports/lint-results-debug.html`

The TaskProfiles were successfully written using the MCP v2 artifact schema. Both profiles now use the project-local `.toolchains/jdk17` and `.toolchains/android-sdk` paths because MCP 2.1.0 currently has a durable-task HostCapability mount bug for `required_capabilities`.

## Build and lint acceptance

- Manifest package visibility for `com.vivo.floatingball` has been added through `<queries>`.
- Debug job: `task-floatclip_debug-b7b8f0e363a34d4c92af`.
- Debug result: **BUILD SUCCESSFUL**.
- APK artifact: `artifact-1a81ea498bf341c1985fa68d76f50463`.
- APK path: `app/build/outputs/apk/debug/app-debug.apk`.
- APK size: `2,512,988` bytes.
- APK SHA-256: `2fa88da2ea12dcaa81b15144a1ebdf9dce4a574a0253946ea6afc73d9ff9efdb`.
- `zipalign -c -v 4`: verification successful.
- `apksigner verify`: verification successful; debug signer, APK Signature Scheme v2 active.
- Lint job: `task-floatclip_lint-46957a67acb2455d9da2`.
- Lint result: **BUILD SUCCESSFUL**, 0 errors and 2 non-blocking `DiscouragedApi` warnings caused by intentional ROM semantic-resource lookup via `Resources.getIdentifier()`.

The MCP Artifact registry successfully published the APK, but MCP 2.1.0 currently exposes only a loopback Artifact URL (`127.0.0.1:18777`). A one-off externally reachable download copy was therefore created for user delivery; temporary URLs are intentionally not persisted here because they expire.

## MCP infrastructure findings

During this build, a durable task using `required_capabilities=["jdk17"]` failed before command execution with:

`bwrap: Can't mkdir /opt/codex-mcp: Read-only file system`

Two failing reproductions were captured before applying the project-local toolchain workaround:

- `task-floatclip_debug-79ac17f4202c454ebe32`
- `task-floatclip_debug-b9e141c8861c42dd85e8`

The defect is tracked in the MCP 2.1.1 issue ledger as `MCP-20260908-192700-hostcap-mountpoint`; FloatClip evidence has been appended to that existing issue rather than creating a duplicate. The external Artifact URL limitation is separately tracked as `MCP-20260908-192701-artifact-external-url`.

The workaround does not weaken Bubblewrap isolation: FloatClip uses its already-present workspace-local JDK and Android SDK and no longer requests the broken capability mount in these two build profiles.

## Current runtime blocker

The user is no longer on the laboratory LAN. Orange Pi ADB validation on 2026-09-09 produced an empty device list, and an explicit connection attempt to `192.168.3.44:5555` returned:

`No route to host`

Therefore APK installation, MainActivity launch and OriginOS runtime validation cannot be completed remotely until the vivo device and Orange Pi again have a routable ADB path. This is an external network/device availability condition, not a build or lint failure.

## Required next actions

Resume from this point; do **not** repeat static analysis, ROM-lock implementation, debug or lint unless code changes again.

1. Restore a routable ADB path to the target vivo device.
2. Confirm the device is authorized and visible.
3. Install/upgrade the already-built `app-debug.apk`.
4. Launch `com.floatclip.app/.MainActivity`.
5. Verify ROM-lock status on the main screen and inspect `FloatClipOriginOS` logs.
6. Validate overlay permission flow and start the floating clipboard.
7. Validate bubble render, drag, edge snap, expand/collapse, outside-touch collapse, fixed mode, pin/delete/category behavior, clipboard import and accessibility paste.
8. Inspect `dumpsys window`, process state and logcat for crashes or permission failures.
9. Record final device acceptance and only rebuild if runtime fixes are required.

## Acceptance definition

This milestone is complete only when:

- debug build succeeds;
- lint completes without blocking errors;
- APK installs on the target vivo device;
- MainActivity launches without crash;
- overlay can be started after permission is granted;
- floating bubble is visible and interactive;
- panel opens/closes correctly;
- fixed mode permits repeated paste operations;
- ROM lock either activates correctly on the exact supported build or fails closed without breaking the standalone UI;
- no repeated fatal exceptions are visible in runtime logs.
