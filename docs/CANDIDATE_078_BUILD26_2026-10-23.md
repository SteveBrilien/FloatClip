# FloatClip 0.7.8 / build 26 候选记录（2026-10-23）

本候选从 `ca01aaa` 的 0.7.8 build 25 有效应用基线继续，并包含交接文档提交 `65c9f43`。工作分支仍为 `codex/wpf-design-system-077`。公共更新源保持 0.7.1，本候选未发布到自动更新 feed。

## 本轮完成

- Windows 的纯色 / 磨砂 / 透明改为**整个应用级**背景策略：剪贴板、文件、设备、设置和辅助窗口共用同一模式；正文与卡片不使用整体透明度，系统能力不足时回退纯色。透明模式切换仍要求重启窗口。
- Windows 分类支持重命名、删除、特征色和更多 Fluent 图标；删除分类只把记录移到“未分类”，不删除内容，固定 / 置顶状态保留。Android 同步补齐分类重命名、特征色和图标。
- Android 剪贴板图片与文字按同一历史顺序混排；图片单击直接打开应用内大图预览。主界面与悬浮剪贴板共用单栏 / 双栏偏好。
- Android 长截图卡片按顶部可读区域展示；大图预览支持双指缩放、双击缩放和拖动。
- Android 文件页将“预览 / 打开”改为独立的**应用内只读预览**。当前支持图片、PDF、ZIP 目录和常见文本；ZIP 不解压，文本限制 1 MiB，图片解码受尺寸/像素上限约束；分享仍使用独立 ACTION_SEND 入口。
- 已授权 LAN 会话完成 HELLO 后会主动发布文件目录，避免文字链路已就绪但文件清单仍等待下一次刷新。
- Android `versionCode` 与 Windows 内部 build 均提升到 26；版本名保持 0.7.8。

## 自动验证

### Windows / 共享核心（Orange Pi / Linux 交叉构建）

- `FloatClip.Desktop` Release 交叉编译：0 warning / 0 error。
- `FloatClip.DesktopChecks` Release 交叉编译：0 warning / 0 error。该项目只完成编译，未在 Linux 上冒充 WPF 运行时验收。
- `FloatClip.LibraryChecks`：82 项通过，覆盖 vault、冲突与分类迁移；DPAPI 仍要求 Windows。
- Share core：120 项通过。
- Java / C# 同步、持久 socket、断线重连、durable cursor、no-echo 与跨语言 loopback 回归通过。
- Windows UpdateChecks：44 项通过；未安装任何在线包。

### Android

- `testDebugUnitTest`：66 tests / 0 failures / 0 errors / 0 skipped。
- 新增 4 项回归覆盖：分类重命名/删除保留内容和保护状态；实际文本/ZIP 有界预览；图片+文字混排和共享布局偏好；长图初始缩放与拖动边界。
- `lintDebug`：`No issues found.`，0 error / 0 warning。
- `assembleDebug` 成功；APK `versionCode=26`、`versionName=0.7.8`。
- APK signer SHA-256：`0368593f82ab885737b99d78c939111a1ec8ea3d88e8a67b2c3c9a4bd90c3f2c`，与既有 Android 升级身份一致。
- `zipalign -c -P 16 -v 4` 通过。

## 候选制品

### Windows x64

- 路径：`analysis/deliveries/FloatClip-Windows-0.7.8-build26.zip`
- 大小：78,885,581 bytes
- SHA-256：`ae7848532fc16828fa21b2f3361a6f7aa0cc274123293ab6a48e70c2b74e0a99`

### Android

- 路径：`analysis/deliveries/FloatClip-Android-0.7.8-build26.apk`
- 大小：3,743,816 bytes
- SHA-256：`68930fd41c25b5518a14b1eb8f2fc880a3369429ad3b40939345f01d15934f6d`

制品通过 MCP Artifact 服务另行发布下载链接；签名 URL 不写入公共仓库。

## 仍未验收 / 不得扩大结论

- ADB 当前只有 `127.0.0.1:25557 offline`；旧无线地址 `10.33.221.78:39885` 从小主机无路由。因此本轮**没有**完成 vivo Android 11 真机安装、主界面/悬浮窗手势、长图缩放或手机 ↔ Windows 实际文件往返验收。
- Windows WPF 的真实 DWM/Mica/透明材质、窗口拖拽/焦点、Windows 剪贴板、蓝牙，以及 100/125/150/200% 等实际 DPI / 跨屏仍需 Windows 主机。Linux 交叉编译和协议测试不能替代这些项目。
- 公共更新仍是 0.7.1；build 26 只是候选，不更新 `clip.wmy-cloud.cn/release.json` 或 GitHub 公共更新清单。
- 本轮没有扩大图片跨设备协议为“剪贴板图片自动同步”；文件页与共享文件通道仍按既有边界处理。

## 下一轮真机验收顺序

Android ADB 恢复后优先验证：覆盖安装 build 26 → 分类重命名/删除 → 图片混排与单/双栏 → 主界面与悬浮窗大图缩放拖动 → 文件页 PDF/ZIP/文本/图片应用内预览 → LAN 文件目录及手机到电脑实际下载。

Windows 主机恢复后优先验证：全应用背景范围与重启语义 → Light/Tokyo Night → 分类编辑/删除 → Emoji/图片与全部清除保护语义 → 100/125/150/200% DPI → LAN/蓝牙实际链路。
