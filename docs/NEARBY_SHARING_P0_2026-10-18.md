# FloatClip 近场共享 P0：按设备能力选择通道

日期：2026-10-18（Asia/Shanghai）

## 已确认方向

用户已同意推进验证阶段，并在 2026-10-18 明确选择 Wi-Fi 为主。实机检查中当前 Windows 未识别到蓝牙设备，用户指出电脑可能只有外接 Wi-Fi 网卡。蓝牙因此只作为可选能力，不能作为所有设备的共同前提。现有局域网实际可达时优先复用；两端都有可用经典蓝牙时才启用 RFCOMM；Wi-Fi Direct 在两端兼容且验证保留上网后作为候选。蓝牙发现不能成为局域网发现或配对的必备条件。网络隔离且没有其他可用直连通道时，需要另选共同网络、增加硬件或另行设计中继；当前尚未实现网络回退或中继。

## 本轮开发范围

新增独立的 nearby-probe Android 测试模块，包名 com.floatclip.probe，以及 Windows 端蓝牙测试工具。仅传递程序生成的测试文字，验证 Unicode、原始空白、分帧、摘要、双向 ACK 和往返耗时。使用系统安全 RFCOMM，不提供不安全连接降级。

这不是正式同步协议或正式客户端。本轮不接入真实系统剪贴板，不迁移历史，不改 FloatClip 主应用覆盖层或稳定签名。Wi-Fi Direct 只做能力识别，不自动建立网络或改变路由。后续在硬件实测后决定通道策略。

## 当前设备状态

- Windows 远控已恢复：Windows 10 Enterprise LTSB 2016，build 14393，x64，PowerShell 5.1。
- 手机通过这台 Windows 的 USB ADB 已授权连通：vivo V2115A，Android 11；无需校园网互访。之后恢复了经典 TCP 5555，原 supervisor 2.2.18 自动经 Azure 恢复 OrangePi 正式 SSH ADB 25555；MCP 已实测 device / V2115A / PD2115，transport_id 712。FRP 不作为本次恢复路径。
- Windows PnP 和 CIM 两路检查均未发现蓝牙设备，蓝牙支持服务处于停止状态；没有发现带错误状态的蓝牙设备。记录为“系统未识别”，不能单凭此证明物理上完全没有硬件。
- 手机已安装 com.floatclip.probe 0.1.0-p0，所需 Android 11 蓝牙权限已授予，系统蓝牙开启；App 冷启动成功。
- 原版 EXE 无参数显示用法并正常返回 0；原版和修复版协议自检均通过，修复版独立控制台可持续运行。
- 蓝牙两端收发仍待有可用适配器的 Windows 实机；没有把协议自检或 USB 连通标成蓝牙互通。

## 保留既有工作

仓库此前有 README、app/build.gradle.kts、MainActivity、ClipboardOverlayService、EntrySwipeActions、OverlayPreferences 的未提交修改，以及 0.5.6 验收和原生球规划文件。后续添加内容保持这些文件的当前哈希不变；新增模块只在 settings.gradle.kts 注册，并补充构建目录忽略规则。

## 设备验收顺序

1. 手机与 Windows 均在线可操作；记录 OS、网卡和驱动，按 LAN、经典蓝牙、BLE、Wi-Fi Direct 分别探测能力。
2. 仅在两端都有可用经典蓝牙时进行本轮 RFCOMM 配对；缺少适配器时明确提示，并记录后续需验证的网络通道，不能静默假装蓝牙可用。
3. 手机测试程序选择明确的已配对目标，手动开启监听；Windows 连接同一服务 UUID。
4. 双向各发程序生成的测试帧，核对 ACK 摘要、超时、断连和取消状态。
5. 保持校园 Wi-Fi，比较测试前后的默认网络和可用上网状态；分别测试有/无蓝牙耳机。
6. 再研究 Wi-Fi Direct 并发；支持 Wi-Fi Direct、支持 STA/AP 并发均不等于已验证 STA/P2P 并发。
7. 通过后才接入正式配对身份、剪贴板采集与后台生命周期。

## 证据参考

