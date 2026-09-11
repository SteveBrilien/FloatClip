# FloatClip development status

Last updated: 2026-09-11

## 0.5.4 retained-window candidate

User feedback reports 0.5.3 still jitters. See docs/INTERACTION_0.5.4.md.
This revision removes panel open/close transforms and keeps the panel/catcher surfaces attached but transparent and non-touchable while closed.
Long-press actions are one vertical column; category selection is a separate vertical list. App navigation removes duplicate status/help blocks and unused sync UI.
Version 0.5.4 / code 9. Manual visual and touch-through acceptance remains pending.
- Clean source: `505ce76749e48e2bb2358700b9efb00783e48305`.
- Lint: `task-floatclip_lint-db176bbeb3404afab000`, succeeded, No issues found.
- Debug: `task-floatclip_debug-27c81a18cdc244eeae3b`, succeeded, clean source recorded.
- APK: `dist/FloatClip-0.5.4-debug.apk`, 2,709,559 bytes; SHA-256 `ed2d0ff970076d0cf438597a631042aaf641a1940b268862e8f6690fbdef9288`.
- Persistent signer unchanged: `89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3`.
- Signature v3, alignment and package version checked.
- Published `v0.5.4` targets `44e7b2fcb73154816a69f22fcc962974d9141ded`; release workflow `34548202164` succeeded.
- Public APK was downloaded again and verified byte-identical, with the same signer and versionCode 9 / versionName 0.5.4.
- Download: https://github.com/SteveBrilien/FloatClip/releases/download/v0.5.4/FloatClip-0.5.4-debug.apk
- Manual device acceptance remains pending; no on-device jitter or touch-through pass is claimed.

## 0.5.3 interaction repair candidate

Source version is 0.5.3 / code 8. See docs/INTERACTION_0.5.3.md for confirmed source defects, behaviour contracts and the manual device checklist.
The 0.5.2 device feedback supersedes its earlier successful compile/lint assessment: pin hit-through, stuck swipe and category drag defects required a further repair.
This pass restores single-copy/double-pin/long-options and uses a persistent bubble surface with guarded dismissal.
Manual OriginOS visual acceptance is still pending.
- Clean source: `41e1a05990eac23bfc26463d3c442aa9af0641de`.
- Lint: `task-floatclip_lint-593e3ba7f59b420db8a2`, succeeded, No issues found.
- Debug: `task-floatclip_debug-fedcc62da0ec4f3db689`, succeeded, clean source recorded.
- APK: `dist/FloatClip-0.5.3-debug.apk`, 2,725,943 bytes; SHA-256 `3e84643eca4e630c18e0f6e5245859890174bf3921a0c1f204cd8d04eb6cd758`.
- Persistent signer unchanged: `89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3`.
- APK signature v3 and alignment verified.
- Published tag `v0.5.3` targets staging commit `7f171322efd3703c237ccf329dc0dd3198b46d53`; GitHub release workflow `34514844972` succeeded.
- Public APK was downloaded again and matched the local SHA-256, signer, versionCode 8 and versionName 0.5.3.
- Download: https://github.com/SteveBrilien/FloatClip/releases/download/v0.5.3/FloatClip-0.5.3-debug.apk
- Device acceptance remains pending; no ADB installation or visual verification is claimed.

## 0.5.2 gesture / close-transition / background-runtime candidate

0.5.2 addresses the September 11 device feedback on 0.5.1. The current implementation removes double-tap pinning, installs a pressure-independent 360 ms long press with haptic feedback, replaces the legacy PopupMenu with a palette-aware in-panel action sheet, changes custom-category ordering to drag handles, and rewrites panel dismissal so the destination bubble surface exists before the visible close animation begins.

