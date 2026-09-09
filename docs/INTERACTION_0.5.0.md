# FloatClip 0.5.0 interaction design

## Design goal

0.5.0 moves FloatClip away from “a floating Android dialog” and toward a continuously animated utility surface. The priority is continuity of motion and state: no frame should intentionally disappear between two states, and toggling fixed mode must not destroy/recreate the panel window.

## Floating ball physics

The floating ball uses release velocity as a first-class input. Dragging is direct; on release the controller preserves horizontal and vertical momentum, applies friction, clamps against safe display bounds, and only then settles toward the nearest edge. The intended feel is closer to a light puck / curling stone than to a strong magnet.

Interaction principles:

- low-speed release: short settle to the nearest edge;
- high-speed release: visible inertial continuation before edge capture;
- vertical velocity influences the final resting Y position;
- edge attraction increases near the edge instead of applying a constant strong pull everywhere;
- after settling, a delayed half-hide moves the bubble partially off-screen;
- touching the half-hidden bubble immediately cancels pending motion and restores direct control;
- no position teleport is used during release, settle or half-hide.

## Panel transition continuity

Opening and closing are overlapping transitions rather than sequential window swaps.

Open:

1. create the panel at the remembered geometry;
2. panel starts slightly scaled/translated toward the bubble and at alpha 0;
3. panel becomes visible while the bubble is simultaneously fading/scaling away;
4. the bubble window is removed only after its visual transition has completed.

Close:

1. panel starts its fade/scale/translation toward the bubble edge;
2. the bubble begins to reappear before the panel has completely disappeared;
3. panel and scrim are removed only after the transition ends.

This overlap avoids a blank frame or flash between windows.

## Fixed mode

Fixed mode is a state transition on the existing panel, not a panel reconstruction.

- The panel window remains mounted in the same position.
- Entering fixed mode fades the outside scrim to alpha 0 and then makes only that scrim non-touchable.
- Leaving fixed mode restores scrim touchability before fading it back in.
- The pin icon changes tint and performs only a subtle scale response.
- Clipboard rows and scroll position are not intentionally recreated solely because fixed mode changed.

This specifically prevents the 0.4.0 flash caused by removing and re-adding windows.

## Panel dragging

Dragging is available from the whole visual frame rather than only a small icon:

- left/right/bottom border zones;
- top border;
- top/header empty area, excluding the action buttons.

The panel remains clamped to safe display bounds and remembers the final position after a completed drag.

## Clipboard row gestures

Rows are designed around progressive disclosure:

- single tap: paste when Accessibility paste is available, otherwise copy;
- double tap: toggle pinned/top state;
- swipe left: reveal pin/unpin and delete actions;
- long press: open the extended menu (copy, pin/unpin, category, delete).

Pinned rows receive a subtle alternate surface/tint rather than an abrupt layout change.

## Categories

The panel and in-app clipboard page share one category model. The bottom horizontal category bar includes `全部` plus stored categories. Basic semantic classification may place recognized URLs, email addresses, phone numbers, verification-code-like strings and address-like strings into matching categories when those categories exist. Users can always override the category manually.

Deleting a category moves its entries back to the default category before removing the category label.

## Keyboard / focus behavior

The overlay panel stays `FLAG_NOT_FOCUSABLE`. 0.5.0 removes the previous temporary focus-grab path that could cause the currently visible input method to collapse. Clipboard observation/caching is handled without requesting input focus for the overlay window.

## Notification behavior

A foreground service notification cannot be reliably removed while preserving the Android foreground-service contract. 0.5.0 therefore minimizes disturbance instead: minimum-importance channel, no sound, no vibration, no badge, no timestamp and hidden lock-screen content. The Settings page links to the system channel settings for users who want additional OEM-specific control.

## Acceptance checklist

On the target vivo / Android 11 build, verify:

1. slow and fast floating-ball releases have visibly different travel distance;
2. the ball does not snap hard from the middle of the display;
3. vertical momentum changes the final resting Y position naturally;
4. half-hide starts only after the settle delay and contains no jump;
5. panel open and close contain no blank-frame flash;
6. toggling fixed mode repeatedly causes no panel flash or position reset;
7. keyboard remains visible when opening FloatClip over an active text field;
8. any border/header empty region can drag the panel without stealing row gestures;
9. left-swipe actions, double-tap pin and long-press menu do not fight vertical scrolling;
10. category filters and manual category assignment update both the app page and overlay;
11. normal-mode outside tap reliably collapses without touching the underlying app;
12. fixed mode allows interaction with the underlying app for repeated paste.
