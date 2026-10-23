# Windows 0.7.7 WPF 设计系统第一轮验收

从 `e8732dc` 的 0.7.7 / build 22 开始，保留 WPF + XAML、WPF UI 4.3.0、Emoji.Wpf 0.3.4；新增 CommunityToolkit.Mvvm 8.4.2。WebView2 实验单独保存在 `codex/desktop-080-web-ui`，不作为当前产品路线。唯一规范是 [FloatClip Windows Design System](design/FLOATCLIP_WINDOWS_DESIGN_SYSTEM.md)，仓库根 AGENTS.md 要求后续修改先读规范。

## 本轮变更

- `Design/Tokens.xaml` 统一字体、间距、分级圆角、热区和动画；`Light.xaml` / `TokyoNight.xaml` 提供语义配色。旧 `dark` / `tokyo-night` 设置兼容，界面合并为一个“深色（Tokyo Night）”选项。
- 复用现有 WPF UI 模板，共享 Button / 工具栏 / 菜单 / 文件行 / 列头状态。文件类型颜色迁入语义资源，品牌使用原有应用图标。设置行和剪贴板卡片提供悬浮反馈。
- 搜索视图迁为 XAML；查询状态和清除命令进入 ClipboardPanelViewModel，历史操作进入 ClipboardLibraryService。外部点击释放搜索焦点。
- 全部清除不保留撤销快照，固定和置顶内容保留；单条删除保留原撤销能力。手机主界面和悬浮层也取消“全部清除”的撤销。
- 动画时长使用共享 Press / State / Panel token；设置增加“减少动画”，同时尊重系统动画关闭及高对比度。新偏好保存在独立 design-preferences.json，不改旧数据格式。
- 材质服务检测 Windows / DWM / 透明设置；特殊悬浮窗维持纯色。未将“具备 Mica 条件”当作已启用或已通过验收。

## 启动通用提示的原因和修复

本轮两次界面验收暴露了资源解析问题。确认可复现的菜单问题是引用了 WPF UI 4.3.0 中不存在的 `DefaultMenuItemStyle` / `DefaultContextMenuStyle`，已改为运行时实际存在的 `UiMenuItem` / `UiContextMenu`。同时将历史颜色别名发布为同一个应用级语义 Brush 的引用，避免脱离视觉树初始化时资源不可见。

原 Guard 吞掉异常并弹出“本机操作未完成，请保留数据并重试”，导致验收可能在弹窗之后仍报成功。现改为：正式运行写本机 Host/ui-errors.log 并显示非模态状态；隔离验收记录后直接抛出，整次检查失败。新增共享样式提前解析、真实右键弹层打开以及最终 UiFailureCount=0 检查。修复后的本轮检查没有隐藏异常。日志不放入 Git 或同步资料。

## 验收矩阵

| 范围 | 已验证 | 尚未验证 / 限制 |
| --- | --- | --- |
| 设计一致性 | 语义配色、字体和分级 tokens；按钮/菜单/文件行资源解析；正文/次要/禁用及主按钮状态文字对比度均 ≥4.5:1 | 既有命令式视图仍有局部尺寸、字符图标和模板，未声称全页全部迁移 |
| 行为一致性 | 搜索外部点击失焦、卡片悬浮事件、真实菜单弹层、页面缓存/宽度、非模态辅助窗、滑动入口 | 悬浮事件为合成输入，所有控件的真实指针按压/快速连续滑动仍需人工走查 |
| Light / Dark | 两种主题截图、Tokyo Night 存储恢复、已打开辅助窗口跟随、菜单解析 | 高对比度整体视觉及全部弹层人工逐一核对未完成 |
| DPI / 尺寸 | 当前真实窗口缩放 150%；420 / 760 / 宽窗口分别生成 96/120/144/192 DPI 离屏图 | 非四档实际系统缩放/跨屏点击验收；宽窗口渲染不等同最大化/还原人工验收 |
| 文本 / 图片 | 中文、英文、长文本截断、彩色 Emoji 原序列复制、PNG/DIB/DIBV5 与透明度、非模态预览 | 跨设备图片同步尚未实现 |
| 数据功能 | 搜索、固定/置顶保留、清除无撤销、重启偏好、图像库与原生宿主回归 | 不以本轮隔离检查替代用户实际历史与设备验收 |
| 同步 / 文件 | 实际 TCP 会话 FCP3/FCP4、双向 ACK、重连、显式文件下载与校验、传输中剪贴板帧 | 本轮未重跑真实蓝牙/手机/LAN 端到端；此前实机结果见旧 0.7.7 记录 |
| 材质 | 能力检测及纯色回退 | Mica/Acrylic 未启用，置顶/穿透/多屏性能验收待办 |
| 构建 | Release / win-x64 自包含单文件成功；146 项桌面检查、33 项更新检查通过；ZIP 与打包后隔离启动通过 | 现存 NU1510 ProtectedData 冗余引用警告；2016 LTSB 未验收 |

`analysis/desktop-checks/report.json`：PASS 146，uiFailures=0，隔离数据，未触碰用户剪贴板。`analysis/desktop-checks/design/scope.json` 记录真实/离屏测试边界。Android 无撤销候选先前在小主机独立目录通过 56 项单测、assembleDebug 与 lint（0 error / 17 warning），本轮未安装个人手机。

## 试用包

`analysis/FloatClip-Windows-0.7.7-wpf-design.zip`，78,900,681 字节。SHA-256：`81dfabaeb8fcd7a4fac3bfbc17d761cd84959b3e2ee474f6497191e23281a98c`。

主程序：`analysis/windows-0.7.7-design/FloatClip.exe`。SHA-256：`758f537f7fe00181d7ee9ac64c96bd6fe4d2a449a07e3b61f17aa95df72c258d`。附第三方许可；使用 Windows 10 2004 / build 19041 或更新的 x64 系统。先退出托盘旧进程再运行候选。未覆盖 D:\FloatClip，未更新公共发布清单或标签。

## 下一步

继续按共享组件先行逐页迁出剩余 C# 视图和局部视觉值；真实指针/键盘、四档系统 DPI / 跨屏、最大化、辅助弹层逐项人工验收后再考虑材质。真实手机蓝牙链路需保持现有数据与授权回归。自托管签名更新源、跨设备图片同步、Wi-Fi Direct、Windows 系统跨应用分享和完整 Office 内嵌预览仍是独立待办。