The runtime model is also split. With the existing one-key-paste AccessibilityService enabled, the system-managed accessibility component binds and hosts the overlay runtime, allowing FloatClip to leave foreground-service state and remove its own persistent foreground-service notification. Without Accessibility, FloatClip keeps the normal foreground-service fallback and its Android-required notification. Explicit Force stop remains an intentional hard stop and cannot be self-bypassed by an ordinary package. OriginOS autostart/background-power policy remains an OEM best-effort constraint.

Prior art and the resulting design decisions are documented in `docs/INTERACTION_0.5.2.md`. The review included Android foreground/bound-service and AccessibilityService lifecycle documentation, common SYSTEM_ALERT_WINDOW + foreground-service overlay projects, accessibility-overlay projects, modern clipboard managers using IME/Accessibility/Shizuku combinations, and vivo vendor/system push. Vendor push explains how messaging notifications can arrive with an app process offline; it does not keep arbitrary third-party floating UI continuously resident.

Initial implementation gates before the final source-version/document pass:

- debug job `task-floatclip_debug-9fb2a8a9e8de4dae9477` — **BUILD SUCCESSFUL** — Artifact `artifact-23dcb381df344e1eada94348f170d4f3`;
- lint job `task-floatclip_lint-3d67628a4cab459b8a80` — **BUILD SUCCESSFUL**, `No Issues Found` — Artifact `artifact-b38da955a272489e80105555e4de51c3`.

The source line is now `versionName=0.5.2` / `versionCode=7`. The clean source and stable-signing gates are complete:

- final implementation source commit `892e9cad6c769d36e7c641ef978964b5f42b9b65` is clean;
- clean debug job `task-floatclip_debug-9e8d0f17963f490d8cd9` — **BUILD SUCCESSFUL** — Artifact `artifact-d780796b50254631b979c889c7a3245b`;
- clean lint job `task-floatclip_lint-befd3b29ee5e4b0faa96` — **BUILD SUCCESSFUL**, `No issues found` — Artifact `artifact-bcf88d702c8d4a178b451dbf1b71a5da`;
- installable asset `dist/FloatClip-0.5.2-debug.apk` is `2,709,559` bytes with SHA-256 `19b529c9a3da9cff45a4334646e1d1bffc75cdf6043427350f8ad2f0a16a8d45`;
- package metadata is `com.floatclip.app`, `versionCode=7`, `versionName=0.5.2`, `minSdk=28`, `targetSdk=30`;
- release signing certificate SHA-256 is `89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3`, reusing the 0.5.0/0.5.1 persistent identity;
- `apksigner` verifies APK Signature Scheme v3 with one signer and `zipalign -c` passes.

Because the signer is unchanged, 0.5.2 can overwrite-install 0.5.1 normally. Publication is complete and independently verified:

- release staging commit / annotated tag target: `8dead2250308b524c3777e69fc18eb7ee66a7b5e` / `v0.5.2`;
- GitHub main workflow run `34510367812`: **success**;
- GitHub tag/release workflow run `34510391315`: **success**;
- public GitHub Release asset size: `2,709,559` bytes;
- public asset SHA-256: `19b529c9a3da9cff45a4334646e1d1bffc75cdf6043427350f8ad2f0a16a8d45`;
- public asset signing certificate SHA-256: `89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3`;
- the public APK was downloaded back, verified as `versionCode=7` / `versionName=0.5.2`, and confirmed byte-identical to the local preverified release asset.

Download: `https://github.com/SteveBrilien/FloatClip/releases/download/v0.5.2/FloatClip-0.5.2-debug.apk`.

## 0.5.1 device-feedback refinement

0.5.1 addresses the September 9 late device screenshots/feedback without changing the encrypted-vault or sync security model. The implementation removes the visible outside dim layer, slows/softens panel dismissal, centers the title and removes the redundant chevron, persists the bubble's newest logical dock before expansion, adds adjustable motion sensitivity, adds best-effort service recovery, strengthens system-theme synchronization, and makes category order user-controlled.

