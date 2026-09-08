#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tools="$root/.toolchains"
downloads="$tools/downloads"
jdk="$tools/jdk17"
sdk="$tools/android-sdk"
mkdir -p "$downloads" "$sdk/platforms" "$sdk/build-tools"

jdk_url='https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.20.1_1.tar.gz'
jdk_sha256='457b57af8f9c93ec39080bb8c764f559dc8c89a6da1a39d718a400b7890d3e41'
platform_url='https://dl.google.com/android/repository/platform-35_r02.zip'
platform_sha1='0bb560a90a7a2cbd0dd8348224d518b638fe7949'
build_tools_url='https://dl.google.com/android/repository/build-tools_r35.0.1_linux.zip'
build_tools_sha1='e009a9b188cfeb1d2b4c318ab5cb4f1ddc368861'
arm_tools_url='https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-35.0.1/android-build-tools-35.0.1-linux-arm64-20260521.tar.xz'
arm_sha_url='https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-35.0.1/SHA256SUMS'

fetch() {
  local url=$1 file=$2
  [[ -f "$file" ]] || curl -fL --retry 3 --retry-delay 2 "$url" -o "$file"
}

if [[ ! -x "$jdk/bin/java" ]]; then
  archive="$downloads/jdk17.tar.gz"
  fetch "$jdk_url" "$archive"
  echo "$jdk_sha256  $archive" | sha256sum -c -
  rm -rf "$jdk" "$tools/jdk-extract"
  mkdir -p "$tools/jdk-extract"
  tar -xzf "$archive" -C "$tools/jdk-extract"
  extracted=$(find "$tools/jdk-extract" -mindepth 1 -maxdepth 1 -type d | head -1)
  mv "$extracted" "$jdk"
  rmdir "$tools/jdk-extract"
fi

export JAVA_HOME="$jdk"
export ANDROID_HOME="$sdk"
export ANDROID_SDK_ROOT="$sdk"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -f "$sdk/platforms/android-35/android.jar" ]]; then
  archive="$downloads/platform-35_r02.zip"
  fetch "$platform_url" "$archive"
  echo "$platform_sha1  $archive" | sha1sum -c -
  tmp="$tools/platform-extract"
  rm -rf "$tmp" "$sdk/platforms/android-35"
  mkdir -p "$tmp"
  unzip -q "$archive" -d "$tmp"
  android_jar=$(find "$tmp" -type f -name android.jar | head -1)
  [[ -n "$android_jar" ]] || { echo 'android.jar not found in platform archive' >&2; exit 71; }
  src=$(dirname "$android_jar")
  mv "$src" "$sdk/platforms/android-35"
  rm -rf "$tmp"
fi

bt="$sdk/build-tools/35.0.1"
if [[ ! -f "$bt/lib/d8.jar" && ! -f "$bt/lib/apksigner.jar" ]]; then
  archive="$downloads/build-tools_r35.0.1_linux.zip"
  fetch "$build_tools_url" "$archive"
  echo "$build_tools_sha1  $archive" | sha1sum -c -
  tmp="$tools/build-tools-extract"
  rm -rf "$tmp" "$bt"
  mkdir -p "$tmp"
  unzip -q "$archive" -d "$tmp"
  marker=$(find "$tmp" -type f -name aapt2 | head -1)
  [[ -n "$marker" ]] || { echo 'aapt2 not found in build-tools archive' >&2; exit 72; }
  src=$(dirname "$marker")
  mv "$src" "$bt"
  rm -rf "$tmp"
fi

arm_archive="$downloads/android-build-tools-35.0.1-linux-arm64.tar.xz"
arm_sums="$downloads/android-build-tools-35.0.1-SHA256SUMS"
fetch "$arm_tools_url" "$arm_archive"
fetch "$arm_sha_url" "$arm_sums"
expected=$(awk '$2 ~ /android-build-tools-35\.0\.1-linux-arm64-20260521\.tar\.xz$/ {print $1}' "$arm_sums" | head -1)
if [[ -n "$expected" ]]; then
  echo "$expected  $arm_archive" | sha256sum -c -
fi

arm_extract="$tools/arm-build-tools-35.0.1"
rm -rf "$arm_extract"
mkdir -p "$arm_extract"
tar -xJf "$arm_archive" -C "$arm_extract"

for name in aapt2 aidl zipalign split-select; do
  candidate=$(find "$arm_extract" -type f -name "$name" | head -1 || true)
  if [[ -n "$candidate" ]]; then
    cp "$candidate" "$bt/$name"
    chmod +x "$bt/$name"
  fi
done

"$JAVA_HOME/bin/java" -version
"$bt/aapt2" version
printf 'JAVA_HOME=%s\nANDROID_SDK_ROOT=%s\nAAPT2=%s\n' "$JAVA_HOME" "$ANDROID_SDK_ROOT" "$bt/aapt2"
