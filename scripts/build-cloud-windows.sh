#!/usr/bin/env bash
set -euo pipefail
dotnet_bin="$DOTNET_ROOT/dotnet"
mkdir -p analysis/build26
"$dotnet_bin" --version
"$dotnet_bin" build windows/FloatClip.Desktop -c Release -p:EnableWindowsTargeting=true
"$dotnet_bin" build windows/FloatClip.DesktopChecks -c Release -p:EnableWindowsTargeting=true
"$dotnet_bin" run --project windows/FloatClip.LibraryChecks -c Release
bash scripts/test-share-core.sh
"$dotnet_bin" run --project windows/FloatClip.SyncChecks -c Release -- analysis/share-p1/core-checks/vectors
"$dotnet_bin" run --project windows/FloatClip.UpdateChecks -c Release
"$dotnet_bin" publish windows/FloatClip.Desktop -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:DebugType=None -o analysis/build26/windows
python3 - <<'PY'
from pathlib import Path
import zipfile,hashlib,json
out=Path('analysis/deliveries/FloatClip-Windows-0.7.8-build26.zip')
assert not out.exists(), 'Keep immutable candidate artifacts'
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=7) as z:
 for p in Path('analysis/build26/windows').rglob('*'):
  if p.is_file() and p.suffix!='.pdb':z.write(p,'FloatClip/'+p.relative_to('analysis/build26/windows').as_posix())
 with zipfile.ZipFile('analysis/deliveries/FloatClip-Windows-0.7.8-build25.zip') as previous:
  for name in previous.namelist():
   if name.startswith('FloatClip/licenses/'):z.writestr(name,previous.read(name))
 z.writestr('FloatClip/README.txt','FloatClip 0.7.8 build 26 candidate / Windows x64\n完整解压后运行 FloatClip.exe。保留旧版本制品。\n统一应用背景、分类编辑/删除/图标/颜色；原数据与配对协议保留。\n透明背景类型需重启应用。删除分组仅将记录移回未分类。\nLinux 交叉编译；Windows UI、DWM、真实 DPI 与手机传输仍待真机验收。\n')
with zipfile.ZipFile(out) as z:assert z.testzip() is None
report={'file':out.name,'build':26,'bytes':out.stat().st_size,'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'windowsNativeUiVerified':False}
Path('analysis/build26/windows-package.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report))
PY
