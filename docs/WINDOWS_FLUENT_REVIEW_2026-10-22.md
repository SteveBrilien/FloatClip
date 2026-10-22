# Windows Fluent UI 预览阶段（2026-10-22）

> 2026-10-23 更新：0.7.6 试用候选已完成原生 UI 整理与文件目录接入；下文为预览阶段记录。最终状态见 [候选交接](CANDIDATE_0.7.6_2026-10-23.md)。

用户最新要求：先在对话中体验交互 UI，根据反馈调整，然后再完整发版。当前不能把文件流程演示或静态兼容检查标成真机收尾完成。

用户已基本认可主界面，进一步要求细化文件页。最新单页交互与本机下载状态语义见 [文件页设计预览](WINDOWS_FILE_UI_REVIEW_2026-10-22.md)。

## 当前 Windows 代码

- 开发版本 0.7.5 / build 20，WPF UI 4.3.0；继续使用现有 WPF 宿主与授权/同步机制。
- 原生 NavigationView 左侧导航；剪贴板、设备、设置、更新在一个主窗口中切换，页面复用。图片与全文预览仍可单独非模态打开。
- 搜索框 34 DIP、标题按钮 30 DIP、图标 16–18 DIP；正文默认 14 DIP，卡片默认间距 8 DIP。
- 设置采用 ToggleSwitch 和 Slider，可调整导航文字、主题、设备颜色、卡片背景、正文大小、间距和面板宽度，并保存到原有用户配置。
- 东京夜色沿用 Android 主背景 #1A1B26、卡片 #24283B、正文 #C0CAF5，保留原深色和跟随系统。
- 45 项 DesktopChecks 通过，覆盖页面复用、返回搜索、真实开关/滑条、设置持久化、主题、emoji、图片和保护清除/撤销；后续增加的文件适配器尚未完成专项回归。
- Windows 编译通过。C#/Java 58 项文字同步回归此前再次通过；文件适配器加入后应再跑 ShareChecks 和同步回归。

## 文件传输：已核实与仍需完成

- Android 的 DeviceActivity 已继承 ShareActivity，前台注册 SyncRuntime.Observer，能够收到现有 ShareWire file offer / progress / file 回调；手机退出该页面时会拒绝文件邀请。
- 既有协议上限单文件 64 MiB、32 KiB 分块、SHA-256、接收确认、ACK；无需为首版局域网文件传输更换文字协议。
- 已开始接入 DesktopTransfers，增加 Session 的结构化 FileFailed 回调，并把 NativeClipboardSync 原先统一拒绝文件邀请的处理替换为适配器注册。此代码能编译，**尚未完成原生文件页、邀请通知和收发测试，不可部署当前源码给日常用户**。
- 必须补齐：设备暂停/取消授权时的文件权限、接收确认、另存为、缓存清理、文件状态更新、断开/取消的处理、应用退出时资源释放。
- 现有协议没有独立的中途取消帧；若关闭该连接停止文件，会短暂断开该设备的文字连接，依靠原有 durable outbox / ACK / reconnect 保留文字。产品应明确提示，或后续做独立文件通道，不能伪装成无影响取消。
- 文件进度应独立更新，避免每 100 ms 触发整个剪贴板列表重建。当前临时 Host.Transfers.Changed→NotifyChanged 需要改成专用 UI 更新路径。
- Wi-Fi Direct 在两端尚未接入。手机热点属于局域网方案，不应标作已支持 Wi-Fi Direct。

## 旧 Windows 兼容性

当前交付目标仍为 net10.0-windows10.0.19041.0。曾以 SupportedOSPlatformVersion=10.0.14393.0、CA1416 作为错误进行静态编译，成功；这不等于 Windows 10 1607 真机可用证明。

.NET 10 支持清单列有 Windows 10 1607 Enterprise；当前使用的 GetRfcommServicesForIdAsync 也从 14393 引入。不能把运行库或整个 UI 库描述为 Windows 11 专属。

系统圆角和深色标题栏已有版本判断，Fluent 背景当前使用纯色；旧系统主要回退系统特效。仍需在目标 1607 / LTSB 环境验证启动、图片、DPAPI、蓝牙驱动、LAN、配对与双向文字。当前不承诺已兼容 1607。

参考：[.NET 10 支持表](https://github.com/dotnet/core/blob/main/release-notes/10.0/supported-os.md)、[蓝牙 API](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.bluetoothdevice.getrfcommservicesforidasync)、[WPF UI](https://github.com/lepoco/wpfui)。

## 对话预览与下一步

对话预览文件是 floatclip-fluent-preview.html，包含紧凑/展开侧栏、五个页面、实时搜索、图片/全文、左右滑动、右键菜单、固定/置顶、清除/撤销、外观开关/滑条、模拟配对、文件发送/接收/停止、更新流程。配对与文件不产生真实网络传输。

浏览器验证了导航、搜索状态保留、主题、滑条、清除保护/撤销、识别码流程、文件发送/停止/接收、更新和 320px 布局，无运行错误。下一步先接收用户 UI 反馈，再补原生文件收发与专项测试，重建最终候选、附依赖许可证、做真机互通后再准备正式发布。未更新线上 feed、GitHub Release、手机端或 D:\FloatClip 的安装文件。
