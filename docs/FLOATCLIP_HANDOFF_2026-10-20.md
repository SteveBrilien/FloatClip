# FloatClip 接手与推进记录（2026-10-20）

> 记录目的：把“整合局域网剪切板方案”会话交接时的真实代码状态与本次接手后的工作严格分开，后续可据此继续开发、测试与回滚。  
> 接手时间：2026-10-20 20:40（Asia/Singapore / UTC+8）  
> 接手者工作分支：`feature/clipboard-sync-core-20261020`  
> 接手基线提交：`3ccde83 feat: release clipboard-first FloatClip 0.7.1 candidate`  
> 仓库：`SteveBrilien/FloatClip`  
> 当前阶段：**未发布的同步核心开发分支，不是 release candidate。**

## 1. 用户已经确认的产品方向

本轮不再围绕旧 Share 页面继续叠加功能，而是把“剪贴板”作为三端统一核心：

- Android / Windows 的本机历史与局域网连接无需网页登录。
- 第一次配对时通过发现 + 双端识别码核对建立信任；后续记住设备身份。
- 之后设备再次处于可达局域网时应自动重连，不因 DHCP 地址变化要求重新配对。
- 新复制的文字应实时同步；接收端可选择是否写入系统剪贴板。
- 总共享开关、自动重连、发送、接收、接收后写入系统剪贴板均必须可控。
- 暂停共享不得删除配对授权；撤销设备授权才需要重新核对。
- 正文是核心视觉内容，设备标识只做低权重来源信息。
- 手机、Windows、iPad PWA 采用同一套产品语义与设计规范，但按屏幕尺寸和平台交互做适配，不把手机大控件机械搬到桌面。
- UI 必须解决上一轮已经出现的：设备标识喧宾夺主、正文不居中、搜索框异常、设置 icon / 返回箭头比例不统一、亮暗主题白底白字、圆角值混乱、Windows 多套 UI 共存等问题。
- 未经重新验收，不把当前 UI 当作已通过，也不直接发版。

## 2. 接手前仓库真实状态

### 2.1 Git

接手时：
- 当前分支：`feature/clipboard-sync-core-20261020`
- `main` 与该分支都指向 `3ccde83`
- 工作区 **不干净**，存在大量未提交改动与新增文件。
- 这些改动均视为“上一会话遗留工作”，不能在没有审计的情况下覆盖或丢弃。

接手时已修改的主要文件：
- Android：Manifest、MainActivity、DeviceActivity、ClipboardOverlayService、PasteAccessibilityService、ClipStore。
- Android/共享探针：LanTls、ShareActivity。
- Java share-core：ShareSession、ShareWire。
- Web/PWA/relay：page.tsx、protocol/session/relay/relay-crypto、server、relay tests。
- Windows：ClipboardVault、LocalClipboardWindow、Pairing、ShareCore、LibraryChecks、ShareChecks。

接手时新增的主要文件：
- `app/src/main/java/com/floatclip/app/sync/`
  - ClipboardSyncService.kt
  - EncryptedSyncStorage.kt
  - SyncBootReceiver.kt
  - SyncPreferences.kt
  - SyncRuntime.kt
- `share-core/src/main/java/com/floatclip/share/ClipboardSync.java`
- Java 同步行为与 socket peer 测试。
- `nearby-probe/.../LanSyncHost.java`
- Windows：
  - ClipboardSharingWindow.cs
  - ClipboardSyncEngine.cs
  - NativeClipboardSync.cs
  - SyncProtocol.cs
  - FloatClip.SyncChecks/
- Web：`trusted-store.ts`
- 文档：UI 架构重整草案、0.5.6 验收文档等。

### 2.2 已有 P0 / 安全基础

此前已经完成且有提交的 LAN P0：
- TLS 局域网直连；
- 握手、错误指纹、错误邀请、断开重连；
- 双向文字 ACK；
- Win10 与 Android 的 P0 验收记录；
- 核心实现提交 `34e1ca5`，验收文档提交 `c5b5ee3`。

当前新同步实现是在该 P0 上继续演进为 FCP3 持久授权 + 同步协议，而不是重新设计加密方案。

## 3. 接手时已存在的新同步实现

### Android

`ClipboardSyncService` 已承担连接生命周期，Activity 不再持有连接：
- 前台 Service 常驻；
- 监听 Android ClipboardManager 的本机复制事件；
- 读取 `SyncPreferences` 总开关 / 发送 / 接收 / 写入 / 自动重连设置；
- 通过 `LanSyncHost` 在 Wi-Fi / 热点本机地址上监听 FCP3；
- 首次配对只在 90 秒显式窗口内开放；
- 已授权凭据保存在 `share-pairs`，暂停共享不会清除；
- 已授权连接可自动恢复；
- 接收条目进入 `ClipStore` 历史；
- 可选写入系统剪贴板；
- 通过私有广播刷新主剪贴板历史；
- Boot / package replaced 后可按设置恢复同步服务。