Implementation details and the nine-point device acceptance checklist are in `docs/INTERACTION_0.5.1.md`. The keep-alive path is deliberately best-effort: Android `START_STICKY`, task-removal scheduling, boot recovery and package-replacement recovery are implemented, but an OriginOS force-stop/autostart denial cannot be bypassed by a normal application.

Release staging gates are complete:

- final source commit `a3a9db0e02a8c767c8f0c39f4a70ab7cfc8874e2` is clean;
- clean debug job `task-floatclip_debug-92a16e7d87a64586bc00` — **BUILD SUCCESSFUL** — Artifact `artifact-e3a49425c97c495187a8c3fd7dcdd611`;
- clean lint job `task-floatclip_lint-b0fadcefa29c4b3d9242` — **BUILD SUCCESSFUL**, `No issues found` — Artifact `artifact-2110d6cc714c446fac0ee18e62f7e9c9`;
- installable asset `dist/FloatClip-0.5.1-debug.apk` is `2,689,079` bytes with SHA-256 `a5bd45526903a15ebec12833fee68b49c0e6383af9e6659d1244057b11e8da58`;
- package metadata is `com.floatclip.app`, `versionCode=6`, `versionName=0.5.1`, `minSdk=28`, `targetSdk=30`;
- release signing certificate SHA-256 is `89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3`, exactly reusing the 0.5.0 persistent identity;
- `apksigner` verifies APK Signature Scheme v3 with one signer, and `zipalign -c` passes.

Because the 0.5.0 signer is preserved, 0.5.1 can overwrite-install 0.5.0 normally. Publication is complete and independently verified:

- release staging commit / annotated tag target: `c3b21bc437c017df0aadf93aecb412e47ec889a2` / `v0.5.1`;
- GitHub main workflow run `34502691296`: build **success**;
- GitHub tag workflow run `34502710334`: build **success**, release **success**;
- public GitHub Release asset size: `2,689,079` bytes;
- public asset SHA-256: `a5bd45526903a15ebec12833fee68b49c0e6383af9e6659d1244057b11e8da58`;
- public asset signing certificate SHA-256: `89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3`;
- the public APK was downloaded back and verified byte-identical to the local preverified release asset.

Download: `https://github.com/SteveBrilien/FloatClip/releases/download/v0.5.1/FloatClip-0.5.1-debug.apk`.

## 0.5.0 interaction/security release candidate

0.5.0 is the current source candidate. It incorporates the September 9 device-feedback pass: velocity-sensitive floating-ball physics, continuous panel transitions, in-place fixed-mode switching, border/header dragging, swipe/double-tap/long-press row gestures, functional category filtering/assignment, no overlay focus grab, encrypted local storage, portable encrypted backup, and a user-configured HTTPS sync endpoint placeholder.

Security/storage invariants are documented in `SECURITY.md`; interaction invariants and the device acceptance checklist are documented in `docs/INTERACTION_0.5.0.md`. Public tracked files are scanned for private deployment hostname patterns before release.

The 0.5.0 source gate is complete: clean source commit `13b3102290d5f788f99f89f649d6dfb49b31f650` passed both debug assembly and lint with no lint issues. The clean-build Artifact was `artifact-b5290d91ca4d4e34987099a129100aec`.

The installable 0.5.0 distribution has now been deliberately re-signed with a new persistent FloatClip release identity at the user's direction:

- asset: `dist/FloatClip-0.5.0-debug.apk`;
- size: `2,676,869` bytes;
- SHA-256: `6c1821d8ca2ce58b81005e832d37083e396c3b7ef0f9608b571797f3f3d5d71f`;
- signing certificate SHA-256: `89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3`;
- signature verification: APK Signature Scheme v3, one signer;
- package metadata: `com.floatclip.app`, `versionCode=5`, `versionName=0.5.0`, `minSdk=28`, `targetSdk=30`.

Because 0.5.0 intentionally changes the application signing certificate relative to 0.4.0, Android will require the previous package to be uninstalled before installing 0.5.0. Future releases signed with this new persistent identity can update 0.5.0 normally.

