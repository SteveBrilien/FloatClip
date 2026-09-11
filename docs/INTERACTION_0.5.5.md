# 0.5.5: restored swipe actions and visual polish

## Accepted baseline
The user confirms 0.5.4 no longer jitters. Do not reintroduce animated window opening/closing.
Source comparison verifies the five window/motion methods expandPanel, showRetainedPanel, collapsePanel, installBubbleTouch and hideBubbleAtEdge are unchanged from source 505ce76749e48e2bb2358700b9efb00783e48305.

## Interaction
Swipe-left pin/unpin and delete are restored for overlay/history entries, with monochrome line icons and short labels.
Right swipe, front tap, touching another row and 4.5-second idle timeout close the actions.
The action layer is clipped to the exposed region; a closed front owns DOWN so covered action buttons cannot receive taps.
Tap-copy, double-pin and long-press options remain. Retained-panel closure cancels pending gestures and hides swipe actions.
Category drag handles remain independent from entry swipes.

## Visual corrections from screenshots
- Back and category management use bounded header icon controls instead of wrap-height full-width strips.
- Settings use whole-row targets, trailing state/chevron, and a switch for enabled/disabled options.
- Search uses a single flat field with keyboard-search and icon submission, without nested bordered cards.
- Cards use fewer borders and smaller radii; bottom navigation uses text emphasis instead of large filled pills.
- Long-press menus remain one column, are capped at 216dp width, use consistent 19dp icons and at least 48dp rows, and have a separated destructive action.
- Menu height is measured, capped to the panel, and scrollable; placement follows the selected entry and stays inside panel bounds.

## Device checks pending
1. Closed-row taps in every horizontal position copy only; double tap pins once.
2. Swipe pin/delete works, hidden actions never show through, all closure paths work.
3. Long-press menus near first/last rows stay in bounds; category submenu scrolls at smallest panel and larger font scale.
4. Settings whole-row taps and switch actions fire once; header back and category icons have usable touch areas.
5. Search works via keyboard and icon, retaining the query.
6. Recheck open/close remains stable in normal/fixed mode after menu/swipe usage.

Compile/lint and signed-public-APK checks are recorded in STATUS.md; they are not device visual acceptance.