`SyncRuntime` 已把主界面、设备页、浮窗、无障碍桥接到同一同步服务入口。

### Java share-core

`ClipboardSync` 已实现：
- 每设备 HELLO；
- 本机/远端 send/receive 能力协商；
- latest-only 持久 outbox；
- ACK + 内容 hash；
- 重放保护；
- epoch 隔离；
- 暂停 / 恢复；
- peer enable；
- 本地 clock 与冲突比较；
- 重启后恢复 pending；
- 接收历史与“是否应用到系统剪贴板”分离。

### Windows

`PairStore`：
- 使用当前用户 DPAPI 保存配对 token；
- 最多 8 台；
- 原子写入；
- 损坏状态 fail-closed，不静默覆盖。

`NativeClipboardSync`：
- 独立于设备设置窗口持有连接；
- NetworkAddressChanged 唤醒自动重连；
- 保存自动连接与上次所选物理网卡；
- 仅发现 Wi-Fi 或用户配对时明确选择的物理适配器；
- 对已授权设备执行局域网 discovery；
- 身份校验失败后阻断自动死循环，要求重新核对；
- 10 秒同步 outbox retry；
- 关闭设置窗口不会结束已授权会话。

`ClipboardSharingWindow` 已提供：
- 总共享开关；
- 自动连接；
- 发送；
- 接收；
- 接收后写入系统剪贴板；
- 已授权设备开关与移除配对；
- 手动发现 + 首次核对。

## 4. 接手时仍明确未完成 / 未验收

以下均不能宣称完成：
- Android 新同步 Service 的完整 build/lint/unit test gate；
- Windows Desktop 在当前脏工作区上的完整 publish gate；
- Android ↔ Windows 真机局域网持续自动重连；
- Windows 真实系统剪贴板 hook 的实时双向同步；
- Android 11 在后台时“读取剪贴板”的能力边界真机验收；
- Wi-Fi 切换、DHCP 地址变化、热点切换后的自动恢复；
- 同时存在旧 Share 页面与新统一剪贴板页面时的 UI 收敛；
- Windows 的 WPF ResourceDictionary / ControlTemplate 重整；
- 亮暗主题全控件验收；
- 搜索框、设置 icon、箭头、圆角、卡片正文视觉层级的最终统一；
- iPad PWA 与原生持久 LAN 协议的能力对等；
- 正式版本号、签名、APK/ZIP 发布。

## 5. 本次接手后的工作日志

### 20:41–20:44 环境与仓库确认

- 运行 MCP developer doctor。
- 确认项目实际注册名为 `floatclip`。
- MCP 2.1.25-orange.1 正常，网络与项目沙箱正常。
- Android SDK、aapt2、ADB、JDK17 capability 均通过 fresh validation。
- 仓库允许 commit / push，默认远端为 `SteveBrilien/FloatClip`。
- 未进行任何发布或部署。

### 20:42 代码审计

- 读取 `SHARE_PRODUCT_CONTRACT_2026-10-20.md` 与 `UI_ARCHITECTURE_RESET_2026-10-20.md`。
- 确认 0.7.1 只是上一版候选基线，UI 重整文档明确禁止把“构建通过”当成 UX 通过。
- 审计 Android sync runtime/service、Java sync core、LanSyncHost、Windows pairing/sync engine。
- 决定保留上一会话已有实现，先验证再修，不重写协议。

### 20:42–20:43 协议测试

已通过：
- Java Share core：120 checks。
- Java clipboard sync：34 behavioral checks。
- C# clipboard sync：26 checks。
- C# ↔ Java loopback persistent socket：
  - exact text；
  - ACK；
  - disconnect；
  - process restart；
  - latest-only reconnect；
  - Java/C# payload 兼容。

执行过程中出现的两次失败属于**测试命令调用问题而非代码失败**：
1. 手动 dotnet 命令把 `NUGET_PACKAGES` 设成相对路径，.NET 拒绝；
2. 第二次遗漏 SyncChecks 所需 vectors 参数，导致 Program args 越界。
均已修正，随后跨语言检查通过。

Android focused test 的手动 shell 调用被 `build-orangepi.sh` 的 host Java 路径检查拦截，原因是该手动命令没有使用 TaskProfile 注入的 canonical JDK 环境；已改为使用项目既有 durable TaskProfile 验证。

