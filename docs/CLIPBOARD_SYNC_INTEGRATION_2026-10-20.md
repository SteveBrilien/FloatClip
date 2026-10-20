# FloatClip 同步核心接续记录

2026-10-20，Asia/Shanghai。状态：**开发分支，未发布，不能视作完整产品验收通过。**

## 接续依据与保留的工作

21:25 用户要求参考另一会话的工作继续推进。本次完整读取 `FLOATCLIP_HANDOFF_2026-10-20.md`（交接提交 `bcec1ef`），并比较远端工作区与本会话断线前的 SHA-256 基线，而不是用旧副本覆盖新源码。

已接入并保留另一会话的修复：

1. `DeviceActivity` 的 managed 架构归属不再取决于总共享开关，关闭共享不会退回旧 listener。
2. 清除设备授权不再同时关闭总共享。
3. 关闭 90 秒配对窗口不再同时断开已授权连接。
4. managed 页面不能再进入旧 FCP2 / 临时邀请连接流程。
5. 服务启动时清理凭据已经撤销的同步元数据。
6. 保留新增 `DeviceManagedModeTest`，以及其 Android / Windows Gate 结果。

`Pairing.cs` 的另一个变化仅为删除文件末尾空行，也已保留。未修改或纳入本轮提交的其他用户工作：`DEVICE_ACCEPTANCE_0.5.6.md`、`NATIVE_FLOATINGBALL_UX_PLAN_2026-09-29.md`。

## 本次继续修复

- Android 关闭配对窗口立即撤销尚未完成的确认，避免等待 actor 队列时继续接受新授权。
- Android 发送线程与接收入口直接复核已保存的发送 / 接收开关，避免 Service 更新配置前短暂沿用旧权限。
- Windows 启动时清理已经没有配对凭据的同步记录；凭据损坏时保留损坏文件，不静默替换。
- Windows 尚未取得设备名称或同步 HELLO 时，也可以移除旧凭据；增加明确的授权重置入口，保留剪贴板历史。
- Java / C# 增加系统剪贴板写入的权限批次。关闭再开启写入、接收或共享开关后，旧的 UI 队列任务不能重新生效；撤销 / 暂停单设备也会使旧任务失效。
- 仅接收模式中的本机私有复制只撤销旧写入权限批次，不递增共享逻辑时钟；连续 100 次本机私有复制后仍能正常接收下一条远端更新。敏感、空或超限内容也撤销旧排队写入，不进入同步待发队列。
- Windows 启动时记录系统剪贴板序号基线，现存内容可收录到本机历史，但不当作应用运行期间的新复制自动发送；与 Android 服务的启动基线保持一致。
- Windows 区分本机持久化失败与对端身份异常：保存失败不确认送达，不把设备凭据永久标成身份失效。
- PWA 已配对恢复在异步读取本机凭据期间被关闭时，不得重新拉起旧连接。
- PWA 远端接收权限批次变化时，丢弃旧待确认文字；对端撤销配对后，本机可以清理已经失效的记录。

- 修复 FCP3 的发现接收路径：单播地址绑定在回环实验中不能接收定向广播。新增分别绑定所选本机地址和该接口定向广播地址的接收 socket，回复仍固定从本机地址和 46240 端口发送；不使用通配地址。厂商不允许广播绑定时保留热点网关单播发现。新增仅在 127/8 发送的实际 UDP 回归检查，Android 厂商网络栈仍需设备验收。

## 当前实现与控制语义

原生 LAN 使用经 TLS / 配对凭据认证的 FCP3 会话，FCS1 内增加同步消息。首次核对后保存设备授权；重连重新进行传输握手，不重新接受未知身份。连接归 Service / 应用级管理器所有，页面关闭不拥有连接。

