# FloatClip 云端续接资料

从 GitHub 本轮 `codex/windows-ui-sync-20261023` 分支继续。最新产品状态先读 `WINDOWS_0.7.7_REVIEW_2026-10-23.md` 和 `CANDIDATE_0.7.6_2026-10-23.md`；后者保留上一批双端候选的边界，Windows 已在本轮修订为 0.7.7 / build 22，Android 仍为 0.7.6 / build 21。

## 用户已确认的方向

Windows 单一左侧导航窗口，紧凑标准控件，设置使用开关/滑条；文件页按已确认的资源管理器式清单实施，不能擅自换成聊天卡片或另一种布局。边界清楚的工具栏、蓝色下载主按钮、六列对齐、实心/虚线类型图标；少灰色解释文字，计数使用空格/竖线。切页不改窗口宽度。手机文件页是 12dp 卡片、首行对齐状态标、卡片内来源/时间、左滑和长按多选。

## 关键源文件

- Windows 窗口与交互：`windows/FloatClip.Desktop/LocalClipboardWindow.cs`、`ClipboardNavigation.cs`、`FileShelfView.cs`、`FilePreviewWindow.cs`、`ClipboardSharingWindow.cs`、`DesktopControls.xaml`。
- 宿主/同步：`ClipboardHost.cs`、`NativeClipboardSync.cs`、`BluetoothSyncTransport.cs`、`ClipboardSyncEngine.cs`、`ShareCore.cs`、`FileCatalog.cs`。
- 手机后台采集与蓝牙：`app/src/main/java/com/floatclip/app/sync/ClipboardSyncService.kt`、`capture/BackgroundClipboardCapture.kt`、`capture/AdbClipboardHelper.java`、`scripts/activate-adb-clipboard.sh`。
- 手机文件：`app/src/main/java/com/floatclip/app/files/`。真实传输只在已授权的 FCP4 局域网会话使用清单帧，蓝牙/FCP3 继续使用既有文字协议。
- 交互预览在 `docs/ui/`，为带模拟数据的设计参考，不是真实网络客户端。

## 构建和检查

Windows 使用 .NET SDK 10.0.402；目标 `net10.0-windows10.0.19041.0`、x64。现有候选自包含 .NET 运行环境。常规检查：

```text
dotnet run --project windows/FloatClip.DesktopChecks -c Release
dotnet run --project windows/FloatClip.LibraryChecks -c Release
dotnet run --project windows/FloatClip.UpdateChecks -c Release
python scripts/check-share-contract.py
node scripts/check-desktop-preview.cjs
```

预览检查需要 Playwright 和浏览器；通过 `PLAYWRIGHT_CHANNEL` 可选本机 Edge。原生窗口、WinRT 蓝牙和 Windows CurrentUser DPAPI 的实机检查需要 Windows，不能在 Linux 云环境中声称通过。Java/.NET 协议检查见 `scripts/test-share-core.sh` 和 `windows/FloatClip.SyncChecks`。

Android 候选此前通过隔离目录构建，复用了小主机既有 JDK17/SDK/Gradle 缓存和签名。不得把签名私钥、配对存储、剪贴板库或本机 Host 数据放进 Git。远端主工作区仍有其他未提交修改，不能直接覆盖；本轮使用新分支和独立资料目录保存。

## 未完成的产品能力

剪贴板图片跨设备自动同步、自托管签名更新源、Windows 系统跨应用分享、完整 Office 内嵌预览、Wi-Fi Direct 和手机重启后的后台采集恢复仍需推进。更新检查当前仍使用 GitHub 清单；不能把已有公网安装包链接误写成已完成更新源迁移。

## 切回云端

官方文档支持在可用时使用 `/cloud` 选择云端执行和已发布环境；当前执行中的任务不能在一轮内切换执行位置，工具也不能替当前对话直接 handoff 到 cloud。先完成本轮 Git 推送和资料同步，再由用户切换云端环境；云端从上述分支取代码。当前电脑文件、运行中的窗口和系统授权不会自动迁移，必要的 Windows/手机验收仍通过已连接主机完成。

参考：[开发命令](https://learn.chatgpt.com/docs/developer-commands)、[远程连接与限制](https://learn.chatgpt.com/docs/remote-connections)。
