# FloatClip: 2026-11-12 十二项修复交接记录

基线：`5160538a`，分支：`codex/floatclip-12fix-20261112`。原 `analysis/takeover-build50-refine` 的未提交改动未被覆盖。本分支不替换安装版本、不执行数据迁移、不清空设备数据。

## 源码变更

1. 设置：应用主题改用卡片外标准区块标题；删除单控件重复标题，保留明确名称。
2. 悬浮主题：使用独立的 SYSTEM/LIGHT/DARK 偏好；面板、气泡和长按菜单共享其 palette，不再继承应用主题。
3. 面板位置：设置响应立即更新选中态、广播重新计算当前窗口坐标，不等二次启动。
4. 设置状态：按路由缓存 ScrollView 位置，保存到 Activity instance state；返回子页/重新创建后恢复。
5. Android 11：新增纯软件彩色半透明+重复微噪点拟态磨砂，不读取屏幕内容；API31+ 系统模糊仍受系统能力控制；磨砂强度可调。
6. 分类栏：首尾等量留白并尽量按整项标签边界对齐。清除与固定按钮采用无实体背景的点击热区 Ripple；固定空心/实心图钉。
7. Android 11 导航：监听 close system dialogs `homekey`/`recentapps`，强制解除面板固定并收回气泡；真机接收行为待测。
8. 图片查看：图片上层 TYPE_APPLICATION_OVERLAY 内复用既有分区解码/缩略图查看器，保留下层面板与滚动位置；添加右上角关闭。原图按当前现有规范加密保存，缩略图在独立缓存目录。新增原始 JPEG 无损存档尚未实现。
9. 图片同步：Android/Windows 在源 PNG SHA-256 完全相同时去重；重复图片仅 ACK、不重插、不更新时间、不改变原排序；显式复制历史图片只写系统剪贴板、不再同步；新外部图片导入才触发首次同步。当前 SHA-256 是编码后的 PNG 字节哈希，不是跨不同图片编码的像素语义哈希。
10. 图片卡片：移除自动生成的尺寸/长图/点击预览说明，仅显示明确填写的图片备注并参与搜索。
11. 长按菜单：背景、磨砂、透明度、边线、颜色和圆角跟随独立悬浮层主题。
12. 自动测试：新增/调整主题、图片备注、重复远端传输不改序和跨源哈希去重断言。

## 验证

- Windows `FloatClip.LibraryChecks`：105 项库级验证通过（测试用 AES codec；真实 Windows DPAPI 需实机）。
- Windows `FloatClip.Desktop`：Linux 交叉编译 WPF Release 成功，0 warnings, 0 errors。应用运行及 UI 行为需 Windows 实测。
- Android：完整 `assembleDebug + testDebugUnitTest + lintDebug` 已成功，21 个测试 XML、125 项用例、0 测试失败。Lint 0 errors、34 warnings。完成后的额外设置文案/图片同步线程竞态修补，另执行最终增量 smoke 验证。
- ADB：未发现连接设备，因此悬浮窗真实 Home/最近任务收回、图片滑动/缩放、窗口层级和 Android 11 渲染未真机验收。

## 发布门禁

不得因源码编译通过直接发布：需确认升级签名证书与 versionCode、手机/Windows 双端真实复制互不回传、跨端排序保持、崩溃/内存基线和备份可靠；尤其不得把 Debug APK 覆盖安装到含历史数据的用户设备。正式构建、GitHub 推送和 Releases 尚未执行。

- 复测中 `FileShelfTest` 曾因异步下载比预期更快完成而触发竞态断言（第 59 行）；删除该非确定性的 `isDone` 时序断言，保留下载内容、校验与重连结果断言。
