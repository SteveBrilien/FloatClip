#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
bash "$root/scripts/create-nearby-debug-key.sh"
exec "$root/scripts/build-orangepi.sh" "$@"
