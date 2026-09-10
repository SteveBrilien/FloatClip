# FloatClip 0.5.2 interaction / runtime refinement

## Goal

0.5.2 is a focused response to the second on-device review of 0.5.1. It removes ambiguous clipboard-row gestures, replaces the platform popup with a palette-aware in-panel action surface, changes category ordering to direct manipulation, and changes the panel-to-bubble close path so WindowManager surfaces are not inserted halfway through the close animation.

## Panel close continuity

The 0.5.1 close path still created the destination bubble during the panel fade. On the target OriginOS build that means a new overlay surface can appear while another overlay surface is animating, which can produce a visible one-frame twitch even when neither surface intentionally dims the screen.

0.5.2 creates the destination bubble before the transition begins but keeps it visually hidden. A single ValueAnimator then drives both sides of the handoff: panel alpha/scale/translation and bubble alpha/scale. The panel and transparent outside-touch catcher are removed only after the panel is fully invisible. The close duration is 420 ms. No delayed WindowManager add/remove operation occurs in the middle of the visible transition.

## Clipboard-row gesture contract

The previous double-tap-to-pin gesture shared a recognition window with single-tap paste/copy. On a floating overlay that makes accidental pinning too easy and delays the perceived single-tap response.

0.5.2 therefore uses this contract:

- single tap: paste when the accessibility paste bridge is connected, otherwise copy;
- left swipe: reveal pin/unpin and delete actions;
- long press: open the extended action surface;
- no double-tap action.

Long press is recognized after 360 ms with a movement tolerance larger than the normal touch slop. It does not depend on pressure. A short haptic pulse confirms recognition. This is intended to make a normal, light press reliable while still allowing vertical scrolling and horizontal reveal gestures.

## Themed extended action surface

The Android PopupMenu used in 0.5.1 is removed. The long-press surface is rendered inside the existing overlay panel using the active FloatClip/OriginOS palette. It contains a short preview, Copy, Pin/Unpin, Delete, and horizontally scrollable category choices. It inherits light/dark mode and panel opacity semantics instead of using a separate system popup theme.

## Category ordering

The up/down buttons are removed. Each custom category is rendered as a draggable row with a dedicated drag handle. Android's platform drag-and-drop API is used so the current dependency-free View implementation does not need AndroidX RecyclerView solely for this small settings list. Reordering is shown live and persisted through CategoryStore; the same order feeds the app filter and floating category strip.

## Runtime modes

FloatClip now has two non-root runtime paths.

### Accessibility-hosted mode

When the existing one-key-paste AccessibilityService is enabled, the system owns that service's binding lifecycle. The accessibility service binds the overlay runtime and becomes its lifecycle host. If the overlay had previously been started as a foreground service, it removes itself from foreground state and clears its started state while the accessibility binding remains. This removes FloatClip's foreground-service notification without pretending that a foreground service can legally run notification-free.

This mode is intended to survive ordinary task/recent-list removal better than a standalone app-started service on the fixed Android 11 target. It is still best-effort on OriginOS and cannot override an explicit force-stop or an OEM decision to disable/kill the accessibility service.

### Foreground-service fallback

If the AccessibilityService is not enabled, FloatClip retains the standard foreground-service path. Android requires a foreground-service notification, so the notification remains in this fallback mode. START_STICKY, task-removal scheduling, boot recovery and package-replacement recovery remain as secondary recovery mechanisms.

## Prior-art review

Before implementing 0.5.2, the following approaches were compared:

- Android's official foreground-service and bound-service lifecycle model: a foreground service must display a notification, while a purely bound service is kept alive by its client binding;
- Android AccessibilityService lifecycle: an enabled accessibility service is started and bound by the system rather than by an ordinary app background start;
- open-source floating overlay projects such as FloatingOverlayView and MediaFloat, which use SYSTEM_ALERT_WINDOW plus a foreground service and therefore retain the foreground notification;
- TaskBarAndroid, which combines an AccessibilityService/TYPE_ACCESSIBILITY_OVERLAY path with an overlay service;
- Easy Clipboard, which combines an IME, ACTION_PROCESS_TEXT, accessibility UI, a foreground monitor, and optional Shizuku for global clipboard capture;
- KDE Connect Shizuku forks, which move Android 10+ background clipboard access into a Shizuku user service when ordinary app clipboard access is denied;
- vivo's system push architecture, which keeps the vendor push long connection in the system. This explains why messaging apps can receive notifications while their own process is offline, but it does not provide a way for a third-party clipboard app to keep a live floating UI process permanently resident.

The current decision is to keep the default installation non-root and non-Shizuku, reuse the already-required AccessibilityService for lifecycle hosting, and keep the foreground-service path as fallback. Shizuku remains a future optional enhancement for true global clipboard capture, not a prerequisite for the core floating UI.

## Device acceptance checklist

After overwrite-installing 0.5.2 on the target vivo/OriginOS device, verify:

1. closing the panel shows one continuous panel-to-bubble transition with no one-frame twitch or luminance flash;
2. a single light tap on a row only pastes/copies and never changes pinned state;
3. a light long press triggers after roughly one third of a second, produces haptic feedback and opens the themed action surface;
4. the long-press surface matches the current light/dark palette and no white legacy PopupMenu appears;
5. left-swipe pin/delete still works and vertical list scrolling is not captured as a long press;
6. category rows can be reordered by the drag handle and the order is preserved after leaving/reopening the page and is reflected in the floating category strip;
7. with one-key paste AccessibilityService enabled, enabling the overlay removes FloatClip's foreground-service notification after the accessibility host binds;
8. in accessibility-hosted mode, clearing FloatClip from the recent-app/task UI does not permanently remove the floating ball; if OriginOS kills the process, the system accessibility binding should recreate it when permitted;
9. disabling Accessibility while FloatClip is meant to remain active causes the runtime to fall back to the foreground-service recovery path and its required notification;
10. using Android/OriginOS Force stop is treated as an explicit stop: FloatClip is not expected to self-revive until the user explicitly launches/interacts with it again.
