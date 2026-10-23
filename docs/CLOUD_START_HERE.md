# 云端续接入口（2026-10-23）
有效应用源码基线：ca01aaa47fcf58a35d14661b140efb9f38d69d8f / Windows 与 Android 0.7.8 build 25。交接提交只增加文档与设计参考，不是新的应用版本。

## 直接使用的远端项目
Codex MCP Dev 项目名：floatclip-cloud
MCP 路径：/home/codex/workspace/Android/FloatClip/analysis/cloud-workspace-build25
主机实际目录：/home/orangepi/workspace/Android/FloatClip/analysis/cloud-workspace-build25
工作分支：codex/wpf-design-system-077。
完整独立 Git 仓库，源码、索引和 Git 对象均独立；原 floatclip 根目录仍有其他未提交改动，不要重置、覆盖或在原根目录构建。
完整 Git bundle：analysis/baseline-source.bundle；两端安装包：analysis/deliveries/。
.toolchains 链接到小主机既有工具链；没有复制签名私钥、用户历史或配对库。新项目隔离环境不直接暴露这些原项目私有路径，构建请用下面已注册的任务。
这些远端项目/工具链依赖小主机和 MCP 服务在线；不需要 Windows 电脑持续开机来运行 Android 构建。

## 先读
AGENTS.md
docs/design/FLOATCLIP_WINDOWS_DESIGN_SYSTEM.md
docs/design/CHANGELOG.md
docs/CANDIDATE_078_BUILD25_2026-10-23.md
docs/CLOUD_CONTINUATION_2026-10-23.md
docs/NATIVE_UPDATES_2026-10-23.md
docs/ui/ 下七份交互 HTML 是模拟数据设计参考，不是真实网络客户端。

保留 WPF + XAML / .NET 10 / WPF UI；保留 Android vault、包名、原升级签名、配对与同步协议。
公共更新仍为 0.7.1；build 25 尚未提升为公共更新。
基线验证：Windows 187 项桌面、45 项更新通过；Android 62 项单测，lint 0 error / 17 warning。
Windows ZIP SHA256：87f5e7240d116ea44294e266688ae350db5f5ea9c37ba0f3a99dd68b39de8b6c
Android APK SHA256：d42927159abbc4cc72b995791c66c224cf0bbb271483746765e2033a8b34ed90
不要覆盖已发布的 build 25 制品，后续改动使用更高 build。

## 继续的需求（尚未实施）
1. 磨砂/透明是 Windows 整个应用的外观选择。上一轮只作用于剪贴板背景，文件/设置仍强制纯色；用户本轮明确纠正范围。文字保持不透明，密集内容保留足够可读的 Surface，统一服务、主题与系统回退。
2. Windows 分组支持编辑/删除，删除分组时将记录移到“未分类”，不删除内容；提供更多统一 Fluent 图标。Android 已有删除、缺少重命名，核对两端一致行为。
3. 手机图片默认与文字混排；图片单击应直接应用内看大图（现在单击复制，预览藏在长按菜单）。悬浮剪贴板也需要直接大图与缩放/拖动。
4. 图片卡片适配长截图，允许切换居中单栏与左右双栏；应用和悬浮面板使用同一布局偏好。
5. Android 文件页“预览/打开”当前 ACTION_VIEW 交给其他应用，须改为真实应用内预览。分享单独保留 ACTION_SEND。图片、PDF、ZIP、可支持文本展示实际内容；不支持类型明确说明。
6. 用户说手机已回局域网、ADB 小主机已开。最近 MCP 设备只有 127.0.0.1:25557 offline，本地 ADB 无设备；需重新连接，不能声称真机验收。只操作授权的 FloatClip。
7. GitHub SSH 部署密钥可推代码分支；连接器 pull=true、push/maintain/admin=false，不代表可用 Release/admin API。不要导出或复制私钥。
8. 用户要求完成后邮件发送两端下载链接及未验收范围。收件人留在会话/私有交接信息，不写入公共仓库。

## 环境边界
OrangePi 可构建 Android 和运行共享协议检查。WPF 的真实界面、DWM 材质、Windows 剪贴板/蓝牙及四档实机 DPI 仍需 Windows 主机；不能在 Linux 上把静态检查描述成 Windows 真机验收。
新 Codex/ChatGPT 云端会话需启用同一 Codex MCP Dev 连接器后读取 floatclip-cloud；仅把源码推到 GitHub 不会自动移动本地对话或创建已发布的 Codex Cloud 环境。

## 远端构建与发布入口
代码编辑/搜索/Git 状态：Codex MCP Dev run_project_shell(project_name="floatclip-cloud", command=...)。
Android 构建与测试：start_task_job(profile_name="floatclip_cloud_android")。任务通过原 floatclip 项目授权的缓存和工具链，在这个独立源码目录构建；不在原工作目录修改代码。
只读就绪检查：start_task_job(profile_name="floatclip_cloud_readiness")。
GitHub 推送仍走原 floatclip 的受限发布入口。独立库不复制部署密钥：先在独立库提交，创建指定分支 bundle；通过 floatclip 的 run_project_shell 将 bundle 快进导入相同分支，再调用 git_push(project_name="floatclip", branch="codex/wpf-design-system-077", force=false, delete_remote_branch=false)。导入前后核对原目录 HEAD/diff/status 不变；分支已推进时先比较，禁止强推。
