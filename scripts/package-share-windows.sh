#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export DOTNET_ROOT="$root/.toolchains/nearby-dotnet"
export DOTNET_CLI_HOME="$root/.toolchains/nearby-dotnet-home"
export NUGET_PACKAGES="$root/.toolchains/nearby-nuget"
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
cd "$root"
bash scripts/test-share-core.sh
"$DOTNET_ROOT/dotnet" run --project windows/FloatClip.ShareChecks -- analysis/share-p1/core-checks/vectors
"$DOTNET_ROOT/dotnet" publish windows/FloatClip.Desktop -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:DebugType=None -o analysis/share-p1/windows-publish
python3 - <<'PY'
from pathlib import Path
import zipfile,hashlib,json
out=Path('analysis/share-p1/FloatClip-Share-Windows-0.3.0.zip')
with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_DEFLATED) as z:
 for p in Path('analysis/share-p1/windows-publish').iterdir():
  if p.is_file(): z.write(p,'FloatClip/'+p.name)
 z.writestr('FloatClip/README.txt','FloatClip Share 0.3.0 预览版\n\n双击 FloatClip.exe。手机打开 FloatClip 并允许连接；Windows 设备页查找手机。首次核对识别码，确认后记住设备。\n蓝白界面；设置中选择跟随系统、浅色或深色。\n前台手动共享文字与单文件，最大 64 MiB；收到后按需复制或另存。\n文件需接收端确认，校验完成后可保存；不自动覆盖或打开。\n前台连接；手机切后台会断开（系统文件选择期间除外）。\n空闲 3 分钟或最长 10 分钟结束连接；可在设备页重连。\n文件传输限速 2 MiB/s；不改变网络、代理或防火墙。\n暂不支持自动后台剪贴板同步、PC-PC、Wi-Fi Direct 或续传。\n')
print(json.dumps({'file':str(out),'bytes':out.stat().st_size,'sha256':hashlib.sha256(out.read_bytes()).hexdigest()}))
PY