GitHub publication is complete and independently verified:

- release commit/tag target: `f9e1289e5b8e72872f001cfe5150e54926f8c966` / `v0.5.0`;
- main workflow run `34365181024`: **success**;
- tag/release workflow run `34365223029`: **success**;
- GitHub Release asset size: `2,676,869` bytes;
- the public asset was downloaded back from GitHub and matched local SHA-256 `6c1821d8ca2ce58b81005e832d37083e396c3b7ef0f9608b571797f3f3d5d71f` and signer SHA-256 `89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3` exactly.

## 0.4.0 GitHub release published

FloatClip 0.4.0 is now published from the public repository `SteveBrilien/FloatClip`. The release path was verified end-to-end after correcting an important signing issue in the first GitHub-hosted asset.

- `main` remote head after release handoff: `45426765dbbe0d601c988d90d6c234f0dd1344bc`;
- annotated tag `v0.4.0` resolves to source commit `effe2409b99cc124801ed5a5b2269a797c9cf380`;
- initial tag workflow run `34333602665`: build **success**, release **success**;
- stable-signature repair workflow run `34334404446`: build **success**, `sync_release` **success**;
- final GitHub Release asset: `FloatClip-0.4.0-debug.apk`;
- size: `2,582,629` bytes;
- SHA-256: `4aa2e1dfbaedc02ee9d2ca63328d128b8235d6861735869d151c33827a084182`;
- signing certificate SHA-256: `9e476b6a60e08a14ec0d563ee1dde772145c70ea0463ce227b9b6e9e50275056`;
- download: `https://github.com/SteveBrilien/FloatClip/releases/download/v0.4.0/FloatClip-0.4.0-debug.apk`.

The first GitHub Actions APK used the runner's ephemeral default debug key and therefore could not safely overwrite the migrated FloatClip installation. That asset was replaced in-place by the byte-identical stable-signature Orange Pi Artifact (`artifact-07ac59e239e046acb3703212562b29da`). The final public release SHA-256 was downloaded and rechecked after replacement and matches the registered local Artifact exactly.

GitHub CI continues to build/lint independently, but release publishing now consumes only the preverified stable-signature artifact from `dist/` until protected signing credentials are provisioned for Actions. MCP's localhost-only Artifact server is no longer a delivery blocker.

## 0.4.0 adaptive theme / native interaction candidate

FloatClip is now at `versionName=0.4.0` / `versionCode=4`. This iteration directly addresses the light-theme contrast and floating-interaction issues reported from the 0.3.0 device screenshots:

- app and overlay appearance support `SYSTEM`, `LIGHT`, and `DARK` modes;
- foreground colors are derived from the effective surface luminance, so a light OriginOS semantic surface cannot be paired with white clipboard text;
- the app is split into three pages (`状态`, `剪贴板`, `设置`) with all appearance controls on the final Settings page;
- floating-ball opacity is independently configurable (35–100%, default 76%);
- edge half-hide depth is configurable (0–55%, default 38%);
- bubble edge snap is now distance-aware and eased instead of immediate, with touch/landing scale feedback;
- after settling, the bubble waits 900 ms and smoothly half-hides toward the current screen edge;
- expanded-panel open/close transitions use short fade/scale animations;
- normal-mode blank-area dismissal now uses a dedicated `DismissScrimLayout` touch dispatcher instead of relying on a parent click listener, eliminating the child-touch-consumption failure mode while still preventing touch-through;
- fixed mode remains non-modal for repeated paste;
- appearance changes send a scoped refresh broadcast to the running overlay service;
- delayed clipboard capture is guarded by a generation token so panel rebuild/collapse cannot leave a stale clipboard callback behind.

Pre-commit gates for 0.4.0:

