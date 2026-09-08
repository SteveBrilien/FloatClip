# Roadmap

Last updated: 2026-09-08

## M0 — feasibility prototype
- [x] project skeleton
- [x] overlay permission flow
- [x] floating bubble drag and edge snap
- [x] expand/collapse panel
- [x] fixed-open multi-paste mode
- [x] text history + pin + delete
- [x] basic/custom categories
- [x] search
- [x] Accessibility `ACTION_PASTE` bridge
- [x] persistent bubble side/Y position
- [x] read-only OriginOS baseline/native-float capture
- [x] pull and hash FloatingBall/SystemUI candidate APKs
- [x] FloatingBall static analysis
- [x] identify native `FloatingBallIdleView` / `EdgeView` / `ExpandedView` architecture
- [x] implement fail-closed OriginOS ROM lock
- [x] implement first semantic-resource-only OriginOS Bridge
- [ ] add `<queries>` visibility for `com.vivo.floatingball`
- [ ] debug build after OriginOS Bridge changes
- [ ] lint after OriginOS Bridge changes
- [ ] install on target vivo Android 11 device
- [ ] validate ROM lock on the exact target build
- [ ] validate temporary-focus clipboard import on OriginOS
- [ ] complete runtime acceptance checklist

## M1 — usable standalone app
- [x] custom category CRUD
- [x] search/filtering
- [x] per-item delete
- [x] pin/unpin
- [x] duplicate handling in text history
- [x] persistent bubble position
- [x] adaptive light/dark standalone styling
- [ ] per-item text editing
- [ ] configurable duplicate policy
- [ ] configurable panel size/placement
- [ ] better OriginOS-inspired motion/geometry using measured native values
- [ ] boot/autostart guidance for vivo background management
- [ ] privacy controls: sensitive-clip exclusion, retention limit, clear all
- [ ] export/import of text history and categories

## M2 — images and rich content
- [ ] URI/MIME capture
- [ ] private image cache + FileProvider
- [ ] thumbnails
- [ ] image re-copy/paste
- [ ] temporary URI permission handling
- [ ] Android Sharesheet fallback
- [ ] receiving-app compatibility matrix

## M3 — enhanced privilege bridge

### M3A — Shizuku/rootless investigation
- [ ] test Shizuku on the exact PD2115 Android 11 ROM
- [ ] determine whether clipboard binder/package/UID checks permit reliable observation
- [ ] if viable, implement narrow privileged clipboard observer service
- [ ] keep UI/storage outside the privileged service

### M3B — LSPosed/SystemUI optional module
- [ ] only if required, prototype a ROM-locked LSPosed adapter
- [ ] re-validate fingerprint + APK hashes before hook activation
- [ ] evaluate safe native view/controller reuse
- [ ] evaluate native edge/idle/expanded motion reuse
- [ ] provide immediate kill switch/fallback to standalone
- [ ] never make app startup depend on module presence

## M4 — OriginOS polish
- [x] identify native semantic color/dimension resource names
- [x] implement semantic-resource-only bridge for the exact analysed FloatingBall build
- [ ] verify active "未来科技" resource values on-device
- [ ] compare FloatClip bubble size/edge geometry against native FloatingBall
- [ ] match edge-hidden state
- [ ] match expand/collapse motion language
- [ ] add haptics
- [ ] improve panel translucency/elevation/insets
- [ ] handle rotation and navigation-mode changes against measured native behavior

## M5 — reliability and maintenance
- [ ] crash/ANR diagnostics export
- [ ] bridge health/status screen with copyable identity data
- [ ] backup/export/import for history, categories and preferences
- [ ] migration/versioning for persisted data
- [ ] update checker
- [ ] rollback-friendly APK releases
- [ ] device acceptance script and repeatable regression checklist
- [ ] document every newly supported ROM fingerprint/hash before enabling it

## Current release gate

The current milestone is **not complete** until all of the following are true:

- `floatclip_debug` succeeds;
- `floatclip_lint` completes without blocking findings;
- APK installs to `192.168.3.44:5555`;
- MainActivity launches without crash;
- ROM lock is observable and behaves fail-closed;
- overlay bubble starts and remains stable;
- drag/snap/expand/collapse/fixed mode are verified;
- clipboard import and accessibility paste are tested on-device;
- logs show no repeated fatal exception or permission loop.

See `docs/STATUS.md` for the exact continuation point.
