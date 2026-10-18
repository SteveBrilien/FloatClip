#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
out=analysis/share-p1/core-checks/classes
mkdir -p "$out"
.toolchains/jdk17/bin/javac -encoding UTF-8 -cp ".toolchains/android-sdk/platforms/android-35/android.jar:$out" -d "$out" nearby-probe/src/main/java/com/floatclip/probe/LanTls.java nearby-probe/src/main/java/com/floatclip/probe/LanPair.java tests/nearby/LanPairChecks.java
.toolchains/jdk17/bin/java -cp "$out:.toolchains/android-sdk/platforms/android-35/android.jar" com.floatclip.probe.LanPairChecks
