#!/usr/bin/env bash
set -euo pipefail
project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
root="$project_root/.toolchains/adb-arm64/root"
adb_bin="$root/usr/lib/android-sdk/platform-tools/adb"
lib_dir="$root/usr/lib/aarch64-linux-gnu/android"
[[ -x "$adb_bin" ]] || { echo "ARM64 adb is not bootstrapped: $adb_bin" >&2; exit 69; }
[[ -d "$lib_dir" ]] || { echo "ARM64 adb libraries are missing: $lib_dir" >&2; exit 69; }
export LD_LIBRARY_PATH="$lib_dir${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$adb_bin" "$@"
