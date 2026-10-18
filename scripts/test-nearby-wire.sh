#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
java_home=${JAVA_HOME:-$root/.toolchains/jdk17}
out="$root/analysis/nearby-p0/wire-tests"
mkdir -p "$out/classes"
"$java_home/bin/javac" -encoding UTF-8 -d "$out/classes" \
  "$root/nearby-probe/src/main/java/com/floatclip/probe/ProbeWire.java" \
  "$root/tests/nearby/ProbeWireTest.java"
"$java_home/bin/java" -cp "$out/classes" ProbeWireTest "$out/vectors"
