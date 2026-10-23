# 桌面交互与原生截图

`floatclip-desktop-review.html` 是本轮完整五页交互参考，默认展示已确认的资源管理器式文件页。使用模拟数据，不连接设备、不修改剪贴板、不下载安装程序。原生实现继续使用 WPF 与 WPF UI 控件；两者应保持相同的页面结构、按钮层级和操作顺序。

PNG 为 Windows 原生程序在隔离历史和合成文件样本下的实际渲染，不含个人文件或用户上传截图。`files-page-approved-light.png` 为标准宽度，`files-page-tokyo-night.png` 为窄窗口东京夜色主题；极窄窗口保留表格水平滚动。

预览自动检查：安装 Playwright 后在仓库根目录运行 `node scripts/check-desktop-preview.cjs`。本机 Edge 可设置 `PLAYWRIGHT_CHANNEL=msedge`。单独打开片段时，图标依赖会话宿主提供的 Lucide；源文件不读取网络。
