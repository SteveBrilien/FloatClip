## 2026-09-09T09:22:52.780876Z — FIX: Correct GitHub release signing identity before device delivery

The first v0.4.0 GitHub-hosted APK was built with GitHub Actions' ephemeral default debug keystore. Its signing certificate SHA-256 (f52f107091f39f120ce62ebb0e4c326deb488d0cc3981766fa27195e78d47ce0) did not match the stable FloatClip device/update certificate (9e476b6a60e08a14ec0d563ee1dde772145c70ea0463ce227b9b6e9e50275056), so that asset would not be overwrite-install compatible. Added the clean local release artifact from source commit effe2409b99cc124801ed5a5b2269a797c9cf380 under dist/, recorded its SHA-256 4aa2e1dfbaedc02ee9d2ca63328d128b8235d6861735869d151c33827a084182, changed tagged releases to publish only preverified stable-signature assets, and added a one-shot [sync-release] workflow path that replaces the existing v0.4.0 release asset via the repository GITHUB_TOKEN after CI passes.

Files:
- `.github/workflows/android.yml`
- `dist/FloatClip-0.4.0-debug.apk`
- `dist/FloatClip-0.4.0-debug.apk.sha256`
- `dist/release.env`
- `dist/README.md`
- `CHANGELOG.md`

## 2026-11-05T13:53:42.540406Z — FIX: Repair Android image lightbox, action icons, and navigation motion

Floating image preview now stays in an overlay lightbox with swipe paging; action icons are distinct; motion curves use inline presets/custom controls; internal routes and standalone Activities have natural transitions. Final verify: 108 unit tests, lint, assemble all passed. Vivo rescue install was not completed because the remote ADB transfer hit the 300s transport timeout; existing phone data/app remained unchanged.

Files:
- `app/src/main/java/com/floatclip/app/MainActivity.kt`
- `app/src/main/java/com/floatclip/app/clipboard/ClipImageViewerView.kt`
- `app/src/main/java/com/floatclip/app/overlay/ClipboardOverlayService.kt`
- `app/src/main/java/com/floatclip/app/prefs/OverlayPreferences.kt`
- `app/src/main/java/com/floatclip/app/ui/ZoomImageView.kt`
- `app/src/main/res/values/themes.xml`
- `docs/UI_MOTION_FIX_2026-11-05.md`
