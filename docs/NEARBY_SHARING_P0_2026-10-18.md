# FloatClip 近场共享 P0：蓝牙优先的能力验证

日期：2026-10-18（Asia/Shanghai）

## 已确认方向

用户已同意推进验证阶段。校园网可能禁止设备互访，因此调整为：蓝牙提供近场保底通道；Wi-Fi Direct 在经验证能保留上网时提供大文件加速；普通局域网可达时复用现有连接。

## 本轮开发范围

新增独立的 nearby-probe Android 测试模块，包名 com.floatclip.probe，以及 Windows 端蓝牙测试工具。仅传递程序生成的测试文字，验证 Unicode、原始空白、分帧、摘要、双向 ACK 和往返耗时。使用系统安全 RFCOMM，不提供不安全连接降级。

这不是正式同步协议或正式客户端。本轮不接入真实系统剪贴板，不迁移历史，不改 FloatClip 主应用覆盖层或稳定签名。Wi-Fi Direct 只做能力识别，不自动建立网络或改变路由。后续在硬件实测后决定通道策略。

## 当前设备状态

- MCP ADB 未列出可用设备；既有两条回环通道均未连接成功，已知校园网设备地址当前不可路由。
- Windows Remote Desktop Commander 已登记设备为 Offline。
- 所以本轮可验证构建、线缆无关的协议实现与错误路径，不能声称蓝牙实机互通或 Wi-Fi 并发已通过。

## 保留既有工作

仓库此前有 README、app/build.gradle.kts、MainActivity、ClipboardOverlayService、EntrySwipeActions、OverlayPreferences 的未提交修改，以及 0.5.6 验收和原生球规划文件。后续添加内容保持这些文件的当前哈希不变；新增模块只在 settings.gradle.kts 注册，并补充构建目录忽略规则。

## 设备验收顺序

1. 手机与 Windows 均在线可操作；记录 OS、蓝牙网卡和驱动版本。
2. 系统设置完成两端配对，确认普通网络连接保持不变。
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
| Windows 工具包 | x64 交叉构建、PE 格式、ZIP 完整性及公开下载字节校验通过；尚未在 Windows 真机执行 |
| 稳定 P0 签名 | 已固定项目本地独立测试签名，与 APK 签名核对一致；不使用原 App 正式私钥 |
| 真机蓝牙、耳机共用、对上网影响 | 未执行：手机 ADB 和 Windows 远控设备均未在线 |
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
- 本轮仅在构建机执行 C# 纯协议自测，Windows 蓝牙 P/Invoke 尚待真机验收。

### 代码入口

- Android：`nearby-probe/`。
- Windows：`windows/FloatClip.NearbyProbe/`、`windows/Start-NearbyProbe.ps1`。
- 运行说明：`windows/README.md`、`windows/README.zh-CN.txt`。
- 验证脚本：`scripts/test-nearby-wire.sh`、`scripts/test-nearby-dotnet.sh`。
- 固定测试签名：`scripts/create-nearby-debug-key.sh`、`scripts/build-nearby-probe.sh`。
- CI 已增加测试模块构建/lint、Java 检查和 C# 黄金字节互通；线上结果以具体 workflow 运行记录为准。

已修正旧连接断开/超时与新连接启动之间的取消竞态，防止旧会话取消新会话。此部分完成源码审查和编译，仍需实机快速断连/重连回归。

下一步是恢复手机 ADB 与 Windows 远控后，按本文顺序做真实 RFCOMM 联测，再决定后台运行与 Wi-Fi Direct 正式实现。不能把本轮编译及协议自测标为实机通过。