### 20:44 构建 Gate 启动

已启动：
- `floatclip_sync_android_dev`
- `floatclip_sync_windows_dev`

结果待后续日志补充。

## 6. 本轮继续推进的优先级

P0（本轮必须先做）：
1. 让 Android / Windows 当前同步分支完整 build/test 通过。
2. 找出自动重连、实时监听和开关语义中的实际 bug。
3. 对暂停、重启、断网、DHCP 地址变化、重复消息做可重复自动测试。
4. 把继承工作与本轮修复形成独立提交。

P1（核心通过后）：
1. 把设备连接状态接回统一剪贴板主界面。
2. 开始 Windows WPF 单一 Shell + ResourceDictionary / ControlTemplate 收敛。
3. Android 继续拆除 DeviceActivity 旧 Share UI 依赖，保留连接 service。
4. 做亮/暗主题、搜索框、icon、箭头、圆角、正文层级的统一验收。

## 7. 发布边界

当前禁止直接发布：
- 当前工作区未 clean；
- 当前分支不是稳定 release 分支；
- UI 仍未经用户重新验收；
- 真机自动重连尚未完成；
- 版本号与安装包签名快照尚未冻结。

只有完成本文件 P0、形成 clean commit、通过自动化 gate，并在用户确认 UI 后，才进入下一次发行流程。

### 20:45–20:53 自动化 Gate 与平台边界复核

正式 TaskProfile 结果：
- Android `floatclip_sync_android_dev`：**成功**。
  - `:app:assembleDebug`
  - `:app:testDebugUnitTest`
  - `:app:lintDebug`
  - Artifact：`artifact-a504c99d81544965b36f415a4f76de99`
- Windows `floatclip_sync_windows_dev`：**成功**。
  - self-contained win-x64 单文件 publish
  - Artifact：`artifact-8e34e35d3baa4ebfa3cc32e94c9c09ff`

补充检查：
- Windows clipboard library：32 项持久化 / 来源 / 冲突检查通过。
- LAN pairing code：8 项通过，Java/C# 64-bit 识别码一致。
- Nearby wire：168 项协议检查通过。
- 当前 ADB capability 没有连接设备（`devices=[]`），因此本轮不能把 Android 真机自动重连、OriginOS 剪贴板行为或后台复制声明为已验收。

Android 11 平台边界重新核对：
- Android 官方安全文档明确指出 Android 10（API 29）起，后台进程不能读取前台应用剪贴板。
- AOSP Android 30 `ClipboardService` 在分发 `OnPrimaryClipChanged` 前同样调用 `clipboardAccessAllowed(OP_READ_CLIPBOARD,...)`，普通应用通常要求 UID 处于 focus；默认输入法、系统权限等是例外。
- 因此“前台 Service 常驻 / 悬浮窗存在”本身不能证明 Android 11 能在其他 App 前台时持续捕获复制内容。
- 现有 AccessibilityService 会做机会性捕获，但 AOSP 权限判断并未把普通 AccessibilityService 列为通用豁免；OriginOS 是否有厂商差异必须真机验证。
- 结论：**协议、连接与 Windows 侧实时监听目前成立；Android → Windows 的任意 App 后台复制实时同步仍是设备验收项，不能提前宣称完成。**

参考：
- Android Developers · Secure Clipboard Handling: https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling
- AOSP Android 30 ClipboardService: https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/+/refs/heads/androidx-camera-release/com/android/server/clipboard/ClipboardService.java

### 20:53 控制语义缺陷修复

发现一个继承实现中的真实 bug：

- 旧设备页“移除所有设备授权”原来调用 `disconnect()`。
- 在新的 managed connection 模式里，`disconnect()` 会进一步调用 `pauseManagedConnections()`。
- `DeviceActivity.pauseManagedConnections()` 会把 `SyncPreferences.enabled` 写成 false。
- 结果是“撤销授权”意外等于“关闭总共享开关”，违反已确认的产品契约：**撤销信任与暂停共享必须是两个独立操作**。

本次修复：
1. `ShareActivity` 增加 `clearManagedTrust()` 托管钩子。
2. managed 模式清除授权时不再调用 `disconnect()`，只撤销授权。
3. `DeviceActivity.clearManagedTrust()` 同步清空 `share-pairs`，不修改 `floatclip_sync.enabled`。
4. `ClipboardSyncService` 初始化 `ClipboardSync` 后，以配对凭据为权威，自动清理“授权已撤销但 sync state 仍残留”的 peer，防止服务重启后出现幽灵设备或旧 pending。
5. 非 managed 的旧 Share 兼容模式保持原逻辑，不改变其前台会话生命周期。