- debug job `task-floatclip_debug-00bbe66b99254d1794a3` — **BUILD SUCCESSFUL**;
- APK Artifact `artifact-a00d77ca1a674edd9b951e47b6e5042e`;
- APK size `2,582,629` bytes;
- APK SHA-256 `9fd3613d493294efcb76f82ab13c5ddb91959b94c0294353d675891785484959`;
- lint job `task-floatclip_lint-f743f8dcb8724625ab41` — **BUILD SUCCESSFUL**;
- lint Artifact `artifact-c16f2b10f7664e5aa9ea16978c7df168`;
- lint result: 5 warnings, 0 errors.
- clean distribution source commit `d9d20d531bd84006160014b41e3bad0f7eea6835`;
- clean distribution debug job `task-floatclip_debug-cc9d040e57ba41d49a7c` — **BUILD SUCCESSFUL**;
- clean distribution Artifact `artifact-e2f31f436a744c7f8ca071e9c2d10f58`;
- clean distribution APK size `2,582,629` bytes; SHA-256 `ae8ba13289d1880b6b4fff46b8dc8ab9ce41831d2b6382b4b94fc6e4508f9ddf`.

Artifact publication is currently unavailable from this connector because it is attached to an MCP secondary runtime (`ancillary_listeners_enabled=false`) and the configured artifact server is not running/owned by this runtime. The build Artifact remains registered and can be published once the primary artifact listener is reachable.

The remaining acceptance work is device-side feel/interaction validation after a manual overwrite install: confirm light/dark contrast, snap timing, half-hidden resting position, normal-mode outside dismissal, fixed-mode interaction and automatic clipboard synchronization. A clean-commit distribution rebuild is performed before publishing the APK.


## 0.3.0 native-style UI iteration

This iteration upgrades FloatClip to `versionName=0.3.0` / `versionCode=3` and moves the visual/interaction system closer to an OriginOS-native utility:

- launcher icon changed to a white background with a flat black copy/paste glyph;
- floating bubble changed to a true circular, semi-transparent white control with a black copy/paste icon;
- overlay actions use a consistent monochrome Fluent-like vector icon set for drag, pin/fixed mode, collapse and delete;
- expanded panel opacity is configurable from 45% to 100%;
- panel width is configurable from 280dp to 420dp;
- panel height is configurable from 300dp to 620dp;
- floating bubble size is configurable from 42dp to 64dp;
- expanded panel can be dragged freely by its header and persists its x/y position;
- normal mode keeps the full-screen scrim so outside taps collapse without touch-through;
- fixed mode remains panel-only / non-modal for repeated paste workflows;
- MainActivity uses flatter outlined cards and exposes the appearance controls directly in-app;
- automatic system-clipboard synchronization remains active when the panel opens.

Pre-commit validation for this iteration:

- debug job `task-floatclip_debug-ec6d3df36d6f4afdb7d2` — **BUILD SUCCESSFUL** — Artifact `artifact-3eae05648825475c9cc11a1ae321b2a0`;
- lint job `task-floatclip_lint-47ed77f737ef48b494f3` — **BUILD SUCCESSFUL** — Artifact `artifact-3e654c73500746979df0d232d7fbf161`.

The release artifact must be rebuilt once more from the clean commit created after these changes.

## Current phase

FloatClip is now in the **manual-device acceptance candidate** stage. The standalone overlay, ROM-locked OriginOS bridge, automatic clipboard synchronization, UI refresh, signature migration, debug build and lint gates are complete. The remaining device work is to install the latest candidate on the vivo again and re-run the final interaction checks for the new scrim/fixed-mode behavior.

The user has chosen the normal delivery flow going forward: publish the APK and let the user perform the overwrite install manually; automated ADB installation is no longer a release requirement for every iteration.

## Target device

- vivo PD2115 / V2115A
- Android 11
- OriginOS Ocean
- Build fingerprint: `vivo/PD2115/PD2115:11/RP1A.200720.012/compiler1018205834:user/release-keys`
- Previously observed USB serial: `34472930300027K`
- Previous laboratory wireless address: `<target-device-address>:5555`

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
