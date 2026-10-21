# FloatClip 续接记录（2026-10-21）

## 接手基线与用户要求

- 接手对象：项目内“项目交接记录”会话，继续其已经确认的功能。
- 分支：`feature/clipboard-sync-core-20261020`；接手 HEAD：`f6909f8`。
- 用户要求：东京夜色深色主题、按压与展开/收回动画可调、右滑保护、透明度/雾化/边线/主题色开关、分类图标选择、修正二级菜单重复返回箭头、清理最近任务后尽量保持服务。
- Android 继续使用现有原生 View 和保留窗口宿主，不做框架重写。
- 保留“界面先验收、具体候选允许后再正式发布”的既有要求；本次不更新公开 APK、Windows 包、PWA 或更新源。

接手前先把全部 tracked diff 和新增文件保存在 `analysis/takeover-20261021T101853Z/`。继承代码由 `ce7c7ef` 单独固定，不将前会话的工作算作本次新实现。两份历史未跟踪文档（0.5.6 验收和 9 月原生球计划）保持原样。

## 继承实现的实际范围

| 项目 | 代码状态 | 仍需验收 |
| --- | --- | --- |
| 东京夜色 | Android 主界面、设备页、弹窗和浮窗已有色板 | vivo 实际显示、跨页切换 |
| 动画参数 | 卡片/球/拖动缩放、展开收回时长、开关时长 | 快速操作、触摸误触、收回抖动 |
| 保护记录 | locked 字段、右滑操作、批量清空保留、导入导出 | 真机手势与备份闭环 |
| 外观 | 面板/区块透明度、雾化强度、边线和主题颜色 | 实际透明叠层下可读性 |
| 图标 | 分类图标内置选项及持久化 | 已选分类与浮窗各入口展示 |
| 后台 | overlay 与 sync 各自保活设置、任务移除和启动恢复入口 | vivo 最近任务清理、系统回收、重启 |
| 通知 | 简略/详细常驻通知切换 | 系统通知栏实际表现 |
| 更新 | 预览/开发测试通道选择 | 开发 feed 尚未公开发布，不能视为已有可用更新 |

分类图标选择不等于任意图标导入；当前范围是内置分类图标。Android 11 的“雾化”是透明表面的颜色混合，不是真实跨应用背景高斯模糊。

## 本次新修复

### 保护记录容量

本机 addText 原来新增后直接 take(500)，会在库满时挤掉保护记录。现在仅淘汰最旧的普通记录，置顶和保护均保留。500 条全受保护时拒绝新收录，不修改原数据；同源重复内容仍可刷新。新增回归覆盖容量、全保护、重复复制和两种清空行为。

### 双向滑动收回

反向滑动已展开的菜单，现在先回到关闭位置；同一手势不越过零位再打开对侧。取消手势必须关闭，也不能触发复制。用真实 MotionEvent 驱动的 Robolectric 回归覆盖左右展开、反向关闭和取消。

### 主题与动画生命周期

- 东京夜色浮窗之前仍重算为旧的浅白文字，现在使用与主题一致的正文/次要文字以及卡片表面。
- “恢复默认外观”现在同时恢复设备特征色和半透明选项，保留通知、保活和悬浮窗启用意愿。
- 浮窗销毁/重建时取消根面板动画，避免旧动画结束回调继续操作已移除窗口。
- 关闭系统动画时，开关、球和面板的按压反馈尊重系统设置。
- 详细通知开关在重建外观时同步刷新正在运行的浮窗前台通知。
- 浮窗长按菜单补上保护/取消保护入口；既有二级菜单重复返回字符修复保留。

### 蓝牙会话竞态

发现并修复两个能造成“配对完成却未开始传内容”的竞态；它们是已证实的代码问题，但不能据此认定为历史真机失败的唯一根因。

1. BluetoothSyncHost 先调用 attached，再调用 connected。attached 的 actor 若先接受了本会话，connected 再调用 canAttach 会因当前已有连接而误关本会话。地址记录回调现仅检查 transport 存活与授权，接入是否允许由 service actor 最终裁定。
2. peerMessages 之前在创建候选回调时就替换 connectionTokens。一个被 LAN 优先级拒绝的蓝牙候选仍可能使正常 LAN 回调失效。现在只有通过接入判断后才发布新 token；关闭、撤销、禁用自动连接或已关闭的候选不能接入。

新增模拟连接回归：候选不窃取活动连接身份，被拒绝后原连接的关闭回调仍生效；地址回调不误关闭自己，但撤销授权仍关闭连接。

