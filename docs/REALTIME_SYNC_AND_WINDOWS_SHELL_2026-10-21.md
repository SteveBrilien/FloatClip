# 实时同步与 Windows 界面：2026-10-21 续接

分支：feature/clipboard-sync-core-20261020。起点：2fc5ade。当前候选为 0.7.2 / build 17；未发布正式版本，未更新正式更新源。用户已否定本轮部署的 Windows 预览外观，不能把编译成功当作界面验收。

## 已定位并修复的同步故障

最关键的问题在 Java TransportCrypto.Records：read 在等待网络数据时持有与 write / seal 共用的对象锁，导致空闲的接收循环堵住发送。改为独立的 sendLock / receiveLock，同一方向仍串行，协议、密钥和计数规则不变。增加“接收阻塞时主动发送必须完成”的回归检查。此前顺序互通测试没有覆盖这个故障。

其他修复：
- Android 显式打开浮窗时短暂取得焦点读取当前剪贴板，随后释放；外观重建不取得焦点。采集直接进入 SyncRuntime，不使用旧无障碍缓存冒充新剪贴板。
- 两端从持久化待发队列选出 pendingPeers，每 5 秒对未连接目标尝试恢复；连接 / 服务发现有界，socket 被跟踪与释放，多设备不在第一个成功后停止。
- 主动复制历史条目建立独立用户复制事件；被动接收写入仍打自写入标记，避免回声，保留原历史来源。
- Windows 常驻 ClipboardHost 独立拥有隐藏消息窗口、剪贴板监听、同步、托盘与快捷键，视图关闭后继续工作。每用户 Mutex 和 CurrentUserOnly NamedPipe 保证单实例。

## Android 后台采集的实际边界

Android 10+ 普通应用在后台不能任意读取系统剪贴板。目标 vivo / Android 11 上实测 shell 已有 READ_CLIPBOARD_IN_BACKGROUND 且 READ_CLIPBOARD AppOp 为 allow，因此提供默认关闭、可撤销的“ADB 后台采集增强”。

最终桥接使用手机本机 127.0.0.1 的临时 TCP 端口，复用 TransportCrypto 进行认证和加密。32 字节随机令牌只在应用和获授权的 shell helper 内存中传递。Bootstrap ContentProvider 同时要求 android.permission.DUMP 和 Binder UID=2000；helper 校验应用 UID 和服务端证明。令牌不进入命令行、文件或日志。此前 abstract LocalSocket 方案被 OriginOS 拒绝，已替换，不是最终实现。

helper 使用系统剪贴板变化回调，没有定时轮询；连接建立前先记录基线，不重放激活前的旧剪贴板。敏感 / 自写入内容被排除，文字有 64,000 字节上限，帧严格校验。关闭或切换发送权限会更新发送批次并断开旧连接，旧连接不能跨越 off/on 补发。

激活脚本 scripts/activate-adb-clipboard.sh，独立 helper 的可复现构建脚本 scripts/build-adb-clipboard-helper.sh。激活后不依赖 ADB 持续连接；手机重启后需重新激活，不承诺自动跨重启恢复。保留原应用数据，没有卸载或清库。

## 已取得的真机证据

### Android → Windows

最终逻辑包 build 16（SHA256 61e3a7d45207d684e2993f4ac9792d113478f506c2784c3b3ff20912275f44ad）安装成功后，激活增强采集并按 Home。没有再打开 FloatClip，依次由 shell 测试入口执行三次合成系统复制：

| 合成内容 | 手机进入采集时间 | Windows 历史核验 |
| --- | --- | --- |
| FC-QA-ADB-BACKGROUND-1792596147859 | 1792596147865，约 6 ms | 1 条，来源 android |
| FC-QA-ADB-BACKGROUND-1792596349861 | 回调累计第 2 条 | 1 条，来源 android |
| FC-QA-ADB-BACKGROUND-1792596423183 | 1792596423189，约 6 ms | 1 条，来源 android |

最后服务诊断 capture frames=3、connected=true、pendingOutgoing=0。Windows 在本机解密自己的库，只输出上述合成条目的匹配计数与来源，没有导出其他剪贴板正文。该证据证明后台事件到达 Windows 且未重复入库；不是长期休眠、所有第三方应用、重启后的稳定性保证。

更早 marker 1792593587469 是打开应用后才被采集，不能用作后台成功证据。marker 1792595323634 曾单独通过后台投递，但上述三次为安装锁修复后的正式重复测试。

Windows → Android：用户最新反馈“基本可以稳定传回手机”。本轮另发 FC-QA-WINDOWS-BACKGROUND-20261021-2，未把未核验的系统当前剪贴板当作已通过。继续保留用户实测与自动实测的区分。

### Windows 宿主

已实测：
- 默认再次启动前后进程数均为 1，激活现有面板。
- 关闭全部 FloatClip 可见窗口后，进程存活；新复制事件仍推进同步时钟。
- 固定切换和位置保存成功，用户之后取消了固定。
- HKCU Run 已登记已验证的 Preview/FloatClip.exe --background；未重启或重新登录验证。
- 旧预览的 Ctrl + Win + V 注册 / 调出失败，用户录入的 Shift + Win + V 也在截图显示占用。不能写成快捷键验收通过。

## Windows UI 反馈与修正范围

用户截图与 remote 指定窗口截图均表明：
- 大“管理剪贴板”与浮动剪贴板几乎完全重复。
- ICO 作为普通 BitmapImage 加载后用于页头，存在选择小帧再放大的风险；资源实际包含 16 至 256 的七档 PNG，不是缺少大尺寸资源。
- WindowChrome、14 DIP 内层圆角边框、外部矩形客户区以及系统缩放 grip 叠加，导致边角和底边异常。
- 快捷键只显示输入值，无法明确区分待保存与已成功注册。
- 图片无法采集、保存和重新放回系统剪贴板，因为当前历史与同步协议只有 text，属于缺失能力。

