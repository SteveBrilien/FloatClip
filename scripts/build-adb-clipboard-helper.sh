#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
out=analysis/adb-capture
mkdir -p "$out/classes" "$out/dex"
sdk=.toolchains/android-sdk
.toolchains/jdk17/bin/javac -source 17 -target 17 -cp "$sdk/platforms/android-35/android.jar" -d "$out/classes" app/src/main/java/com/floatclip/app/capture/AdbClipboardHelper.java share-core/src/main/java/com/floatclip/share/*.java
.toolchains/jdk17/bin/java -cp "$sdk/build-tools/35.0.1/lib/d8.jar" com.android.tools.r8.D8 --min-api 30 --lib "$sdk/platforms/android-35/android.jar" --output "$out/dex" "$out"/classes/com/floatclip/app/capture/*.class "$out"/classes/com/floatclip/share/*.class
python3 - <<'PY'
from zipfile import ZipFile,ZIP_DEFLATED
with ZipFile('analysis/adb-capture/floatclip-capture.jar','w',ZIP_DEFLATED) as archive:
    archive.write('analysis/adb-capture/dex/classes.dex','classes.dex')
PY
