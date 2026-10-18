#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export DOTNET_ROOT="$root/.toolchains/nearby-dotnet"
export DOTNET_CLI_HOME="$root/.toolchains/nearby-dotnet-home"
export NUGET_PACKAGES="$root/.toolchains/nearby-nuget"
export DOTNET_CLI_TELEMETRY_OPTOUT=1
export DOTNET_NOLOGO=1
cd "$root"
publish="$root/analysis/nearby-p0/windows-publish"
"$DOTNET_ROOT/dotnet" publish windows/FloatClip.NearbyProbe -c Release -r win-x64 \
  --self-contained true -p:PublishSingleFile=true -p:DebugType=None -o "$publish"
python3 - <<'PY'
from pathlib import Path
import hashlib,json,zipfile
root=Path.cwd()
dest=root/'analysis/nearby-p0/FloatClip-NearbyProbe-Windows-P0.zip'
with zipfile.ZipFile(dest,'w',compression=zipfile.ZIP_DEFLATED) as z:
    for p in (root/'analysis/nearby-p0/windows-publish').iterdir():
        if p.is_file(): z.write(p,'FloatClip-NearbyProbe/'+p.name)
    for name in ['Test-LanProbe.ps1','Start-NearbyProbe.ps1','Start-NearbyProbe.cmd','README.md','README.zh-CN.txt','FloatClip.NearbyProbe/Probe.cs','FloatClip.NearbyProbe/LanProbe.cs']:
        z.write(root/'windows'/name,'FloatClip-NearbyProbe/'+name)
print(json.dumps({'file':dest.name,'bytes':dest.stat().st_size,'sha256':hashlib.sha256(dest.read_bytes()).hexdigest()}))
PY
