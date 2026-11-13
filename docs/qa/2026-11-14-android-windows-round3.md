# FloatClip build55 round 3 — Android / Windows

Source base: 0.7.9 build54 (`b9874132`), verified on vivo V2115A PD2115. Separate worktree.

- Android image: initialize to FIT, correct viewport from `BitmapRegionDecoder` original dimensions once actually loaded. Clamp stale-metadata region requests.
- Android file destination: one optional persisted SAF tree URI, freely editable from Files page or management menu. Only save after a persisted write grant; no broad storage permissions and no silent fallback if grant revoked.
- Android full-text: same immersive overlay/back action design as image viewer, selectable text, per-entry navigation, copy, pinned/locked actions; keep floating panel memory/scroll while reading.
- Android themes: new explicit Klein blue `#0F2254FF` RGBA (hex navy `#0F2254`, alpha `FF`), with independent app/floating-layer selection; shared file-dialog and file list palette, selected tab background nearly opaque in dark themes.
- Android bottom tab: no dynamic left spacer; later tabs center by scroll position.
- Android migration backup: labeled PIN input groups, spacing and copy aligned with dialog system, validates six-digit PIN and requires verification before merge.
- Windows update: no persistent 'cannot check updates' clipboard banner when network/GitHub cannot be reached; a manual button remains.
- Windows files: drag-drop files into shelf using existing store validation, WPF resizable preview pane, Explorer shell thumbnail providers for Office documents with embedded PPTX thumbnail fallback.
- Windows image sync: repeated copying of the same image now refreshes its event ID/ordering while preserving the existing image blob and pinned/locked/category flags. Network retries with the exact same event ID remain idempotent; native Windows CI discovered this regression.
- Windows signing: **no self-signed trust bypass**. Signed binaries require a legitimate Authenticode signing identity and Windows trust; no change to security policy or SmartScreen settings.

## QA gaps

Windows machine Remote is offline. Cross-compiled WPF and .NET isolated checks are not a substitute for on-Windows visual QA. Shell thumbnails depend on installed thumbnail handlers; it is first-page visual preview, not a full Office slide renderer. No Office installation or screenshot was accessed. Android content migration and SAF selection should be UI-tested on device before considering all OEM interactions complete. No data was removed or migrated.

## Release constraints

Only publish artifacts that match hashes and APK signing identity in `updates/android-identity.json`. Do not swap an unsigned or debug-generated signer. Signed in-app update manifest must be reissued with authorized RSA key; never bypass its verification. CI release workflows currently read historic signed manifests, not automatically generated from this build.