| 操作 | 预期行为 |
| --- | --- |
| 关闭总共享 | 停止连接与收发，保留配对凭据；暂停期间不积累以后补发的私人内容 |
| 关闭自动连接 | 保留已经建立的连接，停止后续自动连接尝试；仍允许用户主动连接 |
| 关闭发送 | 不把新复制内容发送出去，清理未确认的旧发送内容；本机历史独立可用 |
| 关闭接收 | 不接收新剪贴板条目，轮换接收授权批次，旧在途消息不能在恢复后生效 |
| 关闭接收后写入系统剪贴板 | 继续保存接收历史，不写系统剪贴板；旧排队写入不会在重新开启后执行 |
| 暂停某设备 | 保留其配对，停止该连接上的剪贴板数据流 |
| 关闭配对窗口 | 只拒绝新增设备，不中断现有授权连接 |
| 移除配对 | 撤销该信任并关闭会话，不顺带关闭总共享；下次需重新核对 |

原生同步具有稳定设备身份、幂等条目编号、Lamport 顺序、逐设备接收游标与内容校验、latest-only 待发、5 分钟重试有效期和接收授权 epoch。先保存接收历史及游标，再发送应用 ACK；接收写入系统剪贴板带自身写入标记，不重新当作本机复制广播。待写入任务还要复核最新胜出条目与当前权限批次。

原生空闲连接每 30 秒轻量保活，90 秒无接收帧关闭并等待重连；通过系统剪贴板事件捕获，不轮询读取剪贴板。Windows 自动发现限定 Wi-Fi 或用户配对时明确选择的物理适配器；不修改代理、路由、热点或防火墙，也不扫描测力设备网段。

## PWA 的持久配对

首次双方核对识别码后，浏览器从该次 ECDH / HKDF 握手派生非导出的 HMAC 关系密钥，并在 IndexedDB 保存。节点只保存路由标识及哈希化的两端连接凭据，不保存关系密钥或剪贴板正文。

恢复时双方生成新的临时 ECDH 密钥。关系密钥验证绑定双方临时公钥、角色、路由和本轮 nonce 的证明，身份验证通过后才恢复数据会话；不复用上一次 AES 会话密钥与从零重启的计数器。远端稳定设备编号也必须与首次记住的身份一致。

已实现前台恢复、手动移除配对、发送 / 接收控制、接收授权批次、接收历史持久化后 ACK。此通道需要节点可达、登录仍有效；不能替代没有互联网时的原生 LAN。iPad 的系统剪贴板读写仍由用户点按，未实现网页后台监听。

## 支持范围必须准确表达

| 路径 / 能力 | 当前证据与缺口 |
| --- | --- |
| Android ↔ Windows 原生 LAN 文本同步 | 双语言行为、真实回环 socket、重启 / 重连 / ACK 已验证；当前设备实测未完成 |
| Windows 10 ↔ Windows 11 原生直连 | Windows 目前承担发现与发起连接，Windows 监听 / 被发现角色未完成，不能宣称 Windows ↔ Windows 已支持 |
| Android ↔ Android 原生直连 | Android 目前承担监听端，Android 主动发现 / 连接角色未完成 |
| 同一手机连接多台电脑 | 可同时持有已授权会话；目前收到的内容不自动向第三设备转发，不能据此宣称多设备组内内容全量互通 |
| iPad PWA ↔ 其他网页 / 原生端跨网网页入口 | 实际 HTTP / WebSocket + WebCrypto 恢复流程已通过；iPadOS 16 的 IndexedDB 密钥保存与前后台恢复仍需真机验收 |
| 原生常驻服务 ↔ PWA 节点的自动同步 | 原生 LAN 与网页节点仍是两套传输适配；跨网入口中的手动共享不等于原生后台复制已接入节点 |
| 网页 WebRTC “设备直连” | 仍是旧临时连接，不具备本轮经节点通道的持久授权恢复；统一产品入口前须收敛或补齐 |
| Android 11 任意 App 后台复制捕获 | 前台 Service / 悬浮窗不是通用读取权限；无障碍只是机会性捕获，不能保证。需要 vivo / OriginOS 真机结论与明确支持路径 |
| 本轮开关界面 | 属于开发验证入口，发布前须纳入统一应用壳；不能视作最终设计 |
| Windows 开机自启 | 未加入系统启动项；自动重连以应用 / 管理器已经运行为前提 |
| 全量 UI / 自适应 | 架构方向已同意；单一应用壳、统一组件 / 字体 / 圆角 / 亮暗模板及各尺寸视觉验收仍未完成 |

