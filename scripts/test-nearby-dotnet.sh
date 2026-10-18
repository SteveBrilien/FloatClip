#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export DOTNET_ROOT="$root/.toolchains/nearby-dotnet"
export DOTNET_CLI_HOME="$root/.toolchains/nearby-dotnet-home"
export NUGET_PACKAGES="$root/.toolchains/nearby-nuget"
export DOTNET_CLI_TELEMETRY_OPTOUT=1
export DOTNET_NOLOGO=1
cd "$root"
"$DOTNET_ROOT/dotnet" run --project windows/FloatClip.NearbyProbe -c Release -- \
  --self-test analysis/nearby-p0/wire-tests/vectors
