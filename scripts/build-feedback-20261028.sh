#!/usr/bin/env bash
set -euo pipefail
dotnet_bin="$DOTNET_ROOT/dotnet"
out=analysis/feedback-20261028
mkdir -p "$out" analysis/deliveries
"$dotnet_bin" run --project windows/FloatClip.UpdateChecks -c Release
"$dotnet_bin" build windows/FloatClip.DesktopChecks -c Release -p:EnableWindowsTargeting=true
python3 scripts/check-share-contract.py
"$dotnet_bin" publish windows/FloatClip.Desktop -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:DebugType=None -o "$out/windows"
python3 - <<'PY'
from pathlib import Path
import zipfile,hashlib,json
out=Path('analysis/deliveries/FloatClip-Windows-0.7.9-build35-candidate.zip')
assert not out.exists(), 'Keep immutable candidate artifacts'
with zipfile.ZipFile(out,'x',zipfile.ZIP_DEFLATED,compresslevel=7) as z:
 for p in Path('analysis/feedback-20261028/windows').rglob('*'):
  if p.is_file() and p.suffix!='.pdb':z.write(p,'FloatClip/'+p.relative_to('analysis/feedback-20261028/windows').as_posix())
 with zipfile.ZipFile('analysis/deliveries/FloatClip-Windows-0.7.9-build34-candidate.zip') as previous:
  for name in previous.namelist():
   if name.startswith('FloatClip/licenses/'):z.writestr(name,previous.read(name))
 z.writestr('FloatClip/README.txt','FloatClip 0.7.9 build35 candidate / Windows x64\n完整解压后运行 FloatClip.exe。\n更新状态同时显示版本与build。\n保持build34的背景即时切换与紧凑设置布局。\n本轮Remote离线，未完成Windows原生视觉复验。\n正式更新feed未改动；保留既有历史、配对与旧制品。\n')
with zipfile.ZipFile(out) as z:
 assert z.testzip() is None
 assert len([n for n in z.namelist() if n.startswith('FloatClip/licenses/')])==9
report={'file':out.name,'build':35,'bytes':out.stat().st_size,'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'windowsNativeUiVerifiedThisRound':False}
Path('analysis/feedback-20261028/windows-package.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report))
PY
