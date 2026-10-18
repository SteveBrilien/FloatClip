# FloatClip Share 0.3.0：发现、配对、TUN 与更新

日期：2026-10-19（Asia/Shanghai）。仍为独立共享预览包 com.floatclip.probe，与原 FloatClip 共存。

## 连接与授权

Android 在选定现有物理接口开放 90 秒发现窗口；设备页可暂停或重新开放。Windows 优先选择实体 Wi-Fi，排除 TUN/TAP/VPN 等虚拟网卡。每次查找至多三轮，每轮一个当前子网广播和一个当前网关请求，3.5 秒结束；不逐地址扫描。热点网关单点请求兼容不接收广播的手机热点。

Windows 点击设备后，两端首次核对四组、共 16 个十六进制字符的识别码并确认。它是包含实际 TLS 证书、发现周期 ID 与双方随机数的 SHA-256 前 64 位，不是六位密码；发现响应不作为身份认证。最多三个连接尝试，TCP/TLS 15 秒、用户确认 60 秒，且受总发现窗口约束。

确认后在 TLS 中签发 32 字节随机授权。Windows 以当前用户 DPAPI 保存凭据并绑定证书指纹；Android 仅在应用私有偏好保存凭据 SHA-256，且禁止备份。双方各最多八个授权。再次连接仍校验固定证书，通过 TLS 提交凭据，可免去核对。可从任一端移除全部已记住设备；手机端撤销后旧凭据拒绝，不自动降级成新的无确认连接。

当前没有每设备重命名/逐项移除、后台自动同步、PC-PC、Wi-Fi Direct 或中继。手机切后台断开，系统文件选择期间保留有限会话。文字 64 KiB、单文件 64 MiB、接收缓存 128 MiB、文件内容 2 MiB/s 预算、会话最长 10 分钟。备用邀请保留为折叠入口。

## TUN 与网络诊断

实际 Win10 LTSB2016：Ugreen USB Wi-Fi 192.168.246.159/24、手机热点网关 192.168.246.144；Meta Tunnel 198.18.0.1/30。WLAN 的 /24 直连路由存在，同时存在 Meta 与 WLAN 默认路由。Clash Verge/verge-mihomo 运行中。旧 192.168.144.* 已变更，不能当作当前地址。

直接 TCP 套接字不依赖 HTTP 系统代理，但仍可能受 TUN 路由影响。新增本地物理 IP 与 Windows IP_UNICAST_IF 绑定，发现/连接使用同一网卡。界面提供网卡选择/刷新、TUN 检测、当前 CIDR 与可复制排除片段，不自动关闭代理或修改路由/DNS/防火墙。应用不能承诺穿过 VPN 强制阻断或校园网客户端隔离。

排除片段须合并到已有配置、保留原列表。只建议当前热点 /24；不把 DIRECT 规则当作全局 TUN 下必然有效的替代。不能仅凭 Meta 运行就认定历史失败一定由 TUN 导致。

官方依据：
- https://www.clashverge.dev/guide/bypass.html
- https://wiki.metacubex.one/config/inbound/tun/#route-exclude-address
- https://learn.microsoft.com/en-us/windows/win32/winsock/ipproto-ip-socket-options
- https://learn.microsoft.com/en-us/dotnet/api/system.security.cryptography.protecteddata

## 检查更新与下载

设置页主动“检查更新”，独立使用 share-preview 通道，避免误选原应用 0.5.x。固定更新源：
https://raw.githubusercontent.com/SteveBrilien/FloatClip/main/updates/share-preview.json

信息最大 64 KiB，检查通道、结构、构建序号、版本及下载域名，仅允许 github.com/mcp.wmy-cloud.cn 的 HTTPS 默认端口。按 Android/Windows 返回各自链接。错误显示重试，不宣称已是最新版。下载按钮通过系统浏览器打开对应 APK/ZIP，不自动运行、替换目录或绕过系统安装确认。

发布：构建验证 → 不可变版本快照及 SHA-256 → 无预设到期时间的版本链接 → 更新同一 manifest → 提交推送。可覆盖 build 输出和短时签名 URL 不得直接用于长期更新源。同版本内容冲突须升版本，不覆盖已发快照；发布被撤销、文件删除或服务停用仍可能导致下载不可用。

## 验证边界

Java 核心 120 项；C# 119 项（含网络边界、非法下载源、配对向量）；Java 配对 8 项。双方公共配对向量 `8067 FAAD F0E2 7FA0`。Windows 0.3.0 在实际 Win10 以浅/深主题启动并渲染自身内容。

实机首次/重连/撤销/重试及双向生成文件另见最终证据；不以核心测试冒充本轮 TLS/UI 验收。测试仅使用生成文字和文件，不访问用户真实剪贴板/文档。

OrangePi 有线 LAN 是测力专网，不扫描、探测、改路由或作为中继。本轮实机通信仅手机热点 ↔ Windows。保留原应用、悬浮球及六个既有未提交文件。

## 本轮最终状态（2026-10-19 02:46 +08:00）

- 代码实现与候选构建完成；Java 核心120项、C#119项、Java配对8项，共247项核心断言通过，Android最终lint0错误0警告。它们不能代替最终真机验收。
- Windows早期0.3.0候选在本机Win10实际以浅/深主题运行并截图检查；手机上已安装早期0.3.0候选，设备/设置导航的真实UI层级可读取，手机原主题仍为system。用户切换到其他应用后，坐标操作的前台检查阻止了后续点击，未继续抢回前台。
- 完整应用会话尚未验收通过。Windows生成数据测试多次在认证/开始收发阶段超时或断开，首次、记住后重连、撤销与重试整套验收没有完成；不得把它们标为PASS。最后候选的阶段记录只到tcp-connected，后续握手未完成。根因仍未确定。
- 同一Win10、同一手机热点、Clash TUN保持运行时，独立的系统.NET同步TLS对照探针完成UDP发现、固定证书TLS1.2、FCP2首次确认、接收授权及39字节生成Android文字帧。分散写入与指定物理接口的对照也成功。因此不能声称TUN完全阻断了网络，也不能以探针成功替代桌面应用成功。
- 已合并帧写入，减少网络小写入；客户端增加阶段记录和可取消的TCP/TLS超时。修订后的实际应用仍待最终APK安装后重新验收，不将修改等同于故障已修复。
- 最终APK SHA256为dcaee2e1e8d9f46a4f34c7d1438cf58ba1aec343e2e15cca095c4638110823bb，2770019字节；本次替换安装被手机返回INSTALL_FAILED_ABORTED: User rejected permissions。该拒绝之后没有再试安装或绕过确认。最终手机包尚未装上，本机仍是较早同版本候选。
- Windows最终ZIP SHA256为6c9fb6cdc6fbc41add6c976e4e5b9866d8b6f80d4341d0af2a51f6b741120257，58851630字节。候选包放在独立QA目录；原C:\\FloatClip内0.2.0进程未覆盖或结束。
- 两个版本快照的无预设到期链接均以公开下载返回HTTP200，完整字节及SHA256一致。更新源属于share-preview，更新说明明确联合验收待完成；没有后台自动替换/执行。
- 后续最小动作：用户手动确认安装最终APK后，针对同一候选重新验证应用TLS/收发，再完成记住设备、撤销及两端主题/更新检查。当前不宣称已具备稳定共享能力。
