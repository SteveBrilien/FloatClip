#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
out=${1:-analysis/originos/system-apks}
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

pull_package() {
  local pkg=$1
  local pkg_dir="$out/$pkg"
  mkdir -p "$pkg_dir"
  mapfile -t paths < <("$adb_bin" shell pm path "$pkg" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p')
  if [[ ${#paths[@]} -eq 0 ]]; then
    rmdir "$pkg_dir" 2>/dev/null || true
    return 0
  fi
  printf '%s\n' "${paths[@]}" >"$pkg_dir/device-paths.txt"
  local i=0
  for path in "${paths[@]}"; do
    i=$((i+1))
    local base
    base=$(basename "$path")
    "$adb_bin" pull "$path" "$pkg_dir/${i}-${base}" >/dev/null
  done
  (cd "$pkg_dir" && sha256sum ./*.apk 2>/dev/null || true) >"$pkg_dir/SHA256SUMS.txt"
  echo "pulled $pkg"
}

# Confirmed on the target PD2115 / Android 11 ROM by WindowManager and
# SurfaceFlinger snapshots. Keep these explicit so analysis is deterministic
# even when vivo package naming differs from generic keyword discovery.
for pkg in \
  com.vivo.floatingball \
  com.bbk.theme.resources \
  com.bbk.theme \
  com.vivo.upslide \
  com.vivo.globalanimation \
  com.android.systemui; do
  pull_package "$pkg"
done

# Candidate vivo packages are discovered conservatively; pulling is read-only.
mapfile -t candidates < <("$adb_bin" shell pm list packages 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | grep -Ei 'vivo.*(systemui|float|freeform|window|theme)|origin.*(systemui|float|window|theme)' | head -40 || true)
for pkg in "${candidates[@]}"; do
  [[ "$pkg" == "com.android.systemui" ]] && continue
  pull_package "$pkg"
done

printf '%s\n' "$out"
