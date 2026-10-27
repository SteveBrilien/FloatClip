#!/usr/bin/env bash
set -euo pipefail
'scripts/build-orangepi.sh' ':app:assembleDebug' ':app:testDebugUnitTest' ':app:lintDebug' '-Pandroid.useAndroidX=true' '-Dorg.gradle.jvmargs=-Xmx3072m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8' '--offline'
python3 scripts/package-recovery-android33.py
