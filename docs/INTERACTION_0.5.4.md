# 0.5.4 retained-window switching and simpler navigation

The user's 0.5.3 device feedback reports dismissal jitter still present. The preceding compile/lint gates did not establish visual correctness. This iteration removes the former transition mechanism instead of retiming it.

## Window lifecycle
- The bubble, panel and outside-touch catcher stay attached after the first opening. Closing makes panel/catcher windows transparent and NOT_TOUCHABLE; the bubble becomes visible and touchable.
- Opening restores the existing panel and its touch policy. No close-time removeView/addView, cross-fade, scale, translation or deferred edge-hide is used.
- Closing preserves the pre-open bubble coordinate, stops motion, cancels pending row gestures and clipboard polling, and hides the menu.
- Hidden windows remain allocated but do not intercept input. Service shutdown and configuration/appearance rebuild still remove them deliberately.
- A distinct panelOpen state separates an attached cached panel from a visible panel.
- Bubble MOVE below drag threshold no longer changes the window position.
- Fixed mode keeps the catcher NOT_TOUCHABLE. Normal mode consumes the outside-dismiss gesture.

## Menu and navigation
- Long press shows one vertically scrollable column: Copy, optional Paste, Pin/Unpin, Change category, Delete. Category selection is a separate vertical list with Back.
- The menu is opaque for legibility and disables panel border/header dragging while open.
- Swipe actions are removed; the gesture row now only recognises tap, double tap and long press.
- Home contains the enable/disable control and access to permissions/help.
- History shows filtering, search and entries; category editing is a separate view.
- Settings shows theme selection and links to appearance, permissions/background, backup/recovery and About.
- Large explanatory cards, duplicate status/permission listings, the second background section and the unimplemented sync editor are removed.
- Necessary explanations appear on explicit action/help. Backup write-protection status remains visible.
- Nested screens support Back and survive activity recreation.

## Acceptance
Build/lint and package/signer checks are required before release. Device visual acceptance remains pending manual installation, particularly:
1. Repeated normal/fixed open-close; no root transformation or delayed bubble movement.
2. While closed, taps and scrolls where panel/catcher used to be reach the underlying app.
3. Normal outside dismissal consumes that tap; fixed mode allows underlying interaction.
4. Long-press menu and category submenu scroll on the smallest panel; action rows do not drag the panel.
5. No delayed copy after dismiss; tap copies once and double tap pins once.
6. Category drag/cancel/order persistence and nested-screen Back.
7. Stop service removes all retained windows; restart and theme changes restore the correct visible state.

The retained panel trades a small amount of cached view memory for avoiding window allocation/removal during every toggle. No clipboard storage or signing identity changes.
