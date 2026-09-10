# FloatClip 0.5.3 interaction repair

## Confirmed source defects in 0.5.2
- The swipe action layer was always visible below a translucent front view. Since that front did not consume DOWN, covered pin/delete children could receive taps.
- Swipe direction recognition occurred only in interception. When the row itself owned DOWN, MOVE did not establish horizontal mode, so dragging could fail to close.
- Category sorting used platform drag shadows, removed/reinserted rows on every crossing, and did not restore visual order on cancelled drops.
- Expansion discarded the bubble surface and dismissal allocated another; close animations also accepted child input and concurrent clipboard redraws.

## Interaction contract
- Single tap copies after Android's double-tap confirmation interval. Double tap toggles pinning without also copying. Long press opens themed options.
- Swipe reveals only Delete. Right swipe, tapping the front, opening another row, or 4.5 seconds of inactivity closes it. Covered action regions are clipped and cannot receive DOWN.
- The top-right pin icon controls the entire panel's fixed-open mode. Entry pinning remains a double-tap shortcut and an explicit option in the long-press sheet.
- Category drag begins after touch slop on its handle, shows a raised card following the finger, animates neighbours into the gap, scrolls near viewport edges and persists only on successful release. Cancel restores the original order.
- Category/history deletion moves to swipe actions; categories retain their entries under Unclassified. In-app destructive actions request confirmation.
- A persistent non-touchable bubble surface is reused during panel display. Dismissal blocks panel input and clipboard refresh, cross-fades without moving the panel, then restores bubble touchability.

## Permission guidance
Home and Settings expose special-access status and navigation. Overlay status uses canDrawOverlays; accessibility status uses the connected service; battery status uses isIgnoringBatteryOptimizations. OriginOS background/autostart status is explicitly manual, never inferred.
No irrelevant dangerous permissions are added to make the system permission page look populated.
Accessibility is optional and does not itself grant unrestricted background clipboard reads. Shizuku is not integrated in this release.

References:
- https://developer.android.com/training/permissions/requesting-special
- https://developer.android.com/about/versions/10/privacy/changes#clipboard-data

## Manual device acceptance (pending)
1. Tap the left, centre and right of a closed row: only copy; pinned state unchanged.
2. Double tap: pin toggles once; no copy/close. Long press: menu, no copy.
3. Reveal Delete; swipe right slowly/quickly, tap the front, wait, or touch another row; verify closure and no translucent button bleed.
4. Scroll vertically from a row and cancel a swipe; no copy or accidental deletion.
5. Rapidly open/close, close during appearance, dismiss outside and repeat in fixed mode; no twitch or reopen. Outside dismissal must not activate the underlying app.
6. Drag categories both directions, past multiple rows, near screen edges; release saves, cancelled gesture restores. Restart confirms order.
7. Deny/revoke overlay access, return from settings, test optional accessibility off/on and battery-setting fallback.
8. Overwrite 0.5.2 with the same signer; verify history/categories/preferences remain.
Build/lint evidence does not establish OriginOS visual acceptance.
