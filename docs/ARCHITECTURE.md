# FloatClip architecture

Last updated: 2026-09-08

## 1. Architectural principle

FloatClip is split into a stable standalone application core and optional enhanced integration layers.

The standalone path must always remain usable. OriginOS-specific or privileged features may improve visual fidelity, clipboard observation, or native behavior, but they are never allowed to become startup dependencies.

The current target is a fixed vivo PD2115 Android 11 / OriginOS Ocean ROM, so ROM-locked adapters are acceptable when they fail closed.

## 2. Core app (no root)

### Overlay

`ClipboardOverlayService` owns a `TYPE_APPLICATION_OVERLAY` bubble and expanded clipboard panel.

Current behavior:

- draggable bubble;
- left/right edge snap;
- persisted bubble side and Y position;
- tap to expand;
- outside touch collapses unless panel-fixed mode is enabled;
- fixed mode keeps the panel open for repeated paste operations;
- foreground-service lifetime.

The expanded panel remains designed around non-destructive interaction with the editor underneath.

### Multi-paste

`PasteAccessibilityService` writes a selected item to the system clipboard and calls `AccessibilityNodeInfo.ACTION_PASTE` on the focused editable node.

This avoids synthetic key-event injection and permits repeated paste while the FloatClip panel stays visible.

### History

The MVP text history is stored in private SharedPreferences JSON.

Current entry capabilities include:

- text content;
- timestamp;
- pinned state;
- category;
- deletion;
- search/filtering;
- duplicate handling.

Migration to Room is recommended before rich-image records or larger history volumes are introduced.

## 3. Clipboard capture strategies

### A. Standalone mode

Android 10+ restricts continuous clipboard reads by normal background apps. FloatClip therefore exposes an explicit import action and tests whether temporarily focusing the overlay is sufficient on the target OriginOS build.

This remains the guaranteed no-root fallback.

### B. Default IME mode

Android allows the default input method to access clipboard content more freely. Turning FloatClip into a full/default IME would displace the user's normal keyboard unless a delegation/full-keyboard design is built, so this is not the preferred architecture.

### C. Shizuku bridge

A Shizuku user service may run with shell/ADB privileges and could potentially provide background clipboard observation without SystemUI injection.

Whether this works depends on Android/OEM binder checks and UID/package validation on the exact vivo build. It must be tested rather than assumed.

If reliable on the target ROM, this remains the preferred enhanced clipboard path.

### D. Root + LSPosed/SystemUI bridge

A privileged module can observe ClipboardService/SystemUI and may reuse OEM contexts/resources or native controller behavior.

Any such implementation must be:

- separate from the normal app process;
- ROM fingerprint/hash locked;
- thin and defensive;
- optional;
- immediately disable-able;
- unable to prevent the standalone app from starting.

### E. Platform-signed/system app

A platform-signed app would be the cleanest way to obtain signature permissions, but the retail vivo device does not provide the OEM platform signing key. This is not treated as a practical deployment route.

## 4. OriginOS integration architecture

### 4.1 Stable contract

ROM integration is exposed through `OriginOsSystemBridge` rather than being embedded directly into the overlay UI.

The bridge can supply an `OriginOsThemeSnapshot` containing semantic values such as:

- bubble background;
- panel background;
- primary/secondary text colors;
- corner radius;
- elevation;
- diagnostic metadata.

`AdaptiveOverlayThemeProvider` consumes the snapshot when available and otherwise uses the standalone palette.

### 4.2 Integration registry

`IntegrationRegistry` owns the active `OriginOsSystemBridge` implementation.

The app initializes this registry from application context before the main screen or overlay consumes the bridge.

This keeps OEM integration replaceable: a future privileged IPC client can replace the current local semantic-resource bridge without rewriting the core overlay.

### 4.3 ROM lock

`OriginOsRomLock` is the fail-closed gate for the analysed vivo ROM.

It currently requires all of the following:

- exact `Build.FINGERPRINT`;
- exact `com.vivo.floatingball` version name/code;
- exact SHA-256 of the installed FloatingBall APK.

Supported specimen:

- fingerprint: `vivo/PD2115/PD2115:11/RP1A.200720.012/compiler1018205834:user/release-keys`
- FloatingBall: `2.5.32.0` / `253200`
- SHA-256: `e24c914cc6e74f01922cd89385b2168226e5c425a5b212543ff4800ffdd0989e`

Any mismatch disables enhanced OriginOS integration.

### 4.4 Current bridge: semantic resources only

`LockedOriginOsSystemBridge` currently reads selected resources from the installed `com.vivo.floatingball` package only after the ROM lock matches.

Examples include:

- `floating_ball_circle_background_color`;
- `floating_ball_list_func_background_color_rom_9_0`;
- `floating_ball_list_app_background_color_rom_9_0`;
- `floating_ball_expanded_func_label_color`;
- `floating_ball_expanded_outline_corner`.

This implementation intentionally does not:

- invoke vivo private AIDL;
- request signature permissions;
- modify SystemUI;
- inject code into system processes;
- redistribute OEM drawable artwork.

If resource lookup fails, the theme provider silently falls back to standalone values.

## 5. Why native FloatingBall is not called directly

Static analysis and device snapshots show that `com.vivo.floatingball` is a system-owned component using dedicated windows such as:

- `FloatingBallIdleView`;
- `FloatingBallDragView`;
- `FloatingBallEdgeView`;
- `FloatingBallExpandedView`.

Its APK contains private interfaces including `IFloatingBallService`, `IFloatingBallServiceForSettings` and `IUpSlideServiceForFloatingBall`, and requests privileged permissions such as:

- `INTERNAL_SYSTEM_WINDOW`;
- `WRITE_SECURE_SETTINGS`;
- `INJECT_EVENTS`;
- `STATUS_BAR_SERVICE`;
- vivo-only signature permissions.

A normal APK cannot safely assume access to those capabilities. Therefore direct OEM service binding is excluded from the core application architecture.

## 6. Image clipboard design

Android `ClipData` can carry `content://` URIs.

Planned flow:

1. detect URI/MIME clip;
2. while access is valid, copy bytes into app-private storage;
3. store metadata and thumbnail references;
4. expose saved images through a `FileProvider`;
5. create a fresh URI `ClipData` item for re-copy/paste;
6. grant temporary read permission where possible;
7. offer the Android Sharesheet when the target editor does not support image paste.

Image-paste success ultimately depends on the receiving app/editor.

## 7. Crash containment and compatibility

ROM-specific code follows these constraints:

- private lookups are optional;
- failures are logged but do not crash the app;
- unknown ROMs use standalone mode;
- no database/image decoding/long I/O is allowed in future SystemUI hooks;
- every additional supported ROM must be explicitly fingerprint/hash documented;
- OTA or system APK changes should cause bridge deactivation rather than best-effort private calls.

Runtime OriginOS diagnostics use log tag:

`FloatClipOriginOS`

## 8. Build/deployment boundary

Build and device operations are handled outside the app through MCP TaskProfiles / controlled ADB.

Current profiles:

- `floatclip_debug` → debug APK;
- `floatclip_lint` → lint report.

The target acceptance device is expected at `<target-device-address>:5555`.

See `docs/STATUS.md` for the exact current continuation point and release gate.
