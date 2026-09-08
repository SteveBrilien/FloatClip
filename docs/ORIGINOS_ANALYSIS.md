# OriginOS Android 11 analysis and ROM-lock record

Last updated: 2026-09-08

Target: the user's fixed vivo Android 11 / OriginOS Ocean ROM with the current "未来科技" visual theme. Because this ROM is expected to remain stable, FloatClip permits version-locked adapters, but every ROM-specific path must fail closed and preserve the standalone renderer.

## 1. Safety policy

The completed analysis pass was read-only. It did not modify SystemUI, framework files, settings overlays, boot image, SELinux policy, or OEM APKs.

ROM-specific integration follows these rules:

- identify the exact ROM before enabling private/OEM-dependent behavior;
- prefer reading semantic resources already installed on the device instead of copying OEM assets;
- never make standalone FloatClip startup depend on the OriginOS bridge;
- keep database, image decoding and long I/O out of any future SystemUI-side hook;
- treat every private class/resource/service lookup as optional;
- disable only the enhanced adapter if a ROM-specific check fails.

## 2. Captured target identity

### Device / ROM

- Product family: vivo PD2115
- Android: 11
- OriginOS display family observed: `OriginOS Ocean`
- Build fingerprint:

`vivo/PD2115/PD2115:11/RP1A.200720.012/compiler1018205834:user/release-keys`

### Native floating-ball package

- Package: `com.vivo.floatingball`
- System APK path observed: `/system/app/FloatingBall/FloatingBall.apk`
- versionName: `2.5.32.0`
- versionCode: `253200`
- SHA-256:

`e24c914cc6e74f01922cd89385b2168226e5c425a5b212543ff4800ffdd0989e`

Other candidate system packages were also pulled and hashed under `analysis/originos/system-apks/`, including SystemUI, vivo theme resources, GlobalAnimation, SmartMultiWindow, UpSlide and related components.

## 3. Runtime observations

Read-only `dumpsys` / SurfaceFlinger snapshots showed that the native floating-ball package owns dedicated system windows/surfaces. Observed names include:

- `FloatingBallIdleView`
- `FloatingBallDragView`
- `FloatingBallEdgeView`
- `FloatingBallExpandedView`

The process was observed as:

`com.vivo.floatingball:floating`

with system ownership rather than a normal third-party application overlay.

This confirms that the native OriginOS implementation has privileges and window behavior unavailable to an ordinary APK.

## 4. Static-analysis findings

Static analysis of the pulled `FloatingBall.apk` found classes/interfaces including:

- `FloatingBallService`
- `FloatingBallManager`
- `FloatingBallIdleView`
- `AnimatedFloatingBallIdleView`
- `FloatingBallEdgeView`
- `FloatingBallExpandedView`
- `FloatingBallDragView`
- `FloatingBallBaseTheme`
- `FloatingBallSettingsTheme`
- `com.vivo.floatingball.aidl.IFloatingBallService`
- `com.vivo.floatingball.aidl.IFloatingBallServiceForSettings`
- `com.vivo.floatingball.aidl.IUpSlideServiceForFloatingBall`

The package requests OEM/system permissions such as:

- `android.permission.INTERNAL_SYSTEM_WINDOW`
- `android.permission.SYSTEM_ALERT_WINDOW`
- `android.permission.WRITE_SECURE_SETTINGS`
- `android.permission.INJECT_EVENTS`
- `android.permission.STATUS_BAR_SERVICE`
- multiple vivo-only permissions

The APK also contains explicit permission/system-signature checks. Therefore direct use of the private binder services from the ordinary FloatClip APK is not a safe or stable product path.

## 5. Resource findings

The native APK contains useful semantic resources and ROM-specific variants, including examples such as:

### Colors

- `floating_ball_circle_background_color`
- `floating_ball_expanded_func_icon_color`
- `floating_ball_expanded_func_label_color`
- `floating_ball_idle_view_*_color`
- `floating_ball_list_app_background_color_rom_9_0`
- `floating_ball_list_func_background_color_rom_9_0`

### Dimensions

- `floating_ball_idle_view_width`
- `floating_ball_idle_view_height`
- `floating_ball_idle_view_corner`
- `floating_ball_edge_view_width`
- `floating_ball_edge_view_height`
- `floating_ball_expanded_outline_corner`
- `floating_ball_expanded_outline_size`
- `floating_ball_panel_offset_size`
- `floating_ball_trigger_width`

### Skins/layouts/animations

- `activity_skin_theme_choose_*`
- `skin_view_choose_rom_12_0`
- `idleToEdge_left.json`
- `idleToEdge_right.json`
- ROM-specific floating-ball selector/animation drawables

These findings are useful for understanding OEM behavior, but FloatClip must not redistribute vivo proprietary artwork.

## 6. Integration decision

### Level A — standalone overlay

Status: **implemented and permanent fallback**.

Uses FloatClip's own `TYPE_APPLICATION_OVERLAY`, palette and interaction implementation.

### Level B — ROM-locked semantic theme bridge

Status: **implemented**.

`OriginOsRomLock` verifies the exact fingerprint, FloatingBall version and APK SHA-256. Only after all checks match does `LockedOriginOsSystemBridge` read semantic resources from the installed package.

Current bridge scope is intentionally narrow:

- no private vivo AIDL calls;
- no signature permission requests;
- no OEM asset copying;
- no SystemUI injection;
- semantic colors/dimensions only;
- automatic fallback on any failure.

### Level C — native floating-window controller adapter

Status: **not implemented**.

The static analysis confirms that the OEM implementation is privileged. Reusing native view/controller behavior would require a privileged/root/LSPosed-side adapter and must remain separate from the normal APK.

### Level D — clipboard system bridge

Status: **not implemented**.

If automatic background clipboard observation is later required, the preferred order remains:

1. validate a Shizuku/shell-side bridge on this exact ROM;
2. otherwise evaluate a narrow LSPosed/system-side observer;
3. keep storage/UI/image work inside the normal FloatClip process.

## 7. Implemented ROM lock

Source:

`app/src/main/java/com/floatclip/app/integration/OriginOsRomBridge.kt`

Supported identity constants:

- fingerprint: `vivo/PD2115/PD2115:11/RP1A.200720.012/compiler1018205834:user/release-keys`
- FloatingBall version: `2.5.32.0` / `253200`
- FloatingBall SHA-256: `e24c914cc6e74f01922cd89385b2168226e5c425a5b212543ff4800ffdd0989e`

Fail-closed states include fingerprint mismatch, package missing, version mismatch, source path unavailable, hash mismatch and hash-read failure.

Runtime log tag:

`FloatClipOriginOS`

## 8. Device acceptance still required

Before this analysis milestone is considered fully validated on-device:

1. add Android 11 package visibility for `com.vivo.floatingball` in the FloatClip manifest;
2. run debug build;
3. run lint;
4. install to `192.168.3.44:5555`;
5. launch the app;
6. verify the ROM lock reports `MATCHED` on this exact ROM;
7. confirm semantic resource lookup succeeds or safely falls back;
8. validate overlay behavior and inspect runtime logs.

The build/install sequence was previously interrupted by the MCP control plane remaining in `TOOL_REGISTRY_NOT_READY`; no FloatClip runtime failure has yet been established.
