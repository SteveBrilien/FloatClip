# Windows 0.7.8 / build 24：ZIP 文件名与更新超时修复

从 `d74eb911` 的 build 23 继续。已阅读 AGENTS、唯一设计规范、变更记录及现有服务，最小方案只改服务及关联测试/工程引用，保留 WPF + XAML、数据、配对和同步协议。Android 本轮未改，仍为 0.7.8 / build 23。

## 反馈、修复与证据

- 原 ZIP 预览使用默认 UTF-8，旧式 GBK 名称没有语言标记时被解码为替换字符。本轮先严格 UTF-8，再 GBK、CP437 回退，UTF-8 标记仍优先；读取只列目录，不解压或执行。测试覆盖 GBK 中文文件/文件夹和 UTF-8 Emoji。未标记的不同代码页存在歧义，不能声称所有旧编码都能无损自动识别；用户截图中的实际 ZIP 未作为夹具读取或上传。
- 旧客户端实际请求路径已复现主备源超时，同一电脑的独立请求可快速成功；随后也观察到旧路径恢复，未能唯一确认某次超时的底层原因，不能宣称 GitHub 或主源始终不可用。
- Windows 更新检查和下载改为微软 `System.Net.Http.WinHttpHandler 10.0.13`，使用系统 WinINet 代理（含 PAC/WPAD），避免只沿用应用启动时继承的环境代理。每源外层等待从 6 秒放宽到 12 秒；失败同时展示主备原因。保留 TLS 证书验证、RS256、有效期/通道、域名限制、手动重定向校验、SHA-256/包大小、取消及防降级。
- 更新 45 项检查通过，其中覆盖原生 Windows 代理策略、禁止自动重定向、两处错误保留、签名失败不回退；新生产请求路径连续三次验证公开清单成功（0.7.1 / build 14）。网络中断仍可能导致失败，这不是永不超时承诺。
- 已用原有小主机部署密钥实际执行 GitHub 推送，返回成功/已是最新；无需生成新密钥。本轮交付提交随后推送到原 `codex/wpf-design-system-077`，没有导出密钥正文。

## 九项验收

| 范围 | 本轮结果与边界 |
| --- | --- |
| 设计一致性 | 保留语义颜色/间距/圆角/字体/图标，共享组件不新增样式；新增依赖仅微软网络组件 |
| 行为一致性 | 预览继续非模态、仅目录读取；搜索焦点/滑动/菜单保留并回归；未做新的真实指针走查 |
| 主题完整性 | Light/Dark 原有颜色、主题切换回归随桌面检查通过；页面无新视觉属性 |
| DPI 与尺寸 | 延续 150% 真实宿主与四档离屏范围；未冒充四档物理 DPI/跨屏验收 |
| 文本与预览 | 旧 GBK 中文 ZIP、UTF-8、Emoji 夹具通过；实际个人 ZIP 未读取 |
| 数据功能 | 搜索、保护固定/置顶、清除、持久化的隔离回归通过；用户历史与已安装程序未覆盖 |
| 同步与传输 | 同一套 TCP 会话/ACK/重连/文件检查通过；真实手机蓝牙/LAN 本轮未重跑 |
| 动效与材质 | 沿用 Motion Tokens 和减少动画；没有新动效，Mica/Acrylic 维持未启用 |
| 构建与检查 | Release / 自包含成功；170 项桌面、45 项更新检查；最终 EXE 隔离启动 PASS。ShareChecks 工程同步修正共享更新文件引用并编译通过，仍有原有 DPAPI 平台警告；Desktop 保留 NU1510 |

公开更新清单仍是已发布的 0.7.1；尚未把这次候选公开推送。旧版需要先关闭托盘旧进程，再运行修复包。不能把代码修复等同于旧 EXE 已自动变化。

## 安装包

`analysis/FloatClip-Windows-0.7.8-build24.zip`：78,878,343 字节，SHA-256 `888b4a8256f4c1bdcbc09305f05480cfc215f856667284991c9321c2421a90cb`。

`analysis/windows-0.7.8-hotfix24/FloatClip.exe`：207,611,441 字节，SHA-256 `939d3bb6379a55a5a268d46c18f8e3548a6f87d48ab32c209e4c6c4eee62314a`。附原第三方许可与新增微软网络组件声明，Windows 10 2004 / 19041 或更新的 x64 系统。

自动报告在 `design/evidence-hotfix24-20261023/`。与 build 23 的 Light/Dark 前后对照沿用 [上轮截图](CANDIDATE_0.7.8_2026-10-23.md#前后对比证据)；本轮没有页面风格改动，不将未做的人工视觉验收写成通过。

参考：[.NET ZIP UTF-8 标记规则](https://learn.microsoft.com/en-us/dotnet/core/compatibility/core-libraries/9.0/ziparchiveentry-encoding)、[Windows 代理策略](https://learn.microsoft.com/en-us/dotnet/api/system.net.http.windowsproxyusepolicy?view=net-10.0)、[微软 WinHttpHandler](https://www.nuget.org/packages/System.Net.Http.WinHttpHandler/10.0.13)。
