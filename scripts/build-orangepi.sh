#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
java_home=${JAVA_HOME:-/home/orangepi/.local/opt/openjdk-17}
android_sdk=${ANDROID_SDK_ROOT:-$project_root/.toolchains/android-sdk}
aapt2_override=${AAPT2_OVERRIDE:-$android_sdk/build-tools/35.0.1/aapt2}

export JAVA_HOME="$java_home"
export ANDROID_HOME="$android_sdk"
export ANDROID_SDK_ROOT="$android_sdk"
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$project_root/.toolchains/gradle-home}
export PATH="$java_home/bin:$PATH"
export LD_LIBRARY_PATH="$android_sdk/build-tools/35.0.1/lib64:$android_sdk/build-tools/35.0.1/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

for required in "$java_home/bin/java" "$project_root/gradlew" "$android_sdk/platforms/android-35/android.jar" "$android_sdk/build-tools/35.0.1/aapt" "$aapt2_override" "$android_sdk/build-tools/35.0.1/zipalign"; do
  [[ -e "$required" ]] || { echo "[build] missing required component: $required" >&2; exit 69; }
done

# AGP probes platform-tools package metadata even though assembling the APK does not
# invoke adb. Reuse the workspace-local arm64 platform-tools package when available
# so Gradle does not stall trying to resolve Google's x86_64 package remotely.
portable_platform_tools="$project_root/.toolchains/adb-arm64/root/usr/lib/android-sdk/platform-tools"
if [[ ! -e "$android_sdk/platform-tools" && -f "$portable_platform_tools/source.properties" ]]; then
  ln -s "$portable_platform_tools" "$android_sdk/platform-tools"
fi

printf 'sdk.dir=%s\n' "$android_sdk" > "$project_root/local.properties"

if [[ $# -eq 0 ]]; then
  set -- :app:assembleDebug
fi

gradle_exec=${GRADLE_EXECUTABLE:-}
if [[ -z "$gradle_exec" ]]; then
  gradle_exec=$(find "$project_root/.toolchains/gradle-home/wrapper/dists/gradle-8.11.1-bin" -type f -path '*/gradle-8.11.1/bin/gradle' -print -quit 2>/dev/null || true)
fi
if [[ -z "$gradle_exec" ]]; then
  gradle_exec="$project_root/gradlew"
fi

echo "[build] gradle=$gradle_exec"
echo "[build] java=$JAVA_HOME"
echo "[build] sdk=$ANDROID_SDK_ROOT"
echo "[build] aapt2=$aapt2_override"

cd "$project_root"
exec "$gradle_exec" \
  --no-daemon \
  --max-workers=3 \
  --console=plain \
  "-Pandroid.aapt2FromMavenOverride=$aapt2_override" \
  "$@"
