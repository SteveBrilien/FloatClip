# FloatClip 0.7.6 / build 21 试用候选交接

2026-10-23，用户要求先交付可连接配对的两端试用版，桌面各页对照已确认的交互预览，避免大块按钮堆叠。本文更新此前各预览记录的实现状态；这些预览记录中的“尚未修改 Android / 尚无目录协议”属于当时状态。

## 交付与使用

- Windows：`analysis/FloatClip-Windows-0.7.6-candidate.zip`，包含自带运行环境的 EXE、说明和依赖许可证。完整解压；先从托盘退出旧版，再运行包中的 FloatClip.exe。未覆盖 `D:\FloatClip\FloatClip\FloatClip.exe`，安装副本仍为 0.7.2，哈希与工作开始时一致。
- Windows 目标：x64、Windows 10 2004 / build 19041 及以后。2016 年 Windows 10 / LTSB 1607 未做真机验证。
- Android：`analysis/FloatClip-Android-0.7.6-candidate.apk`；候选任务 `floatclip_076_candidate`，隔离源码 `analysis/candidate-076/android-source`。版本 0.7.6 / 21，包名 `com.floatclip.probe`，minSdk 28；复用原 Share 应用签名，不修改独立旧剪贴板包的签名线。
- 两端同一 Wi-Fi / 手机热点；手机允许添加设备，电脑“我的设备 → 添加设备 → 通过局域网添加”，核对识别码并在手机授权。已授权设备沿用既有重连逻辑。
- 先双向复制两条不同的文字验收，再在任一端添加小文件；另一端只出现清单，显式下载后才传内容。单文件上限 64 MiB，每端主动共享最多 64 项。
- 本次仅供试用，未推送提交、发布正式版本或替换线上更新清单。

## 对照预览后的 Windows 实装

| 页面 | 本轮实现 | 与预览仍有差距 |
| --- | --- | --- |
| 主窗口与导航 | 同一左侧 NavigationView；剪贴板、文件、设备、设置、更新复用页面。删除旧 MainWindow 及其兜底入口，旧 --legacy-lan 快捷方式映射同一设备页 | 主体继续使用 WPF + WPF UI 4.3.0 |
| 剪贴板 | 紧凑搜索与标题按钮、查询与筛选、右滑固定、保护清除/撤销；本机图片保存/预览/复制，彩色 emoji 与精确复制；预览窗口非模态 | 图片剪贴板跨设备自动同步未实现 |
| 文件 | 资源管理器式多列列表，标准 ToolBar，Ctrl/Shift/勾选多选，时间筛选、列头排序，实心/虚线类型图标；选择不下载；下载到指定目录、进度、ETA、取消；保留与清理区分原文件和缓存 | 系统默认应用打开文件；内嵌 PDF/Office 预览、独立属性侧栏与 Windows 系统转发尚未接入 |
| 设备 | 已配对设备优先显示，添加设备折叠，紧凑按钮与标准 ComboBox；不因文件页进度重建剪贴板列表 | 真实手机当前离线，未补完个人设备端到端验收 |
| 设置 | 标准 ToggleSwitch / Slider / ComboBox，32 DIP 输入与操作控件，设置项按行排列；浅色、深色、东京夜色统一动态资源 | 原生窗口截图及交互预览各自验证，不能视为所有系统缩放下逐像素一致 |
| 更新 | 同页更新 UI、非模态启动提示、错误分类；SHA256/大小校验、原子替换与失败回退检查 | 版本清单仍在 GitHub；自建更新源和签名清单未迁移部署 |

实际原生截图在 `analysis/desktop-checks/`：`files-page-tokyo-night.png`、`settings-native.png`、`settings-tokyo-night.png`、`devices-native.png`、`updates-native.png`、`clipboard-tokyo-night.png`。文件列表截图含自动检查的示例文件，安装包不附带用户文件或样例历史。

## Android 实装

文件页遵循原有 FloatClip 风格：12dp 卡片圆角，22×24dp 状态标按文件名第一行行高对齐，来源/时间在卡片内，右侧类型图标区分本地实心与远端虚线。点击详情、左滑下载/分享、长按多选、当前时间范围全选、指定目录批量保存和缓存/记录清理已接入原生页面。

