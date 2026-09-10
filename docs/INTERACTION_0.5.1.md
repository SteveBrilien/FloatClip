# FloatClip 0.5.1 interaction / persistence refinement

## Scope

0.5.1 is a device-feedback patch over 0.5.0. The goal is continuity: outside dismissal must not alter perceived screen brightness, panel/bubble transitions must not introduce a blank or bright frame, and reopening the clipboard must not move the floating ball to an older docking position.

## Outside-touch layer

Normal mode still uses a full-screen `TYPE_APPLICATION_OVERLAY` touch catcher so an outside tap is consumed before the panel collapses. The catcher is now visually transparent. It no longer paints a dim color because an application overlay cannot reproduce the same dimming across OriginOS status/navigation system regions, which produced a visible luminance boundary and a fast flash during removal.

Fixed mode changes only the touchability flag of that transparent catcher. It does not animate or recreate the panel window.

## Panel header and dismissal

The clipboard title is vertically centered. The redundant right-side collapse chevron is removed; the pin action remains. The removed icon resource is deleted as well.

Panel dismissal is 380 ms with only a small scale/translation amplitude. The bubble begins its fade-in after 130 ms and takes 240 ms, so both surfaces overlap during the handoff rather than exposing an empty frame. The transparent catcher has no visual animation, eliminating scrim luminance flicker.

## Floating-ball position continuity

The logical dock state remains `side + y`, not the partially hidden x coordinate. 0.5.1 persists this state twice:

1. immediately when the finger is released; and
2. immediately before panel expansion, using the current physical window coordinates.

The spring-settle callback still writes the final state. This means a user can interrupt an in-progress fling/spring by opening the panel without losing the newest position.

## Motion sensitivity

Settings exposes 30–120% motion sensitivity, default 65%. Direct dragging remains 1:1 with the finger. Sensitivity changes only post-release physics: lower values reduce launch velocity and increase `OverScroller` friction; higher values retain more throw velocity and use less friction. Edge settling remains a damped spring after inertial travel completes.

## Keep-alive behavior

FloatClip records whether the user explicitly enabled the overlay. When `后台常驻` is enabled, normal task removal schedules a short best-effort restart. `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` also restore the foreground service when the overlay was previously enabled and overlay permission still exists. Explicit Stop clears the desired-running flag before stopping the service, so recovery is not scheduled.

Android/OEM force-stop remains a hard boundary: after the OS marks an application force-stopped, normal alarms/broadcasts cannot reliably resurrect it until the user launches the app again. The Settings page therefore links to the system app-details/background policy screen for OriginOS-specific allowlisting.

## Theme synchronization

System `Configuration.uiMode` is authoritative in `SYSTEM` mode. OriginOS semantic background colors are accepted only when their luminance direction agrees with the current requested light/dark mode. OEM resources are resolved again against the current configuration rather than holding one `Resources` object forever. The service reacts to configuration changes and performs a screen-on consistency check.

## Ordered categories

Category persistence migrated from an unordered `StringSet` to an ordered JSON list. Existing set-based data is migrated using the previously visible sorted order. The app exposes up/down controls and the resulting order is shared by the history selector and floating-panel category strip.

## Device acceptance checklist

1. Open the panel over both a light and dark underlying app: status bar, navigation region and uncovered app content must not change brightness solely because the panel opened.
2. Tap an outside blank area repeatedly: collapse must have no fast light/dark flash and must not activate the app underneath.
3. Fixed/unfixed toggles must not flash the whole panel.
4. The title baseline/vertical centering should look balanced; no right-side collapse chevron should be present.
5. Move the ball to a new Y, open the panel before and after it fully settles, close the panel, and verify the ball returns to the same logical side/Y.
6. Compare 30%, 65% and 120% sensitivity with similar throws; post-release travel should clearly differ while direct drag remains unchanged.
7. Remove FloatClip from recents/background cleanup and verify the best-effort restart path; separately document any OriginOS force-stop/autostart policy that still blocks it.
8. Switch system light/dark mode with FloatClip active and verify the overlay returns to the correct theme in both directions.
9. Reorder categories in the app and verify the same order appears in the floating panel.
