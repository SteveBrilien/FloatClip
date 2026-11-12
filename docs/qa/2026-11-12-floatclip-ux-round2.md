# FloatClip Android UX second pass — build54 candidate

Base: verified build53 `c217ea2a`, verified on vivo V2115A/PD2115 via 127.0.0.1:25555. Separate worktree, existing data not touched by coding.

## Changes

1. Source/category tab transitions follow tab order (+ direction enters from the right, - enters from the left), rather than using an unconditional animation. Dynamic source/category content still uses lazy ListView.
2. Home/recent-apps no longer forcibly disables pinned panel or collapses it. If OEM detaches a pinned overlay, retry opening the panel rather than the bubble. Screen-off privacy behavior is unchanged.
3. Correct UI semantics: 0% transparency means fully opaque, 55–65% means more transparent; old stored *alpha* is preserved via inverted adapters, so no migration. Solid-background mode remains opaque by design.
4. Removed randomized faux frosting from panel and menu. API 30 and below presents solid or transparent choices only and exposes a clickable explanation. API31+ retains `FLAG_BLUR_BEHIND` when supported; no Compose shader dependencies added.
5. Read-only check of signed native feed: `updates/native-updates-v2.json` on branch `native-updates-v2` currently advertises **build50**, while GitHub's newest Release found on 2026-11-12 is build52. Build53/54 will not be offered older update, but release feed is stale. Republishing requires signing authority; do not silently bypass the signed-manifest verification.
6. Category rows expose direct icon and color controls, with visual icon grid and color preview. Save commits after confirmation; long press/row tap still exposes rename and deletion confirmation; reorder handle remains.
7. Wi-Fi direct entry now shows FloatClip's explanation/confirmation first even when network permission already exists. Android's Wi-Fi network request security dialog **cannot be replaced** on Android 11. It still appears when connecting.
8. File list switches Select and Time positions; dropdowns use consistent text and chevron hit targets; details show file type, source, status, and size. Only user-selected-folder exports provide the `在文件夹中打开` action; internal app cache does NOT imply file has been exported. Per-file transfer progress is driven from live repository bytes and active session transport (`蓝牙`, `局域网`, `Wi-Fi Direct`), not from a hardcoded Bluetooth indicator.

## Blur dependency evaluation

* Cloudy: Compose-oriented; API30 and lower background CPU blur is opt-in and default fallback is translucent scrim. True cross-app backdrop in an Android 11 `TYPE_APPLICATION_OVERLAY` would still need screen content capture (privacy, user consent, frame/battery cost); Cloudy doesn't simply blur other apps by itself.
* PrismalAGSL: Jetpack Compose and Kotlin compiler 2.2+; liquid AGSL requires API33+. API30 legacy visuals are not true GPU cross-window blur. Integrating either library into this View-only application would import Compose runtime/dependencies and extra binaries; actual APK size, frame-time and battery cost have not been benchmarked on device. Neither dependency is used.
* Decision: **0 bytes added for a blur dependency** and no continuous screen capture. Remove the noisy fake shader entirely. On Android 11 do not silently present faux blur as real blur.

## Remaining acceptance work

- Build and Robolectric suite must pass, then use `adb install -r` only with user approval on the phone, same signer and increasing versionCode. Do not call it released/installed yet.
- Verify user-visible tab animations and pinned overlay after Home/Recents, permissions, Android's mandatory Wi-Fi dialog and the saved file URI action. Exact OEM overlay behavior needs physical confirmation; tests cannot ensure OriginOS overlays are retained.
- Image imports **still encode all images as PNG** and image blobs still use vault encryption in this safe branch. Format-preserving migration needs its own backwards-compatible storage/protocol design; do not mix it with UI fixes.
