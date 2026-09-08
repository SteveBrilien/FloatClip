#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
label=${1:-snapshot}
out_root=${2:-analysis/originos}
ts=$(date +%Y%m%d-%H%M%S)
out="$out_root/${ts}-${label//[^a-zA-Z0-9._-]/_}"
mkdir -p "$out"

if [[ -n "${ADB:-}" ]]; then
  adb_bin=$ADB
elif [[ -x "$script_dir/adb-arm64.sh" ]]; then
  adb_bin="$script_dir/adb-arm64.sh"
elif command -v adb >/dev/null 2>&1; then
  adb_bin=$(command -v adb)
elif [[ -x /home/orangepi/.local/share/android-sdk/platform-tools/adb ]]; then
  adb_bin=/home/orangepi/.local/share/android-sdk/platform-tools/adb
else
  echo "adb not found; set ADB=/path/to/adb" >&2
  exit 69
fi

"$adb_bin" start-server >/dev/null
state=$("$adb_bin" get-state 2>/dev/null || true)
if [[ "$state" != "device" ]]; then
  echo "No authorized Android device. adb state: ${state:-none}" >&2
  "$adb_bin" devices -l >&2 || true
  exit 70
fi

run() {
  local name=$1
  shift
  printf '[snapshot] %s\n' "$name"
  "$adb_bin" shell "$@" >"$out/$name.txt" 2>&1 || true
}

"$adb_bin" devices -l >"$out/adb-devices.txt"
run getprop getprop
run build-fingerprint getprop ro.build.fingerprint
run build-display getprop ro.build.display.id
run android-release getprop ro.build.version.release
run android-sdk getprop ro.build.version.sdk
run vivo-os-version getprop ro.vivo.os.version
run vivo-product-version getprop ro.vivo.product.version
run wm-size wm size
run wm-density wm density
run window-windows dumpsys window windows
run window-displays dumpsys window displays
run activity-top dumpsys activity top
run activity-activities dumpsys activity activities
run surface-list dumpsys SurfaceFlinger --list
run systemui-package dumpsys package com.android.systemui
run systemui-path pm path com.android.systemui
run packages pm list packages -f
run processes ps -A
run overlays cmd overlay list
run settings-system settings list system
run settings-secure settings list secure
run settings-global settings list global
run input-method dumpsys input_method
run accessibility dumpsys accessibility

# Compact helper views for fast diffs.
grep -Ei 'mCurrentFocus|mFocusedApp|Window\{|type=|package=|vivo|systemui|float|freeform|mini|small' \
  "$out/window-windows.txt" >"$out/window-interesting.txt" || true
grep -Ei 'vivo|origin|theme|systemui|float|freeform|mini|small' \
  "$out/packages.txt" >"$out/packages-interesting.txt" || true
grep -Ei 'vivo|systemui|float|freeform|mini|small' \
  "$out/processes.txt" >"$out/processes-interesting.txt" || true

cat >"$out/METADATA.txt" <<META
captured_at=$ts
label=$label
adb=$adb_bin
state=$state
META

printf '%s\n' "$out"