修复后已重新启动 Android / Windows 正式 build gate，结果见后续记录。

### 20:54–21:01 架构串线修复与回归测试

继续审计后发现，旧 Share P0 页面与新的持久剪贴板同步仍存在三处会“串回旧架构”的路径。这些问题会让开关语义和用户看到的状态不可信，因此本轮优先修复，而不是继续叠加 UI。

#### A. 总开关关闭时错误回退旧 Share 生命周期

原实现中 `DeviceActivity.managedConnections()` 返回 `SyncPreferences.enabled()`。这实际上把两个完全不同的概念混在了一起：

- “这个页面属于新的 managed clipboard-sync 架构”；
- “用户当前是否开启总共享开关”。

当总开关为 false 时，`ShareActivity.onStart()` 会误以为当前不是 managed 模式，从而启动旧的前台 LAN listener；也就是说“关闭共享”有机会退回旧 Share P0 路径，违背总开关含义。

修复：
- `DeviceActivity.managedConnections()` 固定返回 true，表示架构归属，不再代表运行状态。
- 总开关只由 `SyncPreferences.enabled` + `SyncRuntime.refresh()` 控制。
- 总开关关闭时设备页显示“共享已关闭”，不会启动旧 listener。
- “添加设备”在总开关关闭时只提示先开启共享，不偷偷开启 service / legacy listener。
- `onResume()` 始终绑定 managed observer；状态显示与 service 是否启用独立处理。

#### B. “暂停发现”错误等于“关闭整个共享”

旧设备页的“暂停发现”复用了 `disconnect()`。在 managed 模式中 `disconnect()` 会进一步调用 `pauseManagedConnections()`，最终把总开关写成 false。

修复：
- 新增 `stopManagedPairing()` 托管钩子。
- `ClipboardSyncService.closePairing()` 只把 90 秒 pairing window 置为关闭。
- 关闭配对窗口不会删除授权、不会断开已授权会话、不会关闭自动重连、不会修改总开关。
- managed UI 用词改为“关闭配对窗口”；真正关闭整个功能才叫“暂停共享”。

#### C. 高级页仍可回退临时邀请 / FCP2

新的持续剪贴板同步要求“首次授权一次，之后自动重连”，但旧高级页仍暴露“生成临时邀请链接”，并可进入旧的前台临时 Share 会话。

修复：
- managed 模式的高级页不再进入旧地址选择 / 临时邀请连接逻辑。
- managed 模式只展示当前局域网地址、90 秒配对窗口和网络诊断。
- `listen()` 增加 managed hard guard：即使调用路径错误，也不会退回 legacy listener。
- legacy standalone ShareActivity 仍保留原临时邀请能力，避免破坏兼容测试工具。

#### D. 页面文案同步到新架构

修正仍描述旧架构的文案：
- 不再写“离开应用会暂停 / 10 分钟后重新连接”；
- managed 设备页明确“已授权设备自动重连”；
- 明确区分“关闭配对窗口”和“暂停共享”；
- 明确 Android 11 的后台剪贴板读取限制，避免把常驻连接误写成常驻读取能力。

#### E. 新增回归测试

新增 `app/src/test/java/com/floatclip/app/DeviceManagedModeTest.kt`：
- 初始总开关明确为 false；
- 启动 `DeviceActivity` 并进入设备页；
- 必须显示 managed 的“添加设备”；
- 必须显示“已授权设备会在局域网内自动重连”；
- 绝不能显示 legacy 的“允许附近设备连接”；
- 打开设备页不得静默把总开关重新打开。

该测试已进入最终 `:app:testDebugUnitTest` gate，并通过。

### 21:01 最终源码冻结 Gate

为了保证构建检查对应“最后一行源码”，中间有两次 Android gate 在源码继续修改后被**主动取消**，不是测试失败：
- `task-floatclip_sync_android_dev-b0739011865e4306a9db`
- `task-floatclip_sync_android_dev-9b6edc5d0a7b44198c3b`

两次都已经通过 assemble / unit test，停在 lint 阶段；取消原因只是后续修复使正在运行的 gate 不再对应最终源码。

随后冻结源码并启动最终 gate：
- Job：`task-floatclip_sync_android_dev-0054e35dba6a43d1af87`
- 已通过 `:app:assembleDebug`
- 已通过 `:app:testDebugUnitTest`（包含新 `DeviceManagedModeTest`）
- `:app:lintDebug` 最终结果见下一条记录。

Windows 在本轮 Android 控制语义修复前后的源码未发生新的功能改动，最近一次正式 publish 仍成功：
- Job：`task-floatclip_sync_windows_dev-8ab69e089fb04a37823a`
- Artifact：`artifact-e4e89166d1c34ad0b6faa7279ef4d508`

