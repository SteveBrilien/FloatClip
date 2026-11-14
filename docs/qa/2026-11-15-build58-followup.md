# FloatClip build58 Android / Windows build56 — follow-up verification

## Android

- Android 11 clipboard restriction: only a focused app or default IME can read the system clipboard normally. Accessibility and foreground service do not provide blanket clipboard access. The existing optional, locally encrypted **ADB shell capture helper** can collect copy notifications when explicitly activated, but is unavailable after a phone reboot until reactivated. Enabling this privileged companion is user opt-in. The helper remains text-only.
- An independent `clipboard_text_queue_v1.bin` FIFO (AES-GCM encrypted by existing Android keystore, bounded to 96 text events / 8 MiB) stages captures **which Android actually permits**. Previous `clipboard_sync.v1.bin` and pairing state are never migrated or overwritten. Core v1 text frames remain one-at-a-time, so the receiver's ACK advances the queue; a lost ACK is retried after the v1 TTL instead of discarding the queued text. Send revocation and exclusion clear pending journal; first-time handshake is required. Replaying an event after a crash can result in duplicate-content updates; this is preferable to silently losing text.
- Android overlay long-press menu drops the redundant "粘贴" action, removes the entire-panel dark tint and enables `FLAG_WATCH_OUTSIDE_TOUCH` only while the menu is visible. Pinned panel stays up; outside touch dismisses the menu and is not consumed by the overlay. OEM variations require a real-phone test.
- Image clipboard paste remains dependent on receiver app's rich-content paste implementation; added explicit ACTION_SEND chooser with the per-entry JPEG/PNG MIME, content URI, and read grant to send to apps that cannot paste into a text field. Do not claim every third-party editor supports image paste.
- Image viewers' images fill the available canvas behind status/navigation bars; actionable chrome adapts to safe display/gesture insets. The service overlay cannot guarantee hiding Android system bars; the Activity viewer uses immersive sticky flags when not editing a note.

## Windows

- Added desktop **克莱因蓝** theme with `#0F2254` background, matching phone palette / Fluent tokens, and stable selection in settings. Windows build is 56 while Android build is 58.
- Capped clipboard thumbnail cache to 8 MiB, file preview thumbnail cache to 16 MiB, release image cache when window hidden. Added opt-in on-demand memory diagnostics showing working set, private committed memory, managed heap and image cache without reading clipboard content or forcing GC.
- Windows Remote currently disconnected: no claim that WPF/Emoji renderer or preview components account for a specific percentage of 200–400 MiB. Use the opt-in diagnostic on the actual workstation before estimating hotspot.

## Rollout

- Android tests, Windows WPF build, GitHub Windows CI and user-operated ADB must be validated separately. The original Android app signature and v3 JWS trust are unchanged; no remote device install or data reset is performed without safe migration/verification.
