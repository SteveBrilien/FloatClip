# FloatClip 0.7.9 build42 — 2026-11-06

Source: floatclip-cloud, isolated analysis/cloud-workspace-build25 repository. The older root workspace and unrelated updates/share-preview.json changes are preserved.

## Images

Originals were already separate encrypted files in files/clipboard-images, with references/dimensions in the metadata vault. The old lightbox requested an 8192-dimension / 8-million-pixel bitmap through the 8 MiB card cache, causing repeated decoding and eviction.

Now: 480 thumbnail first; one decoder for the current original; visible-region decoding capped at 3 million pixels; coalesced viewport requests; stale delivery rejected; current source released on close/page. Thumbnail upgrades preserve original-image zoom/scroll coordinates. Card cache stays at 8 MiB. Zoom drawing reuses destination rectangles instead of allocating temporary objects per frame. Long cards decode their top region only.

Copy still uses Android ClipData.newUri plus URI read grants. PNG-suffixed URIs and a seekable proxy descriptor improve receiving-app compatibility; old URI grants and the pipe fallback remain supported. SIZE queries use metadata. A target editor must still support image content; an ordinary overlay cannot provide every IME-specific insertion path.

## User-confirmed settings

| Item | Final behavior |
| --- | --- |
| Theme | System / Light / Dark, fixed blue accent |
| Device colors / source tint | One device-color switch |
| Image columns | Automatic by available width; text full-width |
| Cross-device image grouping | Both choices retained |
| Bubble size | Small / Medium / Large |
| Bubble opacity | Independent slider retained |
| Sensitivity | Fixed density-aware response |
| Edge hiding | Default partial hiding; no ratio slider |
| Panel placement | Follows floating ball |
| Panel dimensions | Drag borders/corners, persist size; title drag moves |
| Text preview lines | Retained |
| Material | Solid / Frost / Transparent plus preview |
| Transparency | One slider; readable foreground; cards >=94% opacity |
| Borders | Theme-managed |
| Curve / Bézier / range editors | Removed and legacy values migrated |
| Press scales | Weak / Standard / Strong |
| Motion speed | Fast / Standard / Slow; faster close, at most 200ms; toggles follow |
| Reset | Retained; service consent/history untouched |
| Share master | Retained |
| Reconnect / survive task removal | Follow sharing; no duplicate switches |
| Text send/receive | One bidirectional sync toggle |
| Image sync / apply incoming clipboard | Independent toggles retained |
| ADB capture | Advanced sharing |
| Detailed notification | Simple default; mandatory foreground notification retained |
| Overlay recovery | Follows overlay master |
| Permissions | Central system grant/settings page |
| Startup update check | Retained |
| Update channel | Inside About & Updates |
| Backup | Manual encrypted export/import, preview and safe merge |
| About | Dedicated page; version/build, permission purposes, offline Kotlin and Java Annotations license notices |
| Categories / overlay master / help | Retained |

Android 12+ uses blur-behind only when the system reports support. Android 11 explicitly shows a high-opacity fallback, not a claim of real cross-window blur.

Migration preserves previous backup files and stops the old automatic export loop. Portable exports include images and protection flags. Import verifies the envelope, previews counts, then merges in a worker. Existing keys win; incoming IDs/image references are local. Capacity/attachment/corrupt-current-vault failures preserve current history. Pairing secrets are not exported.

The service-owned bubble, panel and image overlay explicitly request hardware rendering before attachment (renderer support still depends on the device). Animation uses vsync-driven ViewPropertyAnimator, with generation guards, faster close and invisible final surfaces. Ball touchability recovers immediately, independently of animation completion, preserving prior quick-open/close recovery.

## Windows

Restored pre-build41 Light/Tokyo Night palette and appearance controls; removed system accent, white boundaries and added card color/opacity overrides. Kept independent loading/storage/window work and native whole-window alpha compatibility. Close is neutral/transparent at rest, danger only on hover/press. Pin uses native system glyphs without a permanent colored border. Build metadata is 42.

## Verification

Windows: 93 data checks, 47 update checks, desktop and desktop-checks cross-build, self-contained packaging. Desktop build had 0 warnings/errors. Native WPF execution, physical DPI, actual hover/DWM remain unverified after Remote went offline.

Final Android evidence belongs to the final build42 job and analysis/build42/android-verification.json. The earlier 114-test pass predates the final resize/migration/about changes and must not be presented as final verification.

Both Remotes and ADB were offline in the final phase. No installation, actual 60/120Hz smoothness, OEM task-clear behavior, proxy URI behavior or paste into the user's target editor is claimed. Foreground service and task-removed recovery remain enabled by the corresponding master switches. Force-stop/OEM suppression cannot be guaranteed away.

Signed public update feeds remain unchanged.

## Final delivery evidence

- Source checkpoint packaged for Android: ac302a0b163e7d17c6b861f5aba49cb7fe4cb1d2.
- Android verification/package job: task-floatclip_build42_android_verify-6ceb9c36b8c749bbb57e — succeeded.
- Android tests: 118 passed, 0 failures/errors/skipped.
- Android lint: 0 errors, 28 warnings; DrawAllocation warnings: 0.
- Android package ID: com.floatclip.probe; version 0.7.9 / build42.
- Signing certificate SHA-256: 0368593f82ab885737b99d78c939111a1ec8ea3d88e8a67b2c3c9a4bd90c3f2c, matched against build41.
- APK: FloatClip-Android-0.7.9-build42.apk, 4169885 bytes; SHA-256 7d7d32a635fc51bbc546dfdc31b1cb6949ac730f5378fe2190c08ec670d43e32.
- Windows package job: task-floatclip_build42_windows_package-72b1037f9b0a4eeb8baf — succeeded.
- Windows: 93 data checks, 47 updater checks; WPF/desktop-checks cross-build and self-contained package succeeded.
- ZIP: FloatClip-Windows-0.7.9-build42-candidate.zip, 79057838 bytes; SHA-256 ccfbd38da68a9602400fe869f88c7ccbc8b2430dbf5c9d9406d4e90c4b6e42c2. Includes 9 license files.
- Both packages published with non-expiring Artifact publications; live download bytes verified against ZIP/APK magic.
- Devices were offline; no installation or native visual, 60/120Hz, OEM background-duration or app-specific paste acceptance is claimed.
- Public update feeds were not replaced. Existing external backups/history were not removed.
