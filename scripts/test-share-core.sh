#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
out=analysis/share-p1/core-checks
mkdir -p "$out/classes" "$out/vectors"
.toolchains/jdk17/bin/javac -d "$out/classes" share-core/src/main/java/com/floatclip/share/*.java share-core/src/test/java/com/floatclip/share/*.java
.toolchains/jdk17/bin/java -cp "$out/classes" com.floatclip.share.CoreChecks "$out/vectors"
.toolchains/jdk17/bin/java -cp "$out/classes" com.floatclip.share.ClipboardSyncChecks "$out/vectors"

.toolchains/jdk17/bin/java -cp "$out/classes" com.floatclip.share.DiscoverySocketChecks
