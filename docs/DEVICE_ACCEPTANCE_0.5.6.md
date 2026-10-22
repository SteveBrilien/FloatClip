# 0.5.6 实机验收记录与交付门槛

日期：2026-09-29（Asia/Shanghai）
目标机：vivo V2115A / Android 11；设备 ADB `10.33.221.78:5555`。

## 已做的只读/可恢复检查

- 已安装 FloatClip：`versionCode=10` / `versionName=0.5.5`。本轮编辑版本：`versionCode=11` / `0.5.6`。
- `dumpsys appops` 显示 FloatClip 的 `SYSTEM_ALERT_WINDOW (allow)` 和 `BIND_ACCESSIBILITY_SERVICE (allow)`；说明 Android 系统权限页显示“未请求运行时权限”不能作为“完全没权限”的证据。
- 原生 `com.vivo.floatingball` 的 EdgeView 与 ExpandedView 可在点击后切换可见状态：贴边 120×210px，系统 UID 1000、NAVIGATION_BAR_PANEL；外点收回。资源与 DEX 的结构性静态解包、截图元信息及适用边界见 `NATIVE_FLOATINGBALL_UX_PLAN_2026-09-29.md`。
- 当前 0.5.5 的 FloatClip 覆盖层经状态页开启，在设备上可展开并外点收回，随后已从状态页关闭，窗口确认为无 FloatClip overlay；前台还原为测试前的 ChatGPT。没有改变历史词条。
- 0.5.5 展开时面板后方 App 文本透出，确认默认 88% 透明度会影响可读性。0.5.6 默认改为 96%；已单独保存的用户选择仍优先。

## 0.5.6 改动范围

- 首页状态区压缩为状态 + 单个开关；帮助入口集中到设置“关于”。
- 词条摘要最多两行；未分类不再重复显示，置顶用图钉标识；保留左滑置顶/删除与长按纵列。
- 左滑按钮改为随 FloatClip 显式明暗模式变化的柔和配色；面板删除增加确认页，避免误触直接清空。
- 分类拖动进入新落位时短触觉反馈，落下时沿实际屏幕坐标平滑归位；取消拖动时不重建整列 View。
- 展开/收起及保留窗口切换代码没有改动；剪贴板加密文件与备份格式没有改动。

## 测试门禁与当前状态

| 验证 | 状态 |
| --- | --- |
| 项目方案先于源码更改 | 通过；时间顺序见 MCP operation 日志 |
| 源码差异格式 / `git diff --check` | 通过 |
| 0.5.6 完整 debug 构建 | 待最终构建 ID / APK 哈希填入 |
| 0.5.6 lint | 待最终结果填入 |
| 安装版 0.5.5 基线展开/外点关闭及权限 | 通过 |
| 0.5.6 在原机覆盖安装、滑动收回、删除确认、连续开合与分类拖动 | 待稳定签名 APK，不能用 0.5.5 的手机表现冒充 0.5.6 实测 |

## 阻断与安全执行路径

此前发布 APK 签名证书 SHA-256：`89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3`。MCP TaskProfile 的 debug 默认签名证书：`7f6e26cebebb16b491e4075946ef21f75c496e2f22fefe703abb79ee2cb952d2`。证书不同，Android 不允许覆盖安装，且现有稳定私钥保存在 Git 目录之外，MCP 项目沙箱当前看不到它。

下一步是在受控签名环境将最终同版本 debug 构建 zipalign 后以已有 89f… 稳定私钥重签，核对签名与 versionCode 11，再通过受审计的 ADB `install -r` 覆盖安装并完成上表真实交互验收。不得卸载 0.5.5 / 清除数据以绕过签名限制：加密剪贴板历史依赖现有 App 数据与 Android Keystore。签名私钥和密码不进入仓库、文档、命令回显或聊天消息。
