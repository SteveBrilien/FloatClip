# 0.7.8 自动检查证据

这些图片和报告来自隔离夹具，不含用户剪贴板、配对库、诊断日志或附件。`report.json` 是原生桌面检查，`package-report.json` 是最终单文件候选的隔离启动；`scope.json` 区分真实宿主 150% 与四档离屏渲染。`android-verification.json` 记录 61 项单测、lint、APK 大小/哈希/公开签名身份。

`update-source-probe.json` 只含公开清单/包 HEAD 的状态与长度，不含下载签名 URL 或认证数据。Windows 更新检查另用实际编译代码验证主备签名清单。用户尚未完成外观走查，截图/合成悬停不等同实际指针、DPI 或手机验收。
