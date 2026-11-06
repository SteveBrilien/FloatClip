# Android build43 — classic settings and borderless entries
2026-11-06

User requested removal of the remaining white/gray entry hairlines and restoration of the settings layout from before adjustable animation curves.

- Restored the appearance section/control structure from the parent of e61fd00 (the last settings revision before the curve and range editors): theme colors, image columns, floating-ball size/opacity/sensitivity/edge hiding, panel position/opacity/card opacity/dimensions, text lines and conventional press/timing sliders.
- Restored independent device-color and tinted-card controls plus the older explicit sharing/recovery/notification choices. The restored controls use real preferences; the list uses the selected image column count.
- Removed the unconditional stroke from the shared text/image card. Both app history and floating history use this shared card. Legacy border settings cannot restore entry outlines.
- Kept the current image decoder/cache, resize gestures, service lifecycle, safe manual migration and dedicated About/permission/license pages. The original app identity and update public key remain unchanged.
- Curve and interaction-range editors are absent. The fixed default curve is retained; one-time migration removes hidden legacy customizations without changing service consent, history or pairing.
- Android 11 does not show a nonfunctional real-blur slider. Supported systems use the existing blur capability check and connect the restored strength control to the blur radius.
- Android build43; Windows stays on the independently verified build42.

Validation results and device installation must be added after the build and actual checks.