- https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices
- https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- https://learn.microsoft.com/en-us/windows/win32/bluetooth/bluetooth-and-socket-options
- https://learn.microsoft.com/en-us/windows/win32/api/ws2bth/ns-ws2bth-sockaddr_bth

构建、协议验证及产物结果在本文件后续补充。

## 本轮结果（2026-10-18）

| 验证 | 结果 |
| --- | --- |
| 独立 Android 测试模块编译与打包 | 通过；com.floatclip.probe / 0.1.0-p0，minSdk 28 / targetSdk 35 |
| Android lint | 通过，0 错误 / 0 警告 |
| 原 FloatClip 主应用构建 | 通过；此前 6 个已修改文件保持原哈希 |
| Java 协议回归 | 168 项通过，包括非空截断前缀、分段读取、合并帧、长度越界、损坏内容及 ACK 匹配 |
| C# 编译/协议/结构布局 | 通过；SOCKADDR_BTH 30 字节、地址偏移 2 |
| Java / C# 字节互通 | PROBE 和 ACK 黄金字节逐字节一致，保留中文、Emoji、Tab、CRLF 和尾部空白 |
| Windows 工具包 | x64 构建及 ZIP 校验通过；已在 Win10 LTSB 2016 真机通过 EXE 自检、交互入口和 PowerShell 编译自检 |
| 稳定 P0 签名 | 已固定项目本地独立测试签名，与 APK 签名核对一致；不使用原 App 正式私钥 |
| 真机蓝牙、耳机共用、对上网影响 | 未执行：本次 Windows 未识别到蓝牙适配器；USB ADB 和远控已恢复 |
| Wi-Fi Direct 并发 | 未执行；支持标识不代表并发已通过 |
| 后台采集/后台保活/耗电 | 未验证；本轮 App 前台运行，切后台结束连接 |
| 正式身份与真实剪贴板/文件 | 后续阶段；本轮不启用 |

### 产物与构建依据

- Android：2467446 字节；SHA-256 `bc50d7d59fd911824c57b359ee7bccfaf2ba4a22cdff94e55fea0072f9a90fb8`。
- Android 签名证书 SHA-256：`0368593f82ab885737b99d78c939111a1ec8ea3d88e8a67b2c3c9a4bd90c3f2c`；独立包名可与原 FloatClip 共存。
- P0 私钥位于被忽略的项目工具目录，权限 0600；后续本机构建保留该签名，以便覆盖更新。CI 构建仍是独立测试产物。
- Windows ZIP：31,929,674 字节；SHA-256 `057d5ed4c45094cd4a14c0786bda562ca4cc4fc23e40027a697d876302b693c7`。
- Windows 包含自包含 x64 EXE、PowerShell 入口、C# 源码和中英文说明。PowerShell 入口使用系统编译器，不要求另装 SDK。
- Android 最终构建：`task-floatclip_nearby_p0_build-6ed99997781449f989b9`。
- C# 自测：`task-floatclip_nearby_windows_test-e7c8b7ed4c8f4f808c5a`。
- Windows 打包：`task-floatclip_nearby_windows_package-07cc64f2d1604de9bad6`。
- C# 纯协议自测现已在构建机和 Win10 LTSB 2016 真机通过；Windows 蓝牙连接 P/Invoke 尚待具备适配器的实机验收。

### 代码入口

- Android：`nearby-probe/`。
- Windows：`windows/FloatClip.NearbyProbe/`、`windows/Start-NearbyProbe.ps1`。
- 运行说明：`windows/README.md`、`windows/README.zh-CN.txt`。
- 验证脚本：`scripts/test-nearby-wire.sh`、`scripts/test-nearby-dotnet.sh`。
- 固定测试签名：`scripts/create-nearby-debug-key.sh`、`scripts/build-nearby-probe.sh`。
- CI 已增加测试模块构建/lint、Java 检查和 C# 黄金字节互通；线上结果以具体 workflow 运行记录为准。

已修正旧连接断开/超时与新连接启动之间的取消竞态，防止旧会话取消新会话。此部分完成源码审查和编译，仍需实机快速断连/重连回归。

下一步是按设备能力分别安排验收：有蓝牙的 Windows 做真实 RFCOMM 联测；当前无可识别蓝牙的 Windows 先验证局域网可达条件，并单独规划网络传输原型。不能把协议自测标为蓝牙实机通过。


