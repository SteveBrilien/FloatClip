# Android build43 — classic settings and borderless entries
2026-11-06

User requested removal of the remaining white/gray entry hairlines and restoration of the settings layout from before adjustable animation curves.

- Restored the appearance section/control structure from the parent of e61fd00 (the last settings revision before the curve and range editors): theme colors, image columns, floating-ball size/opacity/sensitivity/edge hiding, panel position/opacity/card opacity/dimensions, text lines and conventional press/timing sliders.
- Restored independent device-color and tinted-card controls plus the older explicit sharing/recovery/notification choices. The restored controls use real preferences; the list uses the selected image column count.
- Removed the unconditional stroke from the shared text/image card. Both app history and floating history use this shared card. Legacy border settings cannot restore entry outlines.
- Kept the current image decoder/cache, resize gestures, service lifecycle, safe manual migration and dedicated About/permission/license pages. The original app identity and update public key remain unchanged.
- Curve and interaction-range editors are absent. The fixed default curve is retained; one-time migration removes hidden legacy customizations without changing service consent, history or pairing.
- Android 11 does not show a nonfunctional real-blur slider. Supported systems use the existing blur capability check and connect the restored strength control to the blur radius.
- Android build43; Windows stays on the independently verified build42.

## Verification and delivery

- Final verification job: task-floatclip_build43_android_verify-aebfdc2f25c04c82887e — succeeded.
- Android 120 tests passed, 0 failures/errors; lint 0 errors. The border test draws both text/image card surfaces in Light/Dark, including a legacy enabled-border preference. Restored settings UI and actual user-input callbacks are covered.
- APK: FloatClip-Android-0.7.9-build43.apk; 4059245 bytes; SHA-256 323c686f8c84af0bf51c5b3fe27211cc425844a765e2fdc0cd9cd15d3c93030e.
- Source checkpoint: 940f9c9eb0557f09c454c4fce8a01b6c7e5fa993. Original package com.floatclip.probe and certificate 0368593f82ab885737b99d78c939111a1ec8ea3d88e8a67b2c3c9a4bd90c3f2c retained.
- Installation was attempted through the recovered ADB channel. Android returned INSTALL_FAILED_ABORTED: User rejected permissions; no retry or permission bypass was performed. A subsequent Package Manager read still reports build42 with the unchanged first-install timestamp. Build43 is available for a normal user-confirmed upgrade.
- Both update channels were signed on the original Windows computer and independently verified against the pinned public key. Android build43 / Windows build42 is an intentional asymmetric train. Development metadata now matches both source builds, and the local product-contract check passes.
- Actual build43 device visuals, specific image-paste targets, 60/120Hz motion and prolonged OEM background duration remain unverified. Earlier build42 native observations are not claimed as build43 acceptance.