因此，“一次配对后两点同步”已经有可验证实现，**完整的多设备共享产品仍未完成**。接下来必须补齐连接角色、受信设备组路由 / 来源与授权模型，以及原生节点适配，不能用 UI 把这些缺口遮住。

## 验证记录

本轮冻结源码的同步协议检查：

- Java Share core：120 项。
- Scoped discovery：5 项实际回环 UDP 广播 / 单播 / 回复源地址与端口检查。
- Java ClipboardSync：43 项，包含排队消息失效、撤销、开关快速恢复、私人本机复制等回归。
- C# ClipboardSync：35 项，与 Java 格式一致。
- Java ↔ C# 实际回环 socket：双向精确文字、ACK、断线、同步内核重启、仅最新值补发；不访问实体网络接口。
- Windows clipboard library：32 项持久化 / 来源 / 冲突检查，测试使用注入的 AES codec，不能冒充真实 DPAPI 验收。
- PWA TypeScript：类型检查通过。
- PWA 浏览器协议 / 加密 / 实际 HTTP + WebSocket：28 项。
- 节点 Python：9 项，包含持久授权存储、凭据不以明文写入 SQLite、撤销角色及连接。
- PWA 生产构建：通过，未部署。

最终 Android / Windows Gate 结果与开发提交记录在文末补充。中间 Android `350e00270ddc45a8807e` 已通过 assemble / unit test，因新增权限批次修复而在 lint 阶段主动取消；不能将其标为源码测试失败或最终通过。

## 下一步验收与发布边界

ADB 查询结果仍为 `devices=[]`。不能把回环连接或编译通过写成 vivo 与真实 Windows 已验收。

设备可用后验证：首次核对 / 凭据保存、离开设备页、锁屏 / 唤醒、App / Service 重启、DHCP 变化、Wi-Fi ↔ 热点切换、连续双向复制、同时复制、暂停与恢复、撤销授权、Clash TUN。仅使用明确选择的 Wi-Fi / 热点，不触碰小主机的测力设备网口。

下一开发阶段按以下顺序继续：

1. 把配对仓库从单一方向的 pin → token 扩展为已认证设备及其可用连接角色。凭据与设备名称 / IP 分开，升级不丢现有授权。
2. 实现 Windows 被发现 / 监听与 Android 主动发现 / 连接，先通过回环 TLS 验证 Windows ↔ Windows、Android ↔ Android 及双端同时重连的去重。
3. 先完成逐对已授权的直接互通：每对设备首次核对一次，之后自动重连；不因为设备处于同一网段就自动信任，不把只与 A 配对的 B 自动授权给 C。
4. 通过全连接的受信设备集验证多设备同时复制，不通过把收到内容伪装成本机复制来转发；需要设备组邀请 / 授权传播时，另行明确模型并评审。
5. 原生 Service / 应用管理器增加节点传输适配，用同一条目、来源、开关和接收游标承载 LAN / 节点切换。保持原生本机与 LAN 无登录，节点认证只是可选通道的授权。
6. 核心角色与传输适配通过后，按已同意的架构统一界面，提交各端亮暗与不同尺寸预览供用户逐项确认。

本轮不修改正式版本号、更新 feed、签名分发或线上 `clip.wmy-cloud.cn`，不自动安装测试包。用户尚未确认具体界面与候选版本，故不得发布。

## 开发提交与源码核对

- `5fe15b6`：Java / C# 同步协议、持久会话、权限批次、真实 socket / UDP 回归。
- `795c891`：Windows 应用级连接、凭据管理、接收历史与开关入口。
- `76b9975`：PWA 关系密钥、前台恢复、新会话认证与权限 / 持久化检查。
- `97c9914`：私有复制撤销排队写入，但不推进共享时钟；补齐单向共享回归。
- `6238867`：Windows 排除格式与空剪贴板事件也撤销旧排队写入。
- `775828b`：Android 常驻服务、独立配对控制、另一会话的 managed 生命周期修复与广播发现适配。

