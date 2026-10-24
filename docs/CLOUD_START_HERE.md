# FloatClip 当前续接入口（2026-10-25）

当前候选为 Windows build34 / Android build31。先读 [本轮续接](TAKEOVER_2026-10-25.md)：包括上一会话未提交工作与 PowerToys 整窗透明兼容。Windows Remote 和正式 ADB25555 已恢复；Windows build34 / Android build31均已安装，Windows原生225、UpdateChecks45、Android单测71通过。实际PowerToys按键序列未稳定验收；下文build29设备状态是历史记录。候选未更新正式签名feed。


实际源码：Codex MCP Dev 项目 floatclip-cloud，路径 /home/codex/workspace/Android/FloatClip/analysis/cloud-workspace-build25；独立 Git 仓库，分支 codex/wpf-design-system-077。原 floatclip 根目录仍有其他历史未提交改动，不要重置或覆盖。

当前版本：Windows 0.7.9 build29；Android 0.7.9 build29。Windows 原生198项、UpdateChecks45项、Android69项单测通过；Android lint 0 error / 18 warning。Android build29 已在 vivo V2115A Android11 覆盖安装成功，原包名/签名及首次安装时间保持；Windows包已放 D:\FloatClip\0.7.9-build29\FloatClip。

本轮需求、修复和验收边界见 [当前候选记录](CANDIDATE_079_BUILD29_2026-10-24.md)。用户最近反馈的图片抖动、外观页小节/间距、固定右下角导入按钮已进入 build29；更新主源已切换到原钥签名的0.7.9，备用源与GitHub Release阶段须读取实际仓库/CI状态。

## 工程与构建

先读 AGENTS.md、docs/design/FLOATCLIP_WINDOWS_DESIGN_SYSTEM.md、docs/design/CHANGELOG.md。保留 WPF + XAML / .NET10 / WPF UI 与 Android 原生视图，保留 vault、包名、原升级签名、配对与同步协议。

代码编辑用 run_project_shell(project_name="floatclip-cloud",...)。Android构建/单测/lint用 start_task_job(profile_name="floatclip_cloud_android")；该任务借用原项目批准的工具链，在本独立目录构建。任务默认Artifact的旧名称不能作为实际版本证据，必须核对 output-metadata.json、APK证书与SHA-256。

Windows实际SDK在电脑 D:\OneDrive\文档\ChatGPT\FloatClip\.toolchains\dotnet-current；198项检查在独立目录 D:\FloatClip\review-079-source 运行。原Windows仓库旧ca01aaa不是当前云端工作区，不能把它直接覆盖云端新源码。

## 更新与交付

updates/share-preview.json是双端冻结下载信息；updates/native-updates.json是原钥RS256签名。Windows私钥只留在 %LOCALAPPDATA%/FloatClip/ReleaseKeys/，不得读取输出或复制到云端。编译公钥身份未改变。

真实主源服务在原 floatclip 项目的 web/server/app.py，静态目录为 web/dist；源与dist的 release.json只原子合并nativeUpdates、两个native版本及downloads，保留Web版本、认证与其他字段。公网地址 https://clip.wmy-cloud.cn/release.json。备用为GitHub main的updates/native-updates.json。

GitHub发布工作流在main含新签名feed后验证RS256、大小和SHA-256，再创建对应GitHub Release；它不上传CI临时签名APK。不要强推、删除旧tag/release或覆盖已发布制品。新源码先提交并创建bundle，经原floatclip项目的授权推送入口发布。

## 设备状态与未验收项

手机Termux Remote设备已上线。启动其既有 adb-tunnel-supervisor 后，正式ADB 127.0.0.1:25555恢复device；25557仍offline，不能混用。ADB具体动作通过已注册HostCapability，安装build29动作 install_floatclip_build29_remote 对应不可变APK。

四档物理DPI/跨屏、其他Windows设备、手机与电脑真实蓝牙/LAN图片/文件往返、长期后台稳定性与耗电仍待验收。不以loopback或编译替代真机验收，不声称已恢复全部历史待办。

[旧交接入口](archive/CLOUD_START_HERE_PRE_079.md)保留当时状态，仅作历史。
