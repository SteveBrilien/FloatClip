# FloatClip UI / motion repair — 2026-11-05

This continuation keeps the current Android build40 workspace as the source of truth and addresses the remaining UI/motion issues without touching the older sync workspace.

## Implemented

- Replaced floating clipboard image preview's Activity hand-off with a full-screen `TYPE_APPLICATION_OVERLAY` lightbox, so opening an image no longer feels like navigating into another FloatClip page.
- Added a shared `ClipImageViewerView` for app and overlay viewing: dark immersive canvas, upper-right close action, image counter/dimensions, copy feedback, safe status/navigation-bar spacing, zoom/pan, and horizontal paging through the currently filtered image sequence.
- Horizontal paging is only accepted when the image has no horizontal pan room, preventing page gestures from fighting zoomed-image dragging.
- Split long-press actions onto distinct semantic icons for image/text view, copy, paste, category, pin, protect, delete, back, and cancel.
- Replaced the animation-curve modal chooser with an inline segmented selector (soft / snappy / linear / custom). Custom Bézier controls expand inline.
- Added route-aware main-screen transitions only when navigation depth changes, avoiding animation flashes on ordinary content refreshes.
- Added a subtle common Activity open/close transition so Device, Node, File, image-import and other standalone screens no longer fall back to abrupt/default page switches.
- Added regression coverage for image-lightbox paging/close affordance and named animation presets.

## Verification

Final durable verification job:
`task-floatclip_photo40_android_verify-51c30cf8993b4638bc37`

Result: `BUILD SUCCESSFUL`
- `:app:assembleDebug`: passed
- `:app:testDebugUnitTest`: 108 tests, 0 failures, 0 errors, 0 skipped
- `:app:lintDebug`: passed
- Debug APK SHA-256: `031ae468684cbf20fcc14a2dbacced25d1822ddd30833bdaf2773d87db150c6c`

The vivo V2115A Android 11 rescue ADB path was online. The handset already has `com.floatclip.probe 0.7.9 build40` installed. A non-destructive `adb install -r` of the verified APK was attempted, but the Azure/reverse ADB transfer hit the host capability's 300-second timeout. Package `lastUpdateTime` remained `2026-11-04 19:28:16`, confirming the new APK was not installed and existing app data was not replaced.

## Deliberately untouched

Pre-existing workspace changes `updates/share-preview.json` and `scripts/build-feedback-20261029.sh` were not modified or included in this UI/motion work.