## Windows 双击启动问题跟进

用户反馈 Win10 程序一闪而过。源码确认旧版无参数启动只打印用法并返回 0，双击会立即关闭窗口；后续在 Win10 LTSB 2016 上复现并取得退出码 0，原版 --self-test 也通过。蓝牙传输仍需另外验收。

修复：EXE 无参数且连接交互终端时进入中文交互控制台；可输入 test 自检、手机地址连接、q 退出。连接异常后保持窗口，输出完整异常及具体失败的 socket 选项，并将错误保存到 %LOCALAPPDATA%\FloatClip\NearbyProbe\Logs。新增 Start-NearbyProbe.cmd，可保留 EXE 进入托管入口前发生的启动错误。原命令行调用保持退出码行为。

验证：C# 协议与 Java 字节一致性自检通过；交互入口执行 test 后 q 正常退出，重定向无参数调用正常显示用法；win-x64 自包含包构建及 ZIP 校验通过。最初入口检查仅运行于构建机；随后已补做下节的 Win10 实机验证。手机校外连接恢复使用 Windows USB 与原 Azure SSH 通道，未继续依赖旧校园地址。


## Win10 与 USB ADB 实机复核（2026-10-18 21:00 起，Asia/Shanghai）

- 原版无参数退出码 0，原版 --self-test 输出 PASS。修复版 --self-test、--help、输入 test 后 q 的交互流程均返回 0；独立启动的无参数控制台保持运行并可响应。PowerShell -SelfTest 使用此系统自带编译器通过。
- 原 PowerShell -List 在没有 Bluetooth PnP 类时输出底层设备查询错误。已改为明确的无设备提示；当前机器实测完成且没有底层查询堆栈。无配对地址时另给出配对提示。
- Android 冷启动 Status ok，耗时约 706 ms，取得 App 进程；UiAutomator 返回 null root，因此本次不声明完整 UI 验收通过。测试后已返回 ChatGPT。
- USB ADB 当前已授权可用。用户随后要求修复手机到小主机的通道；通过已授权 USB 恢复经典 TCP 5555 后，原 balanced / auto 的 supervisor 自动经 Azure 重建正式 SSH carrier。已在小主机多次核对 device、V2115A 和 PD2115，不修改隧道版本、不启用 FRP 自动回退、不添加公网端口映射。
- 当前 PC 无可识别蓝牙，不能完成双向 RFCOMM。外接 Wi-Fi 不保证同时提供蓝牙；后续通道选择必须允许纯有线/纯 Wi-Fi 的 Windows。当前 P0 仍只有蓝牙传输，LAN/Wi-Fi Direct/中继尚未实现。


## Wi-Fi 主通道与后续范围

用户已确认 Wi-Fi 为主。实测 Windows 的 Ugreen USB WiFi 正连接手机热点；手机热点与 Windows 在同一私有子网。恢复 TCP ADB 后，Windows 通过热点地址直连手机成功，查询得到 V2115A / PD2115。这证明当前热点的 IP 路径可用，不能据此声称 FloatClip 已实现网络同步。

后续先实现复用现有网络的独立 LAN 测试通道，再验证普通 Wi-Fi / 有线混合网络、校园隔离与 Wi-Fi Direct。局域网直连和 Wi-Fi Direct 都可以不依赖蓝牙；Wi-Fi Direct 的支持与并发上网仍须针对这张网卡实测。本轮没有启用 Wi-Fi Direct，没有改网络连接、DNS、代理、默认路由或 Windows 网络过滤配置。远控禁用 netsh，网卡盘点采用只读 PowerShell 查询，未修改该限制。

更新后的 Windows P0 包为 31,933,039 字节，SHA-256 `4ec123f09590159dcc727b45622c067172121df11785c295eeb2c961d6b314df`；EXE 与已完成实机测试的启动修复版相同，增加了已在无蓝牙设备环境验证的 PowerShell 友好提示。交互错误恢复测试用无效地址触发参数错误后继续自检成功，错误日志写入成功；此项不代表蓝牙 socket 已验证。
