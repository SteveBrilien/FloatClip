# FloatClip 云端续接资料

最新增量：两端 **0.7.8 / build 25 候选**，先读 [build 25 与设备边界](CANDIDATE_078_BUILD25_2026-10-23.md) 和 [文档入口](README.md)。分类/反馈/背景模式、手机主动图片收录及文件会话显示已调整，Windows 修复蓝牙阻止 LAN 接续。原数据和协议兼容，公共 feed 仍为 0.7.1，手机调试当前离线。以下 build 24/23 留作历史背景：先读 [build 24 修复记录](WINDOWS_078_HOTFIX_2026-10-23.md)：预览加入旧 ZIP 编码兼容，Windows 更新改用系统原生 WinHTTP 网络组件并保留两处源的失败原因。桌面 170 项、更新 45 项检查和打包后隔离启动通过；后文 build 23 描述保留为上一轮交接背景。当前 Github 推送可使用小主机原有部署密钥，密钥内容不要导出或放进聊天/Git。

从本轮 `codex/wpf-design-system-077` 分支继续。先读根 AGENTS.md、`design/FLOATCLIP_WINDOWS_DESIGN_SYSTEM.md`、`design/CHANGELOG.md`、`CANDIDATE_0.7.8_2026-10-23.md`、`NATIVE_UPDATES_2026-10-23.md`；保留 WPF + XAML。WebView2 实验另存分支，不作为当前路线。Windows 和 Android 当前候选均为 0.7.8 / build 23；0.7.7 和更旧记录仅作历史证据。用户负责候选外观检查，不能代替其宣称验收。

## 用户已确认的方向

Windows 单一左侧导航窗口，紧凑标准控件，设置使用开关/滑条；文件页按已确认的资源管理器式清单实施，不能擅自换成聊天卡片或另一种布局。边界清楚的工具栏、蓝色下载主按钮、六列对齐、实心/虚线类型图标；少灰色解释文字，计数使用空格/竖线。切页不改窗口宽度。手机文件页是 12dp 卡片、首行对齐状态标、卡片内来源/时间、左滑和长按多选。

## 关键源文件

- Windows 窗口与交互：`windows/FloatClip.Desktop/LocalClipboardWindow.cs`、`ClipboardNavigation.cs`、`FileShelfView.cs`、`FilePreviewWindow.cs`、`ClipboardSharingWindow.cs`、`DesktopControls.xaml`。
- 设计资源与设备页：`Design/`、`Views/DevicePageView.xaml`、`ViewModels/DevicePanelViewModel.cs`、`Services/ClipboardDeviceService.cs`、`Services/DocumentPreviewService.cs`、`ShellFileIcons.cs`。
- 宿主/同步：`ClipboardHost.cs`、`NativeClipboardSync.cs`、`BluetoothSyncTransport.cs`、`ClipboardSyncEngine.cs`、`ShareCore.cs`、`FileCatalog.cs`。
- 手机后台采集与蓝牙：`app/src/main/java/com/floatclip/app/sync/ClipboardSyncService.kt`、`capture/BackgroundClipboardCapture.kt`、`capture/AdbClipboardHelper.java`、`scripts/activate-adb-clipboard.sh`。
- 手机文件：`app/src/main/java/com/floatclip/app/files/`。真实传输只在已授权的 FCP4 局域网会话使用清单帧，蓝牙/FCP3 继续使用既有文字协议。
- 交互预览在 `docs/ui/`，为带模拟数据的设计参考，不是真实网络客户端。
- 双端更新：`ShareUpdates.cs`、`nearby-probe/.../ShareUpdates.kt`、`UpdateManifestVerifier.cs`、两端 `UpdateTrust`、`updates/native-updates.json`、`scripts/sign-native-updates.py`。签名私钥只在原 Windows 本机，不在云端/小主机。

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

剪贴板图片跨设备自动同步、Windows 系统跨应用分享、完整 Office 排版预览、Wi-Fi Direct 和手机重启后的后台采集恢复仍需推进。Office 当前仅只读文字/单元格预览。双端签名更新源已迁移到小主机公网主源、GitHub 备用，实际签名验证通过；公开清单仍为 0.7.1，不能把候选称为已公开发版。Android 下载仍通过浏览器/系统安装，应用内 APK 下载后验哈希未做。

0.7.8 桌面 168 项检查、更新 43 项检查、Android 61 项单测与 lint 通过。候选目录 `analysis/candidate-078` 用于手机构建，最终资料快照 `analysis/cloud-handoff-078-final` 独立保存，勿覆盖小主机原工作区的 18 项其他修改。下一轮真实蓝牙/手机回归仍应保留原授权数据；本轮未以模拟服务或 TCP 回环冒充手机实机验收。

## 切回云端

官方文档支持在可用时使用 `/cloud` 选择云端执行和已发布环境；当前执行中的任务不能在一轮内切换执行位置，工具也不能替当前对话直接 handoff 到 cloud。先完成本轮 Git 推送和资料同步，再由用户切换云端环境；云端从上述分支取代码。当前电脑文件、运行中的窗口和系统授权不会自动迁移，必要的 Windows/手机验收仍通过已连接主机完成。

参考：[开发命令](https://learn.chatgpt.com/docs/developer-commands)、[远程连接与限制](https://learn.chatgpt.com/docs/remote-connections)。
