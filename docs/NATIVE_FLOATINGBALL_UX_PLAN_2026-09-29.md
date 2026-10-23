# FloatClip × OriginOS 悬浮球：设备审计与改进路线

日期：2026-09-29（Asia/Shanghai）

设备：vivo V2115A / Android 11 / OriginOS Ocean；已安装 FloatClip 0.5.5（versionCode 10）。
本轮目标：借鉴原生球的低干扰与可靠手势，改善剪贴板密度、操作反馈和设置路径；保住 0.5.4 已被用户确认的无抖动切换。

## 1. 事实与证据

- MCP 2.1.24-orange.1 的 ADB 设备列表已显示 `10.33.221.78:5555` 为 device。新版能力包含受审计的 `adb_shell`（只读白名单）、`adb_pull`、项目图片预览、TaskProfile 构建/测试和固定动作 HostCapability；它不是任意 ADB shell。
- 手机上的 `pm path com.vivo.floatingball` 为 `/system/app/FloatingBall/FloatingBall.apk`。直接 `adb_pull` 该路径返回 `PATH_NOT_ALLOWED`；项目先前已获的忽略跟踪副本 `analysis/originos/system-apks/com.vivo.floatingball/1-FloatingBall.apk` 与此前记录的 SHA-256 `e24c914cc6e74f01922cd89385b2168226e5c425a5b212543ff4800ffdd0989e` 相同。仅本地静态分析，不提交或分发 OEM APK / 图像 / 反编译代码。
- ZIP 解包目录列出 `classes.dex`、设置布局、`idleToEdge_left/right.json` 等动画资产；已有静态符号显示 `FloatingBallService`、`FloatingBallManager`、`FloatingBallIdleView`、`FloatingBallEdgeView`、`FloatingBallDragView`、`FloatingBallExpandedView`。动画/窗口名提供结构线索，不足以单独证明帧时序或源码算法。
- 手机 `dumpsys window windows` 实测原生 `EdgeView` 是 120×210 px、`NAVIGATION_BAR_PANEL`、UID 1000，隐藏时 `ExpandedView` 保留 Window 对象而 View 为 INVISIBLE；当前球贴右边。原生 apk 版本 2.5.32.0/code 253200；包拥有 `INTERNAL_SYSTEM_WINDOW`、`READ_CLIPBOARD_IN_BACKGROUND` 等普通第三方 APK 不具备的权限。之前的两组原生窗口快照见 `analysis/originos/20260908-*`。
- 手机上已装 FloatClip 0.5.5。源代码 `collapsePanel()`、`showRetainedPanel()` 通过保留 panel/scrim/bubble 并修改 alpha/touch flags 切换；`docs/INTERACTION_0.5.4.md` 与用户确认其无抖动。0.5.5 的单击复制、双击置顶、左滑置顶/删除、长按纵列菜单都已写入代码，但设备手势验收仍欠缺。
- 现在的 App 首页先显示一个较大的“悬浮剪贴板”卡，按钮之外另列“权限与后台”和“使用帮助”；设置内还有主题选择与外观子页重复、权限页的多段解释。两处剪贴板都把分类及“已置顶”常显在每张卡片里；覆盖式菜单内同时重复“复制/置顶/删除”，信息密度偏低。分类排序使用自制 touch/translation/移除重加 View 的逻辑，视觉反馈和滚动边界需实机确认。
- 实机补测（01:44）：贴边球当前窗口 [960,1854]-[1080,2064]，点击可见触点 (1070,1955) 后 EdgeView 变 INVISIBLE、ExpandedView 变 VISIBLE 且生成全屏 Surface；点展开区外 (220,1250) 收回。展开视觉为贴球一侧的两块紧凑区域：上块两个快捷 App，下块 3×3 图标与短标签，没有操作说明长文、没有背景大范围变暗；设备图像只保存在被 Git 忽略的 `analysis/device/native-expanded-20260929.png`。这是一组起止状态实测，不代表帧级动画结论。\n

## 2. 交互契约