同步 service 也不再无条件 START_STICKY，而是尊重其独立保活开关。

## 自动化证据

- 第一轮 Android gate：`task-floatclip_sync_android_verify_offline-6bb317e0ce2442939e8e`，assemble + 34 项 unit tests + lint 通过。此轮尚未包含最后的两项蓝牙会话回归，不作为最终候选验证。
- 最终 Android gate：`task-floatclip_sync_android_verify_offline-e33a622361984120903d`，assembleDebug + 36 项 unit tests（0 failures / 0 errors）+ lintDebug 全部通过，BUILD SUCCESSFUL。
- 内部 artifact：`artifact-1b5df4de7cd64586a4c3fcffe10aaa92`；未 publish，未更新公开 feed。
- 最终 APK SHA-256：`5d7e0c5264661d1ef7038030a11e185ec1237aaaa62a3e6b2fa1fa3ebfffd919`。
- apksigner verify 通过；证书 SHA-256：`0368593f82ab885737b99d78c939111a1ec8ea3d88e8a67b2c3c9a4bd90c3f2c`。尚未与当前离线手机的已安装包对比，不能据此承诺可覆盖安装。
- Java Share core：120 checks PASS。
- Java ClipboardSync：46 behavioral checks PASS。
- TransportCrypto / first-pair：12 checks PASS。
- Scoped discovery：5 loopback checks PASS。
- C# ClipboardSync：49 checks PASS。
- C# ↔ Java loopback：exact text / ACK / disconnect / restart / latest-only / no echo PASS。
- Windows library：38 checks PASS；运行于 Linux 的测试使用测试 codec，DPAPI 仍需 Windows 真机。
- git diff --check：PASS。
- 最终源码文件摘要：`809cd433b53488dfe8748d8550dd37c249bbf298b46993f2af754999c212f148`，明细在 checkpoint 的 final-source.json。

## 设备和能力边界

当前 ADB `127.0.0.1:25557` 为 offline；Desktop Commander 的 Administrator 和 Win10LTSB 均离线。本次没有安装、点击、录屏或实测清理后台，不能宣称真机验收通过。

- 普通 TYPE_APPLICATION_OVERLAY 按 Android 规定低于状态栏和输入法。vivo 原生球的系统窗口特权不能通过调普通应用的 z 值复制；本次没有实施 SystemUI 注入。
- START_STICKY、stopWithTask=false、onTaskRemoved 和恢复 alarm 属于尽力恢复；不等于不可被 OriginOS 回收，也不能绕过用户“强行停止”。
- Android 11 第三方 App 前台复制时能否后台读取，仍按固定 vivo ROM 真机结果判断。
- 蓝牙首次授权已存在此前真机记录；本次竞态修复后双向文字传输仍未真机验证。
- Windows / iPad 的完整东京夜色和此次动画/保护功能对等未完成。

官方参考（本次核对）：
- https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
- https://developer.android.com/reference/android/app/Service

## 下次接续顺序

1. 核对候选与已安装包签名后在 vivo 覆盖安装，先核对两端识别码/授权；不得卸载清数据解决签名问题。
2. Bluetooth-only：Windows → Android → 历史；Android 内复制 → Windows；12 秒短连接释放后再次复制。区分 FloatClip 前台复制与任意 App 后台复制。
3. LAN 与 Bluetooth 同时可达、正在连接候选被拒绝、撤销授权、暂停/重开共享、断网重连，检验已修竞态和回声抑制。
4. 验收滑动/保护/清空、快速开关浮窗、固定模式、多主题/透明度和系统禁用动画。
5. 最近任务清理/进程回收/重启和保活开关组合，记录实际恢复时间及 OriginOS 设置。
6. 后续审计 Bluetooth 发送失败后的重试触发、多台设备的短连接调度与连接超时取消；当前不将短连接 transport 标为完整可靠性验收通过。
7. Windows / iPad 外观与保护功能对等、全平台界面审阅，之后再进入具体版本发布评审。

## 本次提交

- `ce7c7ef`：精确保留继承的 Android 外观/保护/保活实现。
- `39997e3`：历史容量保护及回归。
- `7d3c366`：反向滑动、东京夜色、动画/通知生命周期及回归。
- `1b5aff8`：蓝牙接入竞态、保活返回策略及两项模拟连接回归。

构建后源码哈希与冻结清单一致。最终代码无未提交改动；前会话两份历史未跟踪文档保持原样。未 push 到 main、未部署、未正式发版。
