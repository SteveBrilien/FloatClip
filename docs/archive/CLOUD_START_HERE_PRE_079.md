# 云端续接入口（2026-10-23）
原有效应用源码基线：ca01aaa47fcf58a35d14661b140efb9f38d69d8f / Windows 与 Android 0.7.8 build 25；交接文档提交为 65c9f43。当前工作区已完成 0.7.8 build 26 候选，详见 `docs/CANDIDATE_078_BUILD26_2026-10-23.md`。

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
docs/CANDIDATE_078_BUILD26_2026-10-23.md
docs/CANDIDATE_078_BUILD25_2026-10-23.md
docs/CLOUD_CONTINUATION_2026-10-23.md
docs/NATIVE_UPDATES_2026-10-23.md
docs/ui/ 下七份交互 HTML 是模拟数据设计参考，不是真实网络客户端。

保留 WPF + XAML / .NET 10 / WPF UI；保留 Android vault、包名、原升级签名、配对与同步协议。
公共更新仍为 0.7.1；build 26 也只是候选。
当前自动验证：Windows Release/Checks 交叉编译 0 warning / 0 error，LibraryChecks 82、Share core 120、UpdateChecks 44；Android 66 项单测全绿，lint 0 error / 17 warning（与 build 25 基线一致）。
Windows build26 ZIP SHA256：ae7848532fc16828fa21b2f3361a6f7aa0cc274123293ab6a48e70c2b74e0a99
Android build26 APK SHA256：68930fd41c25b5518a14b1eb8f2fc880a3369429ad3b40939345f01d15934f6d
不要覆盖 build 25 / build 26 制品，后续应用改动使用更高 build。

## 当前状态 / 下一步
1. build 25 留下的“全应用背景、分类编辑、图片混排与大图、长图/双栏、Android 文件应用内预览、HELLO 后文件目录发布”已经进入 build 26 候选；不要重复实现，先读 build 26 候选记录和实际 diff。
2. ADB 仍只有 127.0.0.1:25557 offline；旧无线地址从小主机无路由。恢复后才做 vivo Android 11 覆盖安装与 UI/手势/真实文件往返验收，不能声称已通过。
3. Windows WPF/DWM、真实 DPI/跨屏、系统剪贴板/蓝牙仍需 Windows 主机；Orange Pi 只能做交叉编译与协议检查。
4. 公共更新保持 0.7.1；未得到发布决定前不要修改 release feed。
5. GitHub 代码分支可提交/推送，但禁止强推；若 SSH host-key/部署入口有问题，先修发布通道，不复制私钥。
6. 用户此前要求完成候选后发送两端下载链接及未验收范围；收件人只保留在私有会话，不写公共仓库。

## 环境边界
OrangePi 可构建 Android 和运行共享协议检查。WPF 的真实界面、DWM 材质、Windows 剪贴板/蓝牙及四档实机 DPI 仍需 Windows 主机；不能在 Linux 上把静态检查描述成 Windows 真机验收。
新 Codex/ChatGPT 云端会话需启用同一 Codex MCP Dev 连接器后读取 floatclip-cloud；仅把源码推到 GitHub 不会自动移动本地对话或创建已发布的 Codex Cloud 环境。

## 远端构建与发布入口
代码编辑/搜索/Git 状态：Codex MCP Dev run_project_shell(project_name="floatclip-cloud", command=...)。
Android 构建与测试：start_task_job(profile_name="floatclip_cloud_android")。任务通过原 floatclip 项目授权的缓存和工具链，在这个独立源码目录构建；不在原工作目录修改代码。
只读就绪检查：start_task_job(profile_name="floatclip_cloud_readiness")。
GitHub 推送仍走原 floatclip 的受限发布入口。独立库不复制部署密钥：先在独立库提交，创建指定分支 bundle；通过 floatclip 的 run_project_shell 将 bundle 快进导入相同分支，再调用 git_push(project_name="floatclip", branch="codex/wpf-design-system-077", force=false, delete_remote_branch=false)。导入前后核对原目录 HEAD/diff/status 不变；分支已推进时先比较，禁止强推。