已完成源码修正并编译的过渡改动：
- 所有旧“管理剪贴板”入口激活同一个面板，托盘保留“打开剪贴板 / 设置 / 暂停 / 退出”，删除第二份列表入口。
- 选择 ICO 最大尺寸帧；托盘按系统 SmallIconSize 取帧，开启布局像素对齐。
- 删除多层自绘外边框与缩放 grip，Windows 11 通过 DWM 的 DWMWA_WINDOW_CORNER_PREFERENCE 统一窗口外轮廓。Windows 10 不伪造相同圆角。
- 默认快捷键 Ctrl + Alt + V；设置改为“更改 → 录制 → 保存”，注册成功后才标记已保存，并提供恢复默认按钮。
- 设置显示版本和 build，不硬编码旧版本。

这些修正尚未部署到 Windows 真机，不能声称外观或自定义快捷键已验收通过。已安装的仍是被用户批评的旧 WPF Preview。完整的浅 / 深色、焦点、DPI、键盘操作和图片流程尚未完成。

remote 工具没有独立截图按钮；可用 PowerShell + Win32 PrintWindow 定向截取本进程，再用 remote read_file 直接返回图片。第一次 CopyFromScreen 被其他窗口遮挡，第二次 PrintWindow 已看见真实 FloatClip；第三次在截取前设置 SetThreadDpiAwarenessContext(-4)，630×973 的完整窗口截图已回读；若不设置会裁掉窗口右下区域。截图回读通常以 Base64 图片块封装，无需经小主机中转；不能把截图文件生成成功当作视觉验收。

## 后续 UI 与图片方向

推荐先制作 C# + WinUI 3 的代表性面板原型，保留已验证的 C# 同步 / 授权 / 加密 / 持久化与系统宿主契约。此建议更新前一天“先保留 WPF”的判断，但不代表 WinUI 迁移已获确认或已经完成。WPF 同样可以做好外观，这次粗糙是实现与验收问题。

产品上只有一个主要剪贴板取用面板，快捷键与托盘调出同一实例；固定是窗口状态。设备与设置承担管理，不再展示第二份相同历史。需要更多整理空间时，在同一状态模型中展开，不再维护另一份窗口逻辑。已有 React/PWA 的整套壳也不能继续作为并列主入口。

React 需要 Electron / Tauri / WebView2 等宿主；Flutter 同样需要 Windows 平台接口处理窗口、焦点、快捷键和多格式剪贴板。C++ 是语言，应比较 WinUI / Win32 / Qt 等具体框架，不因换语言就认为模糊和布局会自动解决。WinUI 3 支持 C#，无须为采用原生 UI 重写同步核心为 C++。

图片需要独立里程碑：
1. 类型化记录：text / image、尺寸、MIME、内容哈希、缩略图、受限加密 blob 存储与删除。
2. Windows 本机图片捕获与恢复；实际验证截图、PNG、DIB / DIBV5、透明度和目标应用粘贴。
3. Android content URI / ClipData 适配和生命周期。
4. 两端先声明图片能力，再传输有界加密附件、校验、持久化确认与清理；不得把图片塞入现有文本字段假装兼容。
5. 分别验收“存入历史”“复制回系统”“向原焦点粘贴”“跨设备图片同步”，不混为一个完成状态。

## 自动检查与候选

- Java Core 120、ClipboardSync 52、TransportCrypto 16、Discovery 5 全部通过。
- C# ClipboardSync 55；Java/C# 真实 socket、断线、重启、ACK 与 latest-only 互通检查通过。
- Android 完整 assembleDebug / testDebugUnitTest / lintDebug 通过：41 tests、0 failures、0 errors。任务 task-floatclip_sync_android_verify_offline-81a539e05ded408585ab，artifact-bde261ad7aa449f5b465fdc5ef43de50。
- 0.7.2 / build 17 仅递增包与更新检查身份 / User-Agent，采集与传输逻辑不再改动；单独重新打包，避免把测试统计冒充为另一轮新测试。最终打包 task-floatclip_sync_android_candidate_package-0fd6565bc13e43238db2 成功，artifact-cf1fb933d0544517bdd85847542c7ab6；SHA256 7c8925b999d0aad0929e52afcf11d1f33fb3d7e07e6a341150deeb52c982b00b，apksigner 核对签名保持 android-identity.json 中的证书。已成功安装，设备确认 versionName=0.7.2 / versionCode=17。安装后再次回到桌面，合成 marker FC-QA-ADB-BACKGROUND-1792596883047 在约 6 ms 内进入采集，Windows 历史核验 1 条、来源 android。
- Windows 过渡改动编译成功：task-floatclip_sync_windows_dev-2cef8c8db49e428dbf0b，artifact-d4e6ebe8ff96412c9925e8adba988e11；仅内部候选，未部署。
- 发布合约 scripts/check-share-contract.py 暂未通过：新原生候选为 0.7.2 / 17，web/public/release.json 与 updates/share-preview.json 仍是正式的 0.7.1。不能为使此检查变绿而伪改下载摘要或发布状态；此门禁留到正式发布准备时处理。
- 旧 Windows 预览临时发布 pub-5993669d836d466a9f9d24ea63791071 已撤销。

参考：
- https://developer.android.com/about/versions/10/privacy/changes#clipboard-data
- https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/ui/apply-rounded-corners
- https://learn.microsoft.com/en-us/windows/apps/winui/winui3/
- https://learn.microsoft.com/en-us/windows/apps/windows-app-sdk/migrate-to-windows-app-sdk/wpf-patterns-winui3
- https://docs.flutter.dev/platform-integration/platform-channels
