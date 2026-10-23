# 0.7.8 / build 23 候选：WPF 交互与双端更新源

本轮从 0.7.7 WPF 继续，修复提示框清晰度、共享资源悬停/菜单/表头、系统关联图标、文件大小显示、实际内容预览、设备页嵌套与开关跳动。Windows 保留现有数据格式、原生配对与同步服务。Android 只修改共同更新客户端与版本比较，保持既有手机界面和数据兼容。

共同更新源使用 https://clip.wmy-cloud.cn/release.json 的 nativeUpdates 字段，备用为 GitHub main/updates/native-updates.json。两端固定公钥验证标准 JWS Compact RS256，核对签发/到期时间、通道，再处理既有下载地址/摘要/版本校验。元数据校验失败不通过备用源掩盖。公网清单仍指向已发布 0.7.1，未将本轮候选推为公开更新。

## 设计实施与验收范围

按八步流程先阅读唯一设计规范/变更记录和共享组件，记录提示框、表头、图标、预览、设备列表重建等差异，先改公共资源再改页面。保留 .NET 10 / WPF UI 4.3.0 / Emoji.Wpf / CommunityToolkit.Mvvm，不新增其他 UI 框架。新设备页使用 XAML + DevicePanelViewModel + ClipboardDeviceService，适配原 NativeClipboardSync；文件图标、容量与文档读取各自属于服务。

| 范围 | 本轮已验证 / 变更 | 未验证 / 边界 |
| --- | --- | --- |
| 颜色、间距、圆角、字体、图标 | 共用语义 Hover/Pressed/SelectedHover，Light 中性、Dark Tokyo Night；共享提示框/菜单/表头/设置行；新增操作图标使用 Fluent，文件行使用 Shell 原生帧；正文及状态文字对比度检查通过 | 既有 C# 视图仍有历史布局尺寸，未称全页 MVVM/全量迁移；第三方 Shell 图标不保证都是矢量 |
| 悬停、按压、滑动 | 共享资源状态；工具栏/选中行悬停更明显；保留既有左右滑、Motion Tokens 与减少动画；紧凑选项行包含完整开关热区 | 本轮输入事件检查含合成事件；真实指针、快速连续操作和动画观感由用户检查 |
| 弹层与焦点 | 提示框移除模糊 Effect；菜单统一纯色边框；搜索框外点击释放焦点；辅助预览非模态；设备菜单绑定自身设备命令 | 各弹层全部键盘路径/多屏切换尚未逐一人工验收 |
| Light / Dark 与切换 | 文件页、设备页、设置页两主题截图；颜色资源解析；辅助窗标题栏跟随主题；Tokyo Night 存储兼容 | 高对比度整页观感和全部系统弹窗未逐一确认 |
| DPI 与窗口尺寸 | manifest 使用 PerMonitorV2；真实宿主 150%；96/120/144/192 DPI 离屏输出及紧凑/常规/宽窗口回归；切页不强改宽度 | 离屏图不等于四档实际 DPI/跨屏热区验收；2016 LTSB 未支持/未验收 |
| 文本、Emoji、图片与文件预览 | 中文/英文/长文本、彩色 Emoji 与原序列复制、PNG/DIB/DIBV5；系统文件关联图标保持高分辨率；ZIP 真实目录、DOCX 段落、XLSX 单元格测试；PDF 翻页代码构建通过 | Office 仅只读文字/单元格，非完整排版；.7z/.rar/旧 Office 等未支持，预览禁用；图片跨设备同步尚未实现 |
| 剪贴板与数据 | 搜索、固定/置顶保留、清除无撤销、重启偏好、原库/配置回归；设备开关四次修改只写四次、行/VM 实例及滚动位置保持 | 不以隔离库检查代替用户全部真实历史验收；没有清除/迁移用户数据 |
| 同步、传输与材质 | 实际 TCP FCP3/FCP4 会话、双向 ACK、重连、显式文件下载/校验、文件中穿插剪贴板帧；未改原协议。材质能力检测与纯色回退保留 | 本轮未重跑手机蓝牙/真实 LAN 往返；Mica/Acrylic 未启用；Wi-Fi Direct 与 Windows 跨应用系统分享待办 |
| 构建、更新与静态检查 | Windows Release / win-x64 自包含成功，168 项桌面检查，隐藏 UI 异常 0；43 项更新检查，实际下载清单验签 44 项；Android assembleDebug / 61 项单测通过，lint 0 error / 17 warning；主备公网清单实测验签通过 | 现存 NU1510 冗余 ProtectedData 引用警告保留；Android APK 下载仍交给浏览器/系统确认，本轮未安装个人手机或执行真实自动替换 |