| 位置 | 主操作 | 次操作 | 关闭/取消 |
| --- | --- | --- | --- |
| 悬浮球 | 点击展开 | 拖动停靠、延迟半隐藏 | 面板外点击收回（未固定） |
| 词条 | 单击复制 | 双击置顶；左滑露出置顶/删除；长按纵列菜单 | 右滑、点击其他词条、面板收起或空闲超时关闭滑动操作 |
| 面板 | 分类筛选与词条浏览 | 边框/标题拖动；右上角固定面板 | 保留窗口切换，不能重新引入窗口销毁/重建和收回动画 |
| App | 首页直接控制球，剪贴板检索 | 分类管理、外观/权限/备份集中在设置 | 减少提示卡、重复导航与持续展示的辅助文字 |

需要记录点击、双击、长按、水平滑动之间的互斥：单击不得置顶，滑动不得复制，关闭菜单不得触发背后的行按钮；隐含操作仍应有一次可发现的“使用帮助”入口。删除要有明确的恢复/确认策略，避免误触造成记录丢失。

## 3. 路线和范围

### CP1：审计和路线（本文件，先完成）

确认设备/版本、静态解包、窗口权限差别、现有手势与文案。记录原生截图/展开行为的未验证项，不把猜测当验收事实。

### CP2：低风险改进（本文完成后才更改代码）

1. 压缩首页与设置冗余，保留“开关、剪贴板、设置”主路径；状态用清楚的即时状态与单个主要操作呈现，帮助文字只在主动打开时显示。
2. 词条卡片提高密度和层次：长文本截断、分类标签轻量化，置顶用一个轻量标识；滑动操作保留双按钮、右滑/外点/超时收回。长按菜单保持纵列和适中尺寸；手势互斥优先于装饰动画。
3. 分类拖动保持排序可用，改善按下、悬停、落位和自动滚动反馈；避免变更剪贴板存储格式和分类迁移语义。
4. 只调整 app/overlay View 和合法公共 Android API；window show/collapse 的保留窗口路径与数据密钥、备份格式不改。

### CP3：验证与交付

运行 debug/lint；用 ADB 核验签名/安装版本和 Overlay/Accessibility 状态；在手机检查球的展开/收回、固定、左滑双操作及取消、单/双击、长按单列菜单、分类拖动和明/暗主题；检查崩溃日志。任何设备动作前先观察当前前台；测试结束回到原先界面。只有实测过的项目标记通过，其余明确为待验收。先提供经过验证的候选构建，不把未安装或未测试 APK 称为正式发布。

## 4. 权限与法律/技术边界

- 普通 FloatClip 只用用户批准的 `SYSTEM_ALERT_WINDOW` 与可选 Accessibility；Android 10+ 后台剪贴板读取受系统限制，不能通过系统悬浮球的 UID 1000、私有 AIDL 或签名权限直接解除。Foreground Service/无障碍绑定与 OEM 后台限制分别处理；不承诺绕过 Force stop。
- Shizuku/Root/LSPosed 深度集成属于独立研究路线，必须验证本机能力、ROM 指纹和失败回退，不能作为 CP2 条件，也不请求/修改系统权限、SELinux、签名或系统 APK。
- 禁止上传用户剪贴板内容、私人屏幕或 OEM 二进制；本地二进制分析证据位于忽略跟踪的 `analysis/`，仓库文档只记非隐私的结构、哈希与结论。
- Android 官方参考：`https://developer.android.com/reference/android/view/WindowManager.LayoutParams`（overlay 层级和许可）、`https://developer.android.com/reference/android/Manifest.permission`（`SYSTEM_ALERT_WINDOW`）、`https://developer.android.com/about/versions/10/privacy`（剪贴板限制）、`https://developer.android.com/reference/android/accessibilityservice/AccessibilityService`。

## 5. 开发门禁

- 变动前后对比 `expandPanel`/`showRetainedPanel`/`collapsePanel`/`installBubbleTouch`/`hideBubbleAtEdge`，发现改动时额外做窗口回归；
- 增量编译和 lint 零阻断；当前稳定签名必须保持覆盖安装能力，不能清除 App 数据；
- 设备上连续操作至少 10 次展开/收回，包括固定态；每种词条手势在首/中/尾行均试，动作层自动/手动可返回；
- 分类拖动在列表上下边界与跨 2 个位置试，滚动不抢手势；外观明/暗看一次；
- 日志/截图只截取相关 UI 与窗口元信息；最终记录已通过、失败和未验证项。
