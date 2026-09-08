#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <baseline-dir> <floating-window-dir>" >&2
  exit 64
fi

base=$1
float=$2
for d in "$base" "$float"; do
  [[ -d "$d" ]] || { echo "not a directory: $d" >&2; exit 66; }
done

for name in window-interesting surface-list activity-top processes-interesting overlays settings-system settings-secure; do
  a="$base/$name.txt"
  b="$float/$name.txt"
  [[ -f "$a" && -f "$b" ]] || continue
  printf '\n===== %s =====\n' "$name"
  diff -u "$a" "$b" || true
done
