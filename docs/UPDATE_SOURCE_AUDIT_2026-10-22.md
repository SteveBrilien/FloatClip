# 更新源核实与自托管迁移准备（2026-10-22）

## 已核实的现状

- Windows `windows/FloatClip.Desktop/ShareUpdates.cs` 的版本清单地址仍为 GitHub Raw 的 `updates/share-preview.json`。仅接受 HTTPS 且最后跳转主机必须为 raw.githubusercontent.com；因此不能只把常量改成小主机地址，还必须同步调整严格的地址策略。
- 小主机仓库中 Android `nearby-probe/.../ShareUpdates.kt` 也仍从相同 GitHub 预览清单检查版本，开发通道从 `share-dev.json` 检查。Android 本轮只读核实，未修改或打包。
- 安装包与版本清单不同：当前公开清单的 APK / ZIP 下载地址已经指向 mcp.wmy-cloud.cn 的不可变公开制品链接。迁移重点首先是版本清单入口。
- 2026-10-22 10:59 UTC，从 Windows 开发机实测：GitHub 清单 HTTP 200 / 0.59s，现有 `https://clip.wmy-cloud.cn/release.json` HTTP 200 / 1.02s，拟用 `https://clip.wmy-cloud.cn/updates/share-preview.json` HTTP 404 / 1.12s。小主机侧独立检查结果同样为 200 / 200 / 404。报告见 analysis/desktop-checks/update-source-probe.json；诊断只读取公开元信息，未获取安装包正文或用户内容。
- GitHub 清单仍为 0.7.1；现有 release.json 是 Web 发布信息，并非原生更新协议清单，不能作为原生客户端的简单替代。未发布候选代码不会仅因检查成功就出现在更新中。
- 本次网络检查成功不能证明历史失败原因。GitHub 的访问波动、DNS、TLS、HTTP 错误或超时均有可能；需要客户端将实际失败类别留在可见状态中。

## 本轮已落地

Windows 更新检查现在分别显示连接超时、网络 / DNS / 安全连接失败和 HTTP 状态错误，保留原有 15 秒超时、64 KiB 清单上限、平台 / 通道校验、允许主机、包大小及 SHA256 校验、版本防降级和主动取消行为。增加可注入 HTTP 客户端的内部测试入口，33 项 UpdateChecks 通过，不连接真实安装流程。

生产更新地址、公开清单、已安装 EXE、Android 及服务器均未切换。不能把当前未部署的 404 地址写成可用更新源。

## 迁移方案与顺序

1. 复用已存在的 HTTPS 入口 clip.wmy-cloud.cn，在专用只读路径提供预览 / 开发清单。只允许明确文件名，响应 JSON、无登录要求、缓存必须重新验证；不挂载项目目录、主机磁盘、应用状态或剪贴板库。当前 Web 静态路由按允许列表拒绝 updates 路径，需要专门实现和测试。
2. 在客户端信任链准备好之前不切换正式源：增加带签名的更新清单，客户端内置受信公钥，私钥在离线发布侧保管，不放公网小主机。清单包含平台 / 通道、版本 / 构建、包哈希 / 大小、可信 HTTPS 下载地址和有效期，并防降级。优先使用成熟的签名元数据规范和库，不能随意拼接 JSON 自创签名序列化。
3. HTTPS 保护传输，SHA256 验证下载与清单一致，但如果清单和安装包同时被替换，单独哈希校验不能验证发布者身份；受信签名提供这一层保护。Windows 候选目前有 SHA256 / 大小校验，清单本身尚未签名，不应声称这一项已经完成。
4. 公网小主机为主、GitHub 为备用，两处提供同一份签名元数据。仅在连接 / 服务不可用时尝试备用；签名不合法、通道不匹配或发现回退版本时拒绝更新，不能通过切换源掩盖安全校验失败。缓存只能显示上次成功校验时间，全部源失败时不能声称已是最新。
5. 所有版本包使用不可变路径。先上传并校验包，再原子替换清单，避免客户端拿到未上传完的包。现有公开制品服务可继续提供安装包，不必迁移整个应用或另开文件系统共享。
6. Android 要保持现有包名与签名升级线，下载安装仍由系统验证；目录协议、手机界面与更新源适配是不同任务。当前本会话负责 Windows，可准备共同清单协议，Android 实装需由负责该端的工作跟进。
7. 旧客户端仍只认识 GitHub 地址，需要一次过渡版本或手动安装新版才能切换，单纯部署新服务器不会自动改变旧客户端。迁移前同时验证 Windows / Android 所用网络、断网、主源宕机、过期 / 篡改 / 降级元数据及旧包安装回退。

## 稳定性边界

现有 clip 公网链路为 Azure HTTPS 入口 → 专用 SSH 隧道 → OrangePi。改成自托管可以绕过 GitHub Raw 的访问依赖，但小主机关机、隧道掉线和带宽也会影响检查 / 下载；不能笼统保证自托管一定更可靠。GitHub 镜像和明确的失败提示用于降低这一影响。

参考：The Update Framework 官方 overview，https://theupdateframework.io/docs/overview/；Android 官方应用签名说明，https://developer.android.com/studio/publish/app-signing。
