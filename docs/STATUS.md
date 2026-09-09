# FloatClip development status

Last updated: 2026-09-09

## Current phase

FloatClip is now in the **manual-device acceptance candidate** stage. The standalone overlay, ROM-locked OriginOS bridge, automatic clipboard synchronization, UI refresh, signature migration, debug build and lint gates are complete. The remaining device work is to install the latest candidate on the vivo again and re-run the final interaction checks for the new scrim/fixed-mode behavior.

The user has chosen the normal delivery flow going forward: publish the APK and let the user perform the overwrite install manually; automated ADB installation is no longer a release requirement for every iteration.

## Target device

- vivo PD2115 / V2115A
- Android 11
- OriginOS Ocean
- Build fingerprint: `vivo/PD2115/PD2115:11/RP1A.200720.012/compiler1018205834:user/release-keys`
- Previously observed USB serial: `34472930300027K`
- Previous laboratory wireless address: `192.168.3.44:5555`

ROM-specific adapters are allowed only behind strict fail-closed locks.

## Current implemented behavior

- `TYPE_APPLICATION_OVERLAY` floating clipboard bubble.
- 48dp compact floating bubble with persisted edge/vertical position.
- Dragging and left/right edge snapping.
- Compact expanded clipboard panel.
- Text history, search, pin/unpin, delete, categories and category cycling.
- Accessibility-based one-tap paste via `ACTION_PASTE`.
- Fixed-open mode for repeated paste operations.
- Foreground overlay service.
- OriginOS semantic-resource palette behind ROM lock.
- Main application redesigned from a developer/test panel into a compact card-style settings/status page.
- Opening the floating panel automatically reads the current Android system clipboard; the old large manual "read clipboard" control is no longer part of the normal interaction path.
- Clipboard import is deduplicated: if the current clipboard already exists, its timestamp is refreshed rather than creating a duplicate row.

## Automatic clipboard synchronization — device validated

The first UI/runtime candidate was installed on the target vivo and tested with the existing user history preserved.

Device-side validation showed:

- history count before panel expansion: 3;
- history count after automatic synchronization: 3;
- IDs/text set/categories/pin state unchanged;
- exactly one existing item's `createdAt` changed.

This verifies the intended path:

`open bubble -> expand panel -> read current system clipboard -> deduplicate -> refresh matching history entry`

No clipboard text was emitted into MCP/chat diagnostics during this validation.

## Outside-touch / fixed-mode interaction

The first runtime candidate exposed an OriginOS interaction issue: the old `FLAG_NOT_TOUCH_MODAL + WATCH_OUTSIDE_TOUCH` approach could allow an outside tap to pass through to the app underneath.

The latest candidate changes the model to:

- **normal mode:** a transparent full-screen scrim owns the outside area, so an outside tap collapses FloatClip without being delivered to the underlying app;
- **fixed mode:** the panel remains panel-only / non-modal so the user can continue interacting with the underlying app while repeatedly pasting.

This second interaction revision has passed debug and lint. Final on-device revalidation is pending the user's manual overwrite install / next available ADB session.

## OriginOS Bridge

`app/src/main/java/com/floatclip/app/integration/OriginOsRomBridge.kt`

`OriginOsRomLock` activates only when all of the following match:

1. the full supported `Build.FINGERPRINT`;
2. `com.vivo.floatingball` version `2.5.32.0` / code `253200`;
3. FloatingBall APK SHA-256 `e24c914cc6e74f01922cd89385b2168226e5c425a5b212543ff4800ffdd0989e`.

The current bridge reads semantic package resources only. It does not invoke vivo private AIDL or privileged controller APIs. Any lock mismatch disables the bridge and preserves standalone behavior.

Runtime log tag: `FloatClipOriginOS`.

## vivo input-method clipboard investigation

The target default input method was identified as:

`com.vivo.ai.ime/.main.IMEService`

It is a platform privileged app. Package/component inspection did not reveal a public clipboard/history Provider or Service suitable for a normal APK. Therefore FloatClip currently synchronizes the Android system clipboard, not the vivo IME's private historical clipboard database.

