# FloatClip build40：相机照片与全文/全屏查看

基于已发布build39候选，保留按需历史、透明度/曲线、设备配对与排序修复。

- 文件选择和分享入口接受 image/*，补齐部分相册只发送 ClipData 或 data URI 的情况。
- 源文件放宽为32 MiB；JPEG/系统可解码图片先采样解码，再转兼容旧端的PNG，必要时继续缩小副本。控制解码为约400万像素/4096长边；原相册文件不改动。JPEG使用EXIF方向；合规PNG保留原始字节，避免不必要的解码/重压。
- 存储/共享格式保持PNG，单张8 MiB、活跃图片总量32 MiB及既有受保护记录规则不变。转换、格式不支持、单图或总量超过限制分别显示原因，不把照片当成仅文字记录。
- 悬浮菜单新增查看全文/全屏查看图片；全文可选择且不截断。全屏图片可缩放/拖动，单击切换工具栏，不重置缩放；用独立内部查看任务，关闭后签名权限广播和一次性token恢复原面板。停止服务后的旧返回不得复活窗口。
- 复制图片先后台读取/验证，成功后才更新系统剪贴板；按钮有按压、忙碌、已复制/失败反馈，不用固定延时掩盖完成状态。失败不得误报成功。
- 启动更新检测补齐从桌面图标热启动；下载/安装或手动更新对话框期间不插入自动检查。

验证：新增普通PNG原样保留、8 MiB以上JPEG、EXIF旋转、相册URI、坏图不修改历史、全文不截断、全屏结构、复制状态及查看器返回/停止回归。最终检查与制品记录见下节。手机/电脑Remote当前离线，未以自动检查替代真机观感与系统照片解码格式验收。

## 最终构建与制品验证（2026-11-03）

源码检查提交 `7a0a3d7fa39ed8d44c5495f5154f4d4eb46dc6c9`；Android107项回归全通过，lint0错误/25警告。验证任务 `task-floatclip_photo40_android_verify-6848b5cf98f24a2cbd87`；Windows原生隔离236项、uiFailures=0、userClipboardUntouched=true，更新48项检查通过：[CI](https://github.com/SteveBrilien/FloatClip/actions/runs/41524807019)。物理四档DPI、OEM相册/安装和动画观感仍未实测。

APK原包名及证书SHA-256 `0368593f82ab885737b99d78c939111a1ec8ea3d88e8a67b2c3c9a4bd90c3f2c` 已验证。MCP永久链接两端均完整GET，大小/SHA-256一致；Windows ZIP完整性及9项许可记录通过。

| 制品 | 字节 | SHA-256 |
|---|---:|---|
| Android40 | 3968857 | `24d12dc362d0be627e2ca6b34b0a2ccf595d2b6ab1d33ddd3e16e5f5c082ba2f` |
| Windows40 | 78906077 | `71f9fe9e9d0c9453e366af66d319f5ccca0dea017208649d61f257134bd21116` |

[Android APK](https://mcp.wmy-cloud.cn/artifacts/pub-5d7663cdd44a4aae8a7d3b745001fee5/FloatClip-Android-0.7.9-build40-photo-candidate.apk?exp=0&sig=a69b2599aeaebc535490cb0d62daf9dc2cc396887509b5b4ed5a0f08366b33ba) · [Windows ZIP](https://mcp.wmy-cloud.cn/artifacts/pub-94129d0b0a774869bbe454bc40eedd58/FloatClip-Windows-0.7.9-build40-photo-candidate.zip?exp=0&sig=6063476feb244599593e037543d15b1aa37f6ee06ab483d5c4797eafc5bd7dfe)。GitHub候选由 `Verified native candidate` 工作流核验上述不可变制品后发布到 `v0.7.9-build40-candidate`，不替换旧版。

正式主源和备用源仍保持原钥签名build29；原私钥只留在离线Windows，不导出、不替换。`updates/share-preview.json`仅工作区待签名信息，不提交到公共主分支。两端Remote离线，因此未安装到用户设备。
