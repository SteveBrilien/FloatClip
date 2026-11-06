# Build42 takeover and device verification — 2026-11-06

## Completed this session

- Reused the existing immutable build42 packages and the committed source checkpoint fcdcecc. No application binaries were rebuilt or replaced under an existing build number.
- Android installed over the existing com.floatclip.probe package through the recovered Azure ADB tunnel. Package Manager reported versionCode 42 / versionName 0.7.9, firstInstallTime 2026-10-18 19:34:15 and lastUpdateTime 2026-11-06 19:40:27 (Asia/Shanghai).
- Confirmed that the shared clipboard foreground service, accessibility service, and accessibility-hosted floating overlay were running after installation. Existing protected/pinned rows remained visible.
- Windows downloaded through the existing SSH route after an HTTPS transfer failed; SHA-256 matched the frozen ZIP. The build41 GUI thread exited through WM_QUIT, allowing its application loop and host disposal to finish. Build42 is running from D:\\FloatClip\\0.7.9-build42\\FloatClip\\FloatClip.exe.
- Executed the packaged Windows DesktopChecks on the physical computer: 242 passed, uiFailures 0, isolated true, userClipboardUntouched true, report UTC 2026-11-06T11:41:59.8561787Z. This supplements the previous 93 library checks, 47 update checks and Android 118-test/lint pass.
- Read the Android settings, permissions and about screens on the physical phone. The About screen displayed 0.7.9 build42. Reviewed Light/Dark isolated WPF renderings at the current machine's 150% system DPI; these do not establish four physical DPI or DWM acceptance.
- Signed the build42 update manifests on the original Windows computer. The private release key stayed there. Independently verified RS256 for both channels against the existing pinned public key, and checked both package sizes and SHA-256 before publication.
- Updated release metadata in an isolated Git worktree, preserving all unrelated dirty files in the original and cloud development workspaces.

## Boundaries

Actual image viewer paging/zoom, app-specific image paste, 60/120Hz motion, task-clear recovery, sustained background duration and battery use remain unverified. The ADB tunnel became intermittent during the image-viewer check; that timed-out attempt is not counted as a pass. No force-stop or data clear was used to simulate task removal. No claim of bug-free or all-day service is made.

The original build42 image implementation, settings decisions, package hashes and prior checks remain in [the build42 record](IMAGE_SETTINGS_BUILD42_2026-11-06.md). Public update and GitHub publication status must be checked against the live signed endpoints and workflow results; preparation/signature verification alone is not publication evidence.