A future full-history integration would require a separate ROM-specific privileged bridge, most plausibly Shizuku/LSPosed-side, and must remain optional/fail-closed.

## Signature migration and user-data preservation

The earlier installed FloatClip and the newer build used different debug signing certificates, causing `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

A one-time migration was completed safely:

1. the old FloatClip SharedPreferences were backed up privately on the Orange Pi;
2. the old signature package was removed;
3. the newer package was installed and launched;
4. `floatclip_store.xml` and `floatclip_overlay.xml` were restored through the controlled host path;
5. device-side SHA verification matched the backups;
6. FloatClip AccessibilityService and overlay permission were restored.

The restored history remained intact after launching the new app. The private migration backup is under the Git-ignored `analysis/` tree and is not committed.

Future builds can overwrite this migrated installation normally because the signing identity is now stable.

## Latest build gates

Latest second-interaction candidate:

### Debug

- Job: `task-floatclip_debug-6b8a334bafb243eaa7fd`
- Result: **BUILD SUCCESSFUL**
- Artifact: `artifact-a17025eff83d4f949defea6f866158ce`
- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Size: `2,524,512` bytes
- SHA-256: `262a6a0a1c2a00b1262b0138c73814f1b4526867ad8453407d08555da23865c2`

### Lint

- Job: `task-floatclip_lint-9d7b411d3b304cfca304`
- Result: **BUILD SUCCESSFUL**
- Artifact: `artifact-2919a3f89c2246258c556a76f98d0559`

### Distribution rebuild from clean commit

- Source commit: `1fd7b7f0c307de655af4bf31e9f5591e1ebbaeec`
- Job: `task-floatclip_debug-262b23f6b47a41ae82cb` — **BUILD SUCCESSFUL**
- Artifact: `artifact-1b00d27060b54df0ba4bebcc9473ca19`
- Size: `2,524,512` bytes
- SHA-256: `9272d41f07aebc0f41b535bb79026ebdcf4ddde8beb5acd59d727ce2d0f8fc54`

The only known lint warnings are non-blocking warnings associated with deliberate ROM semantic-resource lookup / platform compatibility code.

## MCP infrastructure notes

Earlier HostCapability mount-point issues are tracked in the MCP project's issue ledger.

A new defect discovered during FloatClip device testing is tracked as:

`MCP-20260909-034520-adb-sandbox-server-hijack`

Summary: a Bubblewrap TaskProfile that launches an adb client/server can bind the shared host `127.0.0.1:5037`; because the sandbox does not own the host USB device nodes, this can replace a working host adb server with one that reports no USB devices. FloatClip's temporary sandbox ADB TaskProfiles were removed. Device install/launch operations must use structured host ADB capabilities rather than starting adb inside a generic sandbox task.

The `adb` HostCapability was subsequently narrowed back to fixed FloatClip install/launch/permission actions; one-time migration actions were removed.

## Current external blocker

At the end of this pass, `adb_devices` is empty and the vivo is not currently enumerated through USB. This does not block APK delivery because the user will perform the overwrite installation manually.

## Next actions after manual install

1. Open FloatClip and start the floating clipboard.
2. Verify normal mode: expand the panel and tap the transparent outside area; FloatClip must collapse without activating the app underneath.
3. Verify fixed mode: enable fixed mode and confirm the underlying app remains interactive while the FloatClip panel stays available for repeated paste operations.
4. Confirm opening the panel still auto-synchronizes the current system clipboard.
5. Check `FloatClipOriginOS` diagnostics and confirm the exact supported ROM remains `MATCHED`.
6. If the above pass, mark this UI iteration device-accepted; only rebuild for defects found in that test.

## Acceptance definition for this UI iteration

Completed:

- debug build passes;
- lint passes without blocking errors;
- signature migration completed without losing existing FloatClip history;
- MainActivity launches on the target vivo;
- overlay permission and AccessibilityService restored;
- floating service/window confirmed on device;
- automatic system-clipboard synchronization confirmed on device;
- second-generation scrim/fixed-mode implementation builds and lints cleanly.

Pending:

- final device confirmation that normal-mode scrim collapse does not pass the touch to the app underneath;
- final device confirmation that fixed mode preserves intended underlying-app interaction.
