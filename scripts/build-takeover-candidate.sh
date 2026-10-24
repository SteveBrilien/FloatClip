#!/usr/bin/env bash
set -euo pipefail
dotnet_bin="$DOTNET_ROOT/dotnet"
out=analysis/takeover-build34
mkdir -p "$out" analysis/deliveries
if [[ "${1-}" != "--package-only" ]]; then
"$dotnet_bin" --version
"$dotnet_bin" build windows/FloatClip.Desktop -c Release -p:EnableWindowsTargeting=true
"$dotnet_bin" build windows/FloatClip.DesktopChecks -c Release -p:EnableWindowsTargeting=true
"$dotnet_bin" run --project windows/FloatClip.LibraryChecks -c Release
bash scripts/test-share-core.sh
"$dotnet_bin" run --project windows/FloatClip.SyncChecks -c Release -- analysis/share-p1/core-checks/vectors
"$dotnet_bin" run --project windows/FloatClip.UpdateChecks -c Release
fi
python3 scripts/check-share-contract.py
"$dotnet_bin" publish windows/FloatClip.Desktop -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:DebugType=None -o "$out/windows"
python3 - <<'PY'
from pathlib import Path
import zipfile,hashlib,json
out=Path('analysis/deliveries/FloatClip-Windows-0.7.9-build34-candidate.zip')
assert not out.exists(), 'Keep immutable candidate artifacts'
with zipfile.ZipFile(out,'x',zipfile.ZIP_DEFLATED,compresslevel=7) as z:
 for p in Path('analysis/takeover-build34/windows').rglob('*'):
  if p.is_file() and p.suffix!='.pdb':z.write(p,'FloatClip/'+p.relative_to('analysis/takeover-build34/windows').as_posix())
 with zipfile.ZipFile('analysis/deliveries/FloatClip-Windows-0.7.9-build29.zip') as previous:
  for name in previous.namelist():
   if name.startswith('FloatClip/licenses/'):z.writestr(name,previous.read(name))
 z.writestr('FloatClip/README.txt','FloatClip 0.7.9 build34 candidate / Windows x64\n完整解压后运行 FloatClip.exe。\n设置→应用背景→整窗透明。背景模式即时切换，后台服务与数据保持。\n整窗模式让文字、图标和图片一起变淡。刷新与主题切换保留外部工具的透明度。\n设置文字与卡片布局更紧凑。\n包含前一会话的文件续接、蓝牙目录与直接发送修复。\nLinux 交叉编译候选：Windows/PowerToys 实机、四档物理DPI、蓝牙/LAN真实往返待验收。\n保留既有配对、历史和旧版本制品。\n')
with zipfile.ZipFile(out) as z:
 assert z.testzip() is None
 assert len([n for n in z.namelist() if n.startswith('FloatClip/licenses/')])==9
report={'file':out.name,'build':34,'bytes':out.stat().st_size,'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'windowsNativeUiVerified':False,'powerToysVerified':False}
Path('analysis/takeover-build34/windows-package.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report))
PY