下载使用实际字节进度与采样 ETA；分享先准备所有选中文件，再通过临时只读 content URI 调用 Android 系统 chooser。离线或失败不会静默少发文件；后台完成需等待页面恢复后交接。目录访问使用 SAF，持久权限按系统实际授予标志保存。保留文件跳过清理；应用内共享副本与用户原文件隔离。

新增满额保护：512 条目录均受保护或已缓存时，不接纳额外条目，显示清理提示，保留现有记录和连接；不因为目录已满而反复中断文字同步。

## 同步与文件协议

新清单帧仅发送给显式协商 FCP4 的已授权 LAN 会话。旧 FCP3 / 蓝牙会话不接收新帧，原文字 HELLO、ACK、durable outbox 与重连行为保留。清单只广告用户主动加入的文件名和元信息，不向对端暴露本机路径。

按需拉取绑定文件标识、版本和请求 UUID，文件完成后校验长度和 SHA256；取消下载会关闭共用设备会话，由原机制重连，短暂影响该设备连接。Wi-Fi Direct 未实现，手机热点使用 LAN。

## 验证证据

- Windows 原生 DesktopChecks：78 项通过。报告 `analysis/desktop-checks/report.json`；包含原生控件/页面、主题、emoji、图片、搜索、保护清除、持久化、实际 TCP 文件拉取与穿插文字帧，以及元信息损坏恢复/保留标记保护。
- Windows 更新检查：33 项通过，未执行真实安装。
- Windows 剪贴板库：80 项持久化、来源和冲突检查通过，包含 AES 测试编码与实际 Windows CurrentUser DPAPI。
- 最终自包含 EXE：`--qa-ui` 在隔离数据目录启动，真实窗口可见，版本 0.7.6 / 21，未读取用户历史或操作剪贴板。证据 `analysis/candidate-076/packaged-ui.json`。
- Windows ZIP 完整性与 SHA256：`analysis/candidate-076/windows-package.json` 和包旁 `.sha256`。
- Java Share core 120、Clipboard sync 56、Transport crypto 16、Scoped discovery 5 项通过。任务 `task-floatclip_076_core_checks-83c2cc60abbd4c57a76c`。
- C# / Java：58 项通过，包含实际 loopback socket、持久会话、重启重连、ACK、游标和不回环。任务 `task-floatclip_076_cross_language-0a31c3ece30b496fbfb2`。
- Android 最终完整构建：56 项测试通过，lint 0 errors / 17 warnings，包含满额保护与原生卡片首行对齐回归。最终任务 `task-floatclip_076_candidate-3f74578e44694e2eb652`，制品 `artifact-890d4e291d5d42038b930e293c329f65`。签名指纹符合 `updates/android-identity.json`：`0368593f82ab885737b99d78c939111a1ec8ea3d88e8a67b2c3c9a4bd90c3f2c`。
- APK 为 3,728,681 字节，SHA256 `aa981ae8cdd40a280f418dd2f526e5f830cfea98f4424af1fd2d6e71700d3d32`；通过正常 HTTPS 证书校验从公开临时链接重新下载后与构建产物一致。详情与七天链接见 `analysis/candidate-076/android-package.json`。PowerShell 在受限执行环境出现 Schannel 无凭证错误；改用 Python 默认 SSL 验证成功，未关闭证书验证。
- 手机交互预览回归通过：实际指针滑动/长按、多选、按范围选择、批量下载/分享准备、取消、进度/ETA、离线、320px 与主题对比。预览是演示，不等价于 Android 真机验收。

## 保留现场与后续验收

本地继承的修改归档于 `analysis/candidate-076/inherited-local-source.zip`。小主机主工作区有七个尚未提交的 Android 图片相关修改，已备份到 `analysis/candidate-076-checkpoint/inherited-android.zip`，未直接覆盖；候选通过隔离目录构建，并复用现有 SDK、依赖缓存与签名密钥。不要直接用本地文件整体覆盖小主机主工作区。

当前 ADB 列表中个人手机离线，所以尚未宣称手机与电脑真机互通完成。正式发布前仍需同一批候选在个人设备上检验：双向带 emoji 文字、授权/暂停/取消授权、离线重连、跨端清单、双向指定文件下载、批量保存、系统分享、取消后文字恢复，以及 Windows 启动更新提示。图片跨端自动同步、自托管签名更新源、Windows 系统转发/内嵌预览与 Wi-Fi Direct 分别继续推进。
