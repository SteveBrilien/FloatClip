# 剪贴板功能续接：2026-10-22

## 起点与约束

- 从 Orange Pi 正式工作区、分支 `feature/clipboard-sync-core-20261020`、HEAD `f5d7a61` 接手；保留继承的 Windows 未提交改动，不覆盖配对或实际剪贴板数据。
- 本地开发检出位于 `D:\OneDrive\文档\ChatGPT\FloatClip`。原目录为空，现包含源码；`D:\FloatClip` 仅为旧安装包。
- 最新有效交接为 `REALTIME_SYNC_AND_WINDOWS_SHELL_2026-10-21.md`；早期 STATUS/README 中 0.7.1 不是当前开发进度。
- 原生候选仍为 0.7.2/build 17；不修改正式下载 feed、签名身份或正式发布状态。
- 保留文字双向同步、独立 send/receive 锁、持久 pending/ACK、OWN_WRITE 回声抑制、后台宿主与单实例。图片不会塞入文字协议。

## 小步实施与验收

1. 图片本地数据层：类型化记录、独立加密 PNG、尺寸/8 MiB 单图与 64 MiB 总量限制、完整备份/恢复；验证失败不得覆盖现有数据。
2. Windows 系统剪贴板与面板：优先 PNG 保透明，兼容 Bitmap/DIB；图片缩略图、查看、复制、另存；正文与全文支持系统彩色 emoji。
3. 清理与整理：全部清除保留固定/置顶，批量撤销不覆盖受保护内容；验证关闭重载后的保留，不把模拟重载写成真实断电测试。
4. 同一主面板：托盘、快捷键、旧管理入口复用实例。设置/共享/全文/图片按类型或条目复用非模态子窗；复制主面板内容时不连带隐藏正在使用的子窗。
5. 搜索/手势/主题：验证真实 TextChanged 过滤及焦点；右滑固定、左滑置顶/删除；所有打开子窗和菜单动态跟随浅/深色，滑轨支持两个方向。
6. Android：复查已有搜索/右滑保护，补批量清除/撤销与活动对话框主题刷新，继续补前台/明确展开后的本机图片路径。
7. 回归与候选：本地 Windows 构建、LibraryChecks、真实隔离 WPF 交互检查；远程 Java/C# 互通、Android assemble/unit/lint；仅输出内部候选，不覆盖正在运行的稳定安装。

## 产品决定

WPF 的主窗口不能操作源于 `ShowDialog` 选择，并非框架限制。本轮保留经过验证的宿主和同步接口，采用非模态 `Show` 与明确的子窗口复用；没有重写为 WinUI。

Windows 移除嵌入式 WebView2 主历史与旧 vault bridge。原 bridge 由旧纯文字网页整库写回，会剥离图片/固定元数据。`--web` 与可选跨网入口改为系统浏览器打开设备页；iPad/PWA 服务继续保留。本机历史只由原生面板管理。

emoji 使用 [Emoji.Wpf 0.3.4](https://github.com/samhocevar/emoji.wpf)，依据系统字体提供彩色字形、ZWJ/肤色和旗帜。全文复制须使用其 Unicode selection serializer，不能用 WPF TextRange 的图片占位字符代替原文。

## 验证记录

- 起点同步回归：Java Core 120、ClipboardSync 52、TransportCrypto 16、Discovery 5；C# ClipboardSync 58 与 Java/C# 真实 socket 的 ACK、断连、重启、latest-only 和无回声检查均通过。
- Windows LibraryChecks：80 项通过，包括真实 CurrentUser DPAPI 图片持久化、损坏/缺失图片修复、清除保护、重载保留、跨库图片备份与失败回滚。
- DesktopChecks 使用临时数据目录，关闭系统监听、同步、托盘和快捷键；不会捕获或写入用户实际剪贴板。最终界面与构建结果见同次交付的验证报告。

## 独立后续里程碑

- 跨设备图片需要能力协商、有界加密附件、内容哈希、持久确认、断线恢复和生命周期管理。本轮文字协议保持兼容，不能把本机图片成功等同于图片已跨端同步。
- Android 10+ 后台 URI/剪贴板权限边界不能由普通应用绕过；既有 ADB 增强助手仍仅传文字。图片前台采集与后台增强需分别验收。
- 重启/断电、长时间休眠后接续、Windows 10/不同 DPI/目标应用 DIBV5 粘贴、vivo 真机图片权限和 iPad 仍需设备验收，不能以编译或模拟器检查替代。