最终实现源码树 SHA-256 为 `d27a7fb765746a3222da15aa3661ac08637ad1d983dcaa57b7c2095693a5a17c`，覆盖 app/src、nearby-probe/src、share-core/src、windows、web/lib、web/app、web/server、web/tests 中的源码及测试文件，按相对路径与逐文件 SHA-256 排序拼接计算；排除 bin、obj、build、__pycache__、node_modules。最终 Gate 后复核该值。

构建过程：先前 1 GiB Gradle heap 下 lint 的耗时显著增加；最终验证独立使用 `floatclip_sync_android_verify_3g`，Gradle heap 3 GiB、任务总内存上限仍为 8 GiB，未修改项目默认构建参数。另两个中间 Android job `2b0defba57724843b3ff`、`4b30ad2b65614adc9016` 因广播修复与构建参数调整主动取消，不能列为最终通过。

补充取消记录：`task-floatclip_sync_android_verify_3g-12cd4fa4fece412d92c8` 在 assemble / 17 项 unit test 后，因新增“仅接收模式的私有复制”回归修复而取消，最终采用后续冻结源码的 Gate；不将此前任务标作最终通过。

最终冻结前的私有复制修复使 `12cd4fa4fece412d92c8` 的结果失效，该任务已主动取消。最终 Android Gate 为 `task-floatclip_sync_android_verify_3g-31a0f18b9b7c41a0ad46`；Windows Gate 为 `task-floatclip_sync_windows_dev-8d236066ed1c4a828096`。Windows 已成功完成 self-contained win-x64 publish，且没有新增 C# 编译警告；Android 结果在下节补充。


### Android lint 耗时诊断

后续定位到，耗时不能仅归因于 JVM heap。项目范围内的 Gradle 线程采样显示 `WorkerExecutor Queue` 阻塞在 `Lint.readUrlData → GoogleMavenRepository → GradleDetector.checkDependency` 的 HTTPS 连接，属于依赖版本索引查询；并非未完成编译或应用运行卡死。原 `lintAnalyzeDebug` 与 AndroidTest 分析结果已有效写入缓存，在 UnitTest 分析阶段的样本中观察到依赖查询等待；已完成的其他分析结果可复用。

冻结源码改用 `floatclip_sync_android_verify_offline`：仍执行 assembleDebug、testDebugUnitTest、lintDebug，使用已有缓存与 `--offline`，不关闭代码 detector、不新增 lint baseline、不忽略错误。联网查询依赖最新版本的结果不能据此标记为新鲜。线程采样脚本只写在 analysis，不进入应用包与发行源码；未更改设备网络、代理或路由。


### 代码告警复核

首份完整 lint 报告为 0 错误、9 警告。复核后补齐新历史广播接收器在 Android 13+ 的 `RECEIVER_NOT_EXPORTED` 标记，保留 Android 11 的兼容注册分支；同时明确使用同一个本地 Intent 变量启动 / 停止共享服务。之后再次运行同一离线 Gate，不新增 baseline 或错误忽略。

其余告警按实际报告保留：自定义 signature permission 被误提示接近系统 INTERNET 权限（包信息单元测试已核对权限名称）、旧备份 XML 资源未使用、旧存储空间建议、程序化 View 的编辑器构造方法，以及旧节点页字符串国际化。没有将这些写成“零警告”，也没有趁本次核心修复重命名权限或删除旧资源。

## 23:42 接管续记：会话上限后的状态冻结

原“整合局域网剪切板方案”会话达到长度上限后，本会话重新从仓库真实状态接管，不沿用 21:08 的旧交接结论。接管时 HEAD 已由后续会话推进到 `7a5a236`，并另有一批尚未提交的权限批次 / 快速开关竞态修复。

### 接管时已经存在的后续提交

