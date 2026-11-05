#!/usr/bin/env bash
set -euo pipefail
DOTNET=/home/codex/workspace/Android/FloatClip/.toolchains/nearby-dotnet/dotnet
export DOTNET_CLI_HOME=/home/codex/workspace/Android/FloatClip/.toolchains/nearby-dotnet-home
export NUGET_PACKAGES=/home/codex/workspace/Android/FloatClip/.toolchains/nearby-nuget
export DOTNET_CLI_TELEMETRY_OPTOUT=1
out=analysis/build42
mkdir -p "$out" analysis/deliveries
"$DOTNET" run --project windows/FloatClip.LibraryChecks -c Release
"$DOTNET" run --project windows/FloatClip.UpdateChecks -c Release
"$DOTNET" build windows/FloatClip.DesktopChecks -c Release -p:EnableWindowsTargeting=true
"$DOTNET" publish windows/FloatClip.Desktop -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:DebugType=None -o "$out/windows"
"$DOTNET" publish windows/FloatClip.DesktopChecks -c Release -r win-x64 --self-contained false -p:EnableWindowsTargeting=true -p:DebugType=None -o "$out/checks"
python3 - <<'PY'
from pathlib import Path
import zipfile,hashlib,json
base=Path('analysis/build42')
out=Path('analysis/deliveries/FloatClip-Windows-0.7.9-build42-candidate.zip')
checks=Path('analysis/deliveries/FloatClip-Windows-0.7.9-build42-checks.zip')
for target,directory,prefix in [(out,base/'windows','FloatClip'),(checks,base/'checks','Checks')]:
 with zipfile.ZipFile(target,'x',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
  for p in directory.rglob('*'):
   if p.is_file() and p.suffix!='.pdb':z.write(p,prefix+'/'+p.relative_to(directory).as_posix())
  if target==out:
   with zipfile.ZipFile('analysis/deliveries/FloatClip-Windows-0.7.9-build40-photo-candidate.zip') as old:
    for name in old.namelist():
     if name.startswith('FloatClip/licenses/'):z.writestr(name,old.read(name))
   z.writestr('FloatClip/README.txt','FloatClip 0.7.9 build42 candidate / Windows x64\n恢复 build40 配色与外观设置，原生标题按钮，保留 build41 加载优化。\n完整解压后运行 FloatClip.exe。候选未修改正式更新源。\n')
 with zipfile.ZipFile(target) as z:assert z.testzip() is None
 print(json.dumps({'file':target.name,'bytes':target.stat().st_size,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()}))
PY
