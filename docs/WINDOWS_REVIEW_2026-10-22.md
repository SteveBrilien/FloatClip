# Windows 0.7.3 / build 18：交互预览与候选

## 本次范围

只修改 Windows 客户端、Windows 检查项目和预览/交接文档。现有 Android 改动保持原状，未打包或部署 Android。正式发布源、GitHub Release 和本机已安装 EXE 均未更新。

本机 `D:\FloatClip\FloatClip\FloatClip.exe` 的 SHA-256 为 `b46f12422829e6cd87622797aa145a1b7bc433f104f96de34cb8dc91e1fef006`，与后续会话的 0.7.2 候选完全一致。已读取“接受会话并继续推进”及远程最新交接；保留撤下备份 UI 的产品取舍。

## 改动

- 主面板采用中性浅/深色，保留统一主入口、右滑固定/左滑置顶删除。卡片增加来源与时间，细滚动条，图片筛选。
- 彩色 emoji 使用系统字体和 Emoji.Wpf；中文/肤色/ZWJ/旗帜/keycap 保留完整序列，全文复制使用 Unicode selection serializer。
- PNG 优先并保透明，增加有界 DIB/DIBV5 解码及 Bitmap 回退，补充格式检测；缩略图有界缓存，避免每次搜索重读所有图片。
- 点击图片打开非模态预览，支持适应窗口/100%/缩放/复制/另存。主卡片直接提供复制图片按钮。子窗口及原生标题栏随应用主题更新。
- 启动检查、设置中的手动检查、主窗口通知条共享一个更新状态机。可稍后、跳过版本、关闭启动检查；手动检查仍可查看已跳过版本。
- Windows 平台版本优先于发布列车的全局版本。本机预览高于公开源时明确显示预览状态，不误报最新、不降级。
- 下载有大小限制、SHA-256 校验、缓存复用和失败重试，失败不覆盖已验证包。跳转仅允许已知 HTTPS 下载源。
- 用户点击更新并重启后，助手等待当前进程退出，原子替换 EXE、保留回滚副本并重启；失败保留/恢复原版本，并在下次启动显示原因。不触及 Library、Host 同步队列或 Share 配对数据。
- 更新进度独立刷新，不反复重建历史列表或抢搜索焦点。

## 交互预览

对话预览包含“轻量悬浮”“展开查看”两种布局，支持搜索、图片、更多菜单、右滑、固定/置顶、清除/撤销、主题与更新演示。客户端当前落实轻量悬浮；展开查看是供用户选择的设计方案。预览中的复制/下载/重启动作仅改变演示数据。

## 验证

| 检查 | 结果 |
| --- | --- |
| Windows DesktopChecks | 36 PASS；PNG alpha、DIBV5、emoji、真实 TextChanged、非模态/主题、清除/撤销、启动通知、隔离 EXE 替换与篡改拒绝 |
| Windows LibraryChecks | 80 PASS；包含真实 CurrentUser DPAPI、图片重载、保护、校验与失败保留 |
| Windows UpdateChecks | 28 PASS；版本比较、失败/离线状态、去重、跳过与自动检查持久化、下载校验与重试、上次安装失败通知 |
| C# 同步核心 | 58 PASS；C#/Java 实际 loopback、持久连接、ACK、断连重启、latest-only 与 no-echo |
| 交互预览 | 浏览器确认搜索、更新状态、清除保护/撤销及布局切换 |
| Windows 发布构建 | SDK 10.0.402、运行库 10.0.13，self-contained win-x64 单文件构建成功；仅原有 ProtectedData NU1510 提示 |

同步相关的 NativeClipboardSync / ClipboardSyncEngine / SyncProtocol / TransportCrypto 已与远程通过检查的源码逐一核对 SHA-256，一致。未重写已稳定的传输与授权机制。

候选输出：`analysis/windows-0.7.3-review/FloatClip.exe`；ZIP 为 `analysis/FloatClip-Windows-0.7.3-review.zip`。尚未覆盖已安装版本，未对真实历史做清除，也未执行真实在线升级。真实断电/重启、目标应用粘贴及不同 DPI 仍应在候选实机体验时核验。

ZIP SHA-256：`b9662bce7f444438c333dbb34abbb3f131ca40615f51859dce41e46e7b14ce00`；EXE SHA-256：`3b439d904cf5c023a7569c1733cf573ed1ff2676b60fd917c7b0634073ff4065`。ZIP 已完整回读测试。试运行候选前，先从托盘退出旧版；单实例机制会优先激活已经运行的版本。

公开更新 feed 仍为 0.7.1/build 14（2026-10-22 已读取核对），因此新候选显示“当前为预览版本；发布源仍为 0.7.1”是正确状态。候选得到 UI 反馈后再准备正式发布与更新源。
