#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tools="$root/.toolchains"
export JAVA_HOME="$tools/jdk17"
export ANDROID_HOME="$tools/android-sdk"
export ANDROID_SDK_ROOT="$tools/android-sdk"
export GRADLE_USER_HOME="$tools/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"
aapt2="$ANDROID_SDK_ROOT/build-tools/35.0.1/aapt2"
truststore="$tools/java-cacerts"

for required in "$JAVA_HOME/bin/java" "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" "$aapt2"; do
  [[ -e "$required" ]] || { echo "[portable-build] missing $required; run scripts/bootstrap-portable-toolchain.sh first" >&2; exit 69; }
done
if [[ -f "$truststore" ]]; then
  export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$truststore -Djavax.net.ssl.trustStorePassword=changeit${JAVA_TOOL_OPTIONS:+ $JAVA_TOOL_OPTIONS}"
fi

printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > "$root/local.properties"
cd "$root"
if [[ $# -eq 0 ]]; then set -- :app:assembleDebug; fi
exec ./gradlew --no-daemon --max-workers=1 --console=plain \
  "-Pandroid.aapt2FromMavenOverride=$aapt2" "$@"