- `5fe15b6`：持久 FCP3 剪贴板同步核心、scoped discovery 与 Java/C# socket 回归。
- `795c891`：Windows 应用级持久连接、配对凭据与同步设置。
- `76b9975`：PWA 持久关系密钥、重新认证和前台恢复。
- `97c9914`：本机私有复制只撤销旧 apply，不推进共享 clock。
- `6238867`：Windows 排除 / 空剪贴板事件保持 receive-only 同步可用。
- `775828b`：Android Service 持有连接，配对窗口与共享控制解耦，并吸收前次 managed 生命周期修复。
- `7a5a236`：Windows 启动时把系统现存剪贴板视为 baseline，只收录历史，不作为“运行期间新复制”自动发送。

### 本次接管后完成的未提交尾项

接管时工作区仍有权限批次的 generation barrier 改动。已逐项审计并完成：
- Android `SyncPreferences` 持久保存 desired / applied 的 send、receive、apply generation。
- `SyncPolicy` 在 Service 漏掉中间 off/on 事件或进程被杀后，仍会根据 generation 差异重新撤销旧授权批次。
- Android 本机复制捕获携带当时的 send generation；之后即使发送快速关闭再开启，旧捕获不能恢复为可发送内容。
- Android transport 队列给 CLIP / ACK 绑定对应权限 generation，旧队列在开关恢复后仍会丢弃。
- 接收 grant 更新单独轮换 FCP3 epoch，不破坏独立的 outgoing pending。
- apply generation 与 `applicationGrant` 双重检查，阻止旧 main-thread 剪贴板写入任务在关闭 / 重开后复活。
- Android 13+ 动态 HISTORY_CHANGED 广播明确使用 `RECEIVER_NOT_EXPORTED`，Android 11 保留兼容注册分支。
- `SyncRuntime.refresh` 使用同一个显式 Service Intent 完成启停，避免静态检查中的意图歧义。

上述源码已固定为提交：

`285c4c7 Harden clipboard policy generation barriers`

### 本次重新验证

当前冻结源码重新执行而非仅沿用旧会话结论：

- Java Share core：120 checks 通过。
- Java ClipboardSync：46 behavioral checks 通过。
- Scoped discovery：5 个 loopback UDP checks 通过。
- C# ClipboardSync：38 checks 通过。
- C# ↔ Java persistent loopback：精确文字、ACK、断开、重启、latest-only reconnect 通过。
- Windows clipboard library：32 checks 通过；Linux 测试环境的 CA1416 仅说明 DPAPI 真正行为仍需 Windows。
- Android offline full gate：
  - Job `task-floatclip_sync_android_verify_offline-2c244624792844319c0d`
  - `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug` 全部成功。
  - Artifact `artifact-79eedb53ff9b45858cd4f5a23f4dad23`。
- Windows self-contained win-x64 publish：
  - Job `task-floatclip_sync_windows_dev-70b6020e585d4f2eba02`
  - 成功。
  - Artifact `artifact-4856264fdf1641498ede1ce8b6937cf3`。
- `git diff --check` 通过。

接管时与复核后 ADB 设备均为 `devices=[]`，所以 vivo / OriginOS 真机首次配对、自动重连与真实双向复制仍不得标为已验收。

### 当前源码指纹

在提交 `285c4c7` 后，按本文既定算法对 154 个源码 / 测试文件计算：

`54c941d9973a6c25709af6393b2cadfec29140937d7a9d8c74c9135eae1fa96b`

此前文中的 `d27a7f...` 仅对应更早一次冻结点，不再代表当前 HEAD。

### 当前继续开发顺序

核心同步尾项已进入可继续扩展的稳定点。下一步保持此前已确定顺序：
1. 扩展受信设备记录，使“配对身份”与“当前 IP / 设备名 / 连接角色”解耦。
2. 增加 Windows listener / 被发现角色和 Android active discover / connect 角色；先做回环双端角色与重复连接去重。
3. 在此基础上验证 Windows↔Windows、Android↔Android 和多设备逐对已授权互通。
4. 再接原生 node transport；LAN 与 node 共享同一 Entry / provenance / policy / cursor。
5. 最后才进入已确认的单一应用壳 UI 迁移与用户逐页验收。

发布边界不变：当前开发分支不改正式版本号、不部署 PWA、不更新 feed、不发布 APK / Windows 包。
