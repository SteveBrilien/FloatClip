# FloatClip 0.6.0 发布与验证记录

日期：2026-10-20（北京时间）。

## 本次行为

三端统一“剪贴板、共享、设备、设置”，共享 web-v1 协议和蓝白 / 深色 / 跟随系统界面。剪贴板按来源设备的稳定客户端 ID 分区，支持搜索、分类、置顶、复制、合并备份和再次发送。旧原版手机的加密库、悬浮球、无障碍粘贴和加密备份保留，通过原版应用宿主整合；Windows 提供 DPAPI 本机库和离线工具；PWA 为 IndexedDB 加密本机库。

设备分区保存本机已有或已接收的记录，不会自动拉取其他设备的完整剪贴板库。后台静默同步、永久配对、自动重连、蓝牙和 Wi-Fi Direct 尚未发布。

## 自动验证

- Web：TypeScript 类型检查、24 项 Node 测试、8 项后端测试、生产构建及离线清单通过。包含加密 relay 实际连接、来源设备元数据、同名设备隔离、备份冲突合并和置顶保护。
- Android：assembleDebug、lintDebug、5 项 SDK 30 Robolectric 检查通过；包含原记录 ID / 分类 / 置顶迁移、并发写入冲突、相同内容不同来源、损坏库保护、宿主来源限制。ARM 测试使用 LEGACY graphics / Conscrypt OFF。存储测试注入测试密码器，不冒充实际 Android Keystore 验证。最终任务 task-floatclip_unified_android-654cf4b4f5e441ebae35。
- Windows：win-x64 自包含单文件发布通过，21 项库和宿主来源检查通过；测试密码器不冒充 Windows DPAPI 实机检查。共享主界面需要 Microsoft Edge WebView2 Runtime，离线原生工具仍可使用。

## Android 覆盖升级阻塞

目标包名 com.floatclip.app，versionCode 12 / 0.6.0。原版稳定证书 SHA-256 为 89f27902ef697dd6a1802cad39cf3067bf83f0a078bda92f30895c6b62d45dd3；当前构建证书不同，不能覆盖原版。只读检查已知主机默认 debug 证书也不匹配，未找到已配置的原版签名动作。

未发布该默认 debug APK，未改包名绕过升级，也未卸载 / 清除手机数据。发布脚本要求 APK 经 apksigner 验证为原稳定证书才冻结发布。安装包下载信息仅收录真正可用的构件；旧 Share 的 0.4.0 下载保留原字节和独立包名。后续需要原受控签名环境或其准确位置，以原地签名完成 Android 发行。私钥与密码不得写入仓库、聊天或发布记录。

## 硬件验收边界

本轮未连接手机 ADB、Windows 或 iPad。实际 Win10 / Win11 的 WebView2 / DPAPI、Android11 覆盖安装和悬浮球、iPad Safari 安装 / 浅深色布局 / 文件保存待用户测试。未扫描、改动 OrangePi 测力设备有线网络。

## 已部署与下载

- PWA：https://clip.wmy-cloud.cn，后端 /_health 返回 0.6.0。新资源通过 HTTPS 校验，匿名认证及版本 API 返回 401。离线公共壳版本 2b14be2366a3；保留旧哈希资源和服务端 / 静态资源回滚副本，不改认证私有配置。
- Windows 0.6.0 ZIP：59,266,873 字节；SHA-256 691e4a8d6bac9ffb482222a3a6f733ab44faedaa10c7818020cc7b52db91dc1e。下载地址见 updates/share-preview.json 和登录后的“设置 → 更新”。已从公开地址完整下载核对大小和哈希。
- 部署后以两个 5 分钟本地探测会话验证真实 HTTPS / WSS：认证 API、配对、加密文字、40,960 字节文件及 SHA-256、退出撤销均通过。探测会话始终清理；本次没有重新输入或测试用户密码，也不将这项检查记作密码登录验收。密码登录与 30 天登录保持有后端自动测试；原账户配置未改动。
- 本轮沿用并保留最初工作区的 0.5.6 UI 改动。MainActivity、ClipboardOverlayService、EntrySwipeActions、OverlayPreferences 四份既有源码字节未改动；README 和 Gradle 仅追加整合资料与所需版本 / 构建配置。两份既有未跟踪验收资料保持未跟踪。
