# FloatClip 0.8.0 界面迁移试验

Windows 0.8.0 / build 23 从已确认的网页交互稿迁出 HTML/CSS，使用 WebView2 渲染本地界面。`FloatClip.Next` 是本轮可运行入口；`FloatClip.Desktop` 作为 C# 宿主依赖复用现有剪贴板监听、蓝牙/LAN 配对、授权、加密会话、回执、文件清单和传输。0.7.7 独立试用包保留，公共更新清单未改变。

## 为什么采用这个方向

视觉差距来自此前把网页布局人工改写为 WPF 控件；不是 WPF 不能做好。此次直接复用确认稿的布局和样式，减少两套实现持续偏离。HTML/CSS 控制布局与交互；JavaScript 控制页面；WebView2 是嵌入 Windows 客户端的浏览器渲染器；C# 继续负责系统能力。React 是可选的组件组织方式，现阶段不必先重写成 React。Flutter 或原生 C++/WinUI 界面需要重新实现布局与平台接入，不适合以复用当前稿为首要目标的这一步。

官方依据：[WebView2 的 WPF 接入](https://learn.microsoft.com/en-us/microsoft-edge/webview2/get-started/wpf)、[本机能力调用与来源校验](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/security)。

## 本轮交互与实际能力

- 卡片、设置行、设备行、按钮和文件行均有浅色/深色悬浮与按压反馈。搜索框点击外部后释放焦点。
- Windows 和 Android 的“全部清除”保留固定/置顶记录，不再保存该批撤销快照或展示撤销。单条删除的旧版撤销逻辑不受影响。
- 0.8.0 界面读写真实历史；搜索、复制文字/图片、详情、左右滑动固定/置顶、删除、保护清除、主题和字号已接入宿主。
- 文件页使用六列详细列表。添加、按需下载、另存为、属性、预览、文件记录保留/移除和 LAN 转发均调用现有宿主；传输活动可接收/拒绝文件。选中不会启动传输，原始文件名会保留。
- 已授权设备与同步开关使用真实状态；蓝牙添加、LAN 发现/配对继续调用原实现，不更改协议或重置配对。
- 图标与 Noto 彩色 emoji 字体离线打包；按字素序列显示组合表情，复制仍使用完整原始 Unicode。

## 验证与边界

原生宿主 DesktopChecks 104 项、更新 33 项通过；WebView2 隔离验收 15 项通过，包含真实库、桥接、保护清除、失焦、主题落盘、六列文件清单与导航宽度。安装界面资产的浏览器回归通过，验证悬浮反馈、失焦、无撤销、转发至宿主的请求和响应式布局。隔离检查不读取或修改个人剪贴板。测试证据在本机 `analysis/web-080`。

这是迁移试用版本，还不能当作完整 0.8.0 正式发布：备用快捷键/备份管理界面、系统跨应用分享、完整 Office 内嵌预览和更新安装入口仍需完成迁移与打包验收。更新检查与错误状态已接入真实 GitHub 源；此预览不开放自动替换程序。图片本机保存/预览/复制已接入，跨设备自动图片同步作为下一步，未放置可误开的假开关。

## 构建与云端续接

```text
python scripts/prepare-desktop-web.py
python scripts/fetch-desktop-assets.py
dotnet build windows/FloatClip.Next -c Release
node scripts/check-next-ui.cjs
```

使用 .NET 10 和 WebView2 Runtime。字体通过 `UI/asset-lock.json` 校验 SHA-256；25 MB 字体不进入 Git，开发环境首次下载，最终试用包包含字体、图标和许可，运行时无需联网取界面。浏览器检查需 Playwright，可用 `PLAYWRIGHT_CHANNEL=msedge`。原生 `--qa-web <隔离根目录> <报告路径>` 只在 Windows 实机执行。

分支：`codex/desktop-080-web-ui`。小主机使用独立 `analysis/cloud-handoff-080` 副本，保留其当前工作区的其他修改。后续优先读取本文、`docs/ui/README.md` 和 `docs/STATUS.md`。不要把本机 Host 存储、剪贴板正文、配对密钥或签名文件提交到 Git。