当前 `git diff --check`：通过，无 whitespace error。
当前仍无 ADB 设备连接；真机验收边界不变。

### 21:08 最终 Gate 结果

最终冻结源码对应的 Android gate：**成功**。

- Job：`task-floatclip_sync_android_dev-0054e35dba6a43d1af87`
- Artifact：`artifact-2ae22ba3246543ba8bff278f8700192e`
- `:app:assembleDebug`：通过
- `:app:testDebugUnitTest`：通过
- `:app:lintAnalyzeDebug` / AndroidTest / UnitTest：通过
- `:app:lintReportDebug`：生成成功
- `:app:lintDebug`：通过
- Gradle：`BUILD SUCCESSFUL`
- 54 actionable tasks：16 executed，38 up-to-date

因此截至本记录：
- Java Share core 120 项：通过；
- Java ClipboardSync 34 项：通过；
- C# ClipboardSync 26 项：通过；
- C# ↔ Java persistent socket / restart / ACK / latest-only reconnect：通过；
- Windows clipboard library 32 项：通过；
- Pairing code 8 项：通过；
- Nearby wire 168 项：通过；
- Windows self-contained win-x64 publish：通过；
- Android final assemble + unit test + lint：通过；
- `git diff --check`：通过。

## 8. 接手结束时的精确状态

### 已经真正推进完成的部分

1. 保留并验证上一会话写入工作区的 FCP3 持久剪贴板同步骨架，没有重写协议。
2. 验证 Java / C# 双端协议、ACK、重启、latest-only outbox、持久游标和跨语言 payload 兼容。
3. 验证 Android / Windows 当前源码均可完整构建。
4. 修复“撤销设备授权误关闭总共享”的控制语义。
5. 修复“总开关关闭后设备页退回旧 Share listener”的架构串线。
6. 修复“关闭发现误关闭整个共享”的控制语义，拆出独立 90 秒配对窗口。
7. 阻止 managed 持久同步从高级页回退旧临时邀请 / FCP2。
8. 让 managed 设备页文案与真实持久 Service 生命周期一致。
9. 新增防止 managed → legacy 回归的 Robolectric 测试。
10. 明确并记录 Android 11 后台读取剪贴板的系统限制，避免后续把“连接常驻”错误等同于“任意 App 复制都能后台读取”。

### 仍需真机 / 真 Windows 验收的部分

当前 ADB 没有连接设备，因此以下不能宣称已经完成：
- vivo V2115A / OriginOS 上首次配对与识别码核对；
- 一次授权后 App / Service / 手机重启是否自动恢复；
- DHCP 地址变化后是否无需重新配对；
- Wi-Fi ↔ 手机热点切换后的恢复；
- 手机 → Windows 和 Windows → 手机连续实时文字同步；
- 接收后写系统剪贴板时是否完全无 echo / loop；
- 总开关、发送、接收、自动重连、接收后写入、单设备开关的组合行为；
- OriginOS 是否对 AccessibilityService / 剪贴板读取提供 AOSP 之外的能力；
- Windows 真实系统剪贴板 hook、托盘/窗口关闭后的持续同步。
    
### UI / 发布状态

- 这轮优先修核心连接和控制语义，没有把未验收的 UI 直接发版。
- `UI_ARCHITECTURE_RESET_2026-10-20.md` 仍是 UI 收敛基线。
- Windows WPF 单一 Shell、统一 ResourceDictionary / ControlTemplate、亮暗主题、搜索框、icon / 箭头、圆角和正文视觉层级仍是下一阶段。
- 当前分支仍故意保持 dirty：包含上一会话大量尚未分批提交的实现，以及本次修复。
- 不应把这些继承改动一次性伪装成本轮单一 commit；后续应按“协议核心 / Android runtime / Windows runtime / UI / tests”拆分整理。
- 当前仍不满足正式发布条件；不要基于本记录直接升级线上版本号或发布 APK / Windows 包。

### 下一步建议顺序

1. 手机重新接入 ADB 后，优先做 Android ↔ Windows 真机首次配对 + 双向文字闭环。
2. 做“断网 → DHCP 变化 → 重连”“Service 重启 → 重连”“关闭配对窗口但授权设备继续连接”的故障注入。
3. 根据 vivo OriginOS 的实际剪贴板权限表现，决定 Android → Windows 的捕获入口：系统 ClipboardManager、Accessibility 机会性捕获，或明确降级交互。
4. 真机同步核心通过后，再开始统一 Android / Windows UI Shell，避免 UI 重构掩盖连接问题。