用户已明确本轮由其检查外观。自动检查和截图是证据，不代表用户已确认视觉结果。没有覆盖正在使用的 `D:/FloatClip/FloatClip/FloatClip.exe`。

## 前后对比证据

| 页面 | 0.7.7 基线 | 0.7.8 候选 |
| --- | --- | --- |
| 文件 Light | [基线](design/evidence-20261023/files-page-approved-light.png) | [本轮](design/evidence-078-20261023/files-page-approved-light.png) |
| 文件 Tokyo Night | [基线](design/evidence-20261023/files-page-tokyo-night.png) | [本轮](design/evidence-078-20261023/files-page-tokyo-night.png) |
| 设备 Light / Dark | 原嵌套面板与重建列表，见历史记录 | [Light](design/evidence-078-20261023/devices-refined-light.png) / [Dark](design/evidence-078-20261023/devices-refined-dark.png) |
| 压缩包内容 | 放大图标 | [真实 ZIP 目录](design/evidence-078-20261023/zip-content-preview.png) |

截图全部来自隔离夹具，不含个人历史、配对库或用户附件。检查报告与 DPI 范围在同一证据目录。共享组件定义集中于 `Design/Components.xaml`，新设备页复用设置行/开关，不为页面另建相似模板；没有新增 UI 框架依赖。

## 试用包

两端均为 0.7.8 / build 23；Windows 需 Windows 10 2004 / build 19041 或更新的 x64 系统。先退出托盘旧进程，再运行新目录，避免单实例激活旧版本。完整解压 ZIP，附第三方许可。

| 包 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `analysis/FloatClip-Windows-0.7.8-candidate.zip` | 78,794,697 | `1fa63e0559117455556deabb6fb41c1ee50de6ba34b5ba739a68f6665e35093f` |
| `analysis/windows-0.7.8-candidate/FloatClip.exe` | 207,442,758 | `85ff216fe833c54825cf56f5ac8ec04489cbb92baa1aeef12fd6f8b74d103af5` |
| `analysis/FloatClip-Android-0.7.8-candidate.apk` | 3,621,045 | `e0dea0aec8c9716535a94a047c4ebfc68f58f32a2d35259fd238af4f995ef56f` |

APK 包名 `com.floatclip.probe`，签名证书 SHA-256 `0368593f82ab885737b99d78c939111a1ec8ea3d88e8a67b2c3c9a4bd90c3f2c`，与原升级线一致。Windows ZIP 完整性及最终 EXE 的 `--qa-ui` 隔离启动通过，报告版本/build 正确，未触碰用户剪贴板。

## 更新与交接

更新入口、发布密钥保护、清单有效期及实际验证见 [原生更新说明](NATIVE_UPDATES_2026-10-23.md)。旧版需一次过渡安装；本轮候选尚未提升公开，不能把“更新源修好”描述为“0.7.8 已公开自动推送”。

后续从 `codex/wpf-design-system-077` 继续，优先复用当前设计系统；[云端续接资料](CLOUD_CONTINUATION_2026-10-23.md) 包含源文件与测试入口。小主机原工作区的其他未提交内容保留，候选源码另存独立目录，不携带私钥、用户历史或配对存储。
