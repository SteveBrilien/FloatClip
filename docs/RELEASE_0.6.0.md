# FloatClip 0.6.0 发布与验证记录

日期：2026-10-20（北京时间）。

## 本次行为

三端统一“剪贴板、共享、设备、设置”，共用 web-v1 协议和蓝白 / 深色 / 跟随系统界面。剪贴板按来源设备的稳定客户端 ID 分区，支持搜索、分类、置顶、复制、合并备份和再次发送。分区保存本机已有或已接收的记录，不会自动拉取其他设备的完整剪贴板库。

Android 整合原版的加密库、悬浮球、无障碍粘贴和加密备份；Windows 提供 DPAPI 本机库和离线工具；PWA 为 IndexedDB 加密本机库。后台静默同步、永久配对、自动重连、蓝牙和 Wi-Fi Direct 尚未发布。

## Android：沿用当前共享版身份

按用户 04:01 的决定，不再等待原版独立应用签名。新版保留当前共享版 com.floatclip.probe，versionCode 12 / 0.6.0，固定使用 .toolchains/nearby-probe/debug.keystore。已核对该密钥及先前公开的 0.4.0 APK，证书 SHA-256 均为 0368593f82ab885737b99d78c939111a1ec8ea3d88e8a67b2c3c9a4bd90c3f2c。相同包名、相同签名、更高构建号符合覆盖升级条件；本轮没有进行真机安装验证。

源码命名空间仍为 com.floatclip.app，仅用于复用现有类和资源；安装身份由 applicationId 决定。原版 com.floatclip.app 可保持安装、数据不被新版自动读取或删除。签名私有权限改为安装包名作用域，避免与原版重复声明权限而拒绝安装。

后续发版继续使用这一固定签名。updates/android-identity.json 记录公开身份；发布脚本必须验证 APK 证书、包名、版本、启动组件和私有权限后才能冻结。CI 可生成临时调试密钥进行构建检查，不能将其产物作为当前升级包发布。私钥和密码不进入 Git、聊天、文档或公开构件。

迁移原版记录：先在原版设置中完成便携加密备份，并保留一份副本；在新版“设置 → 本机剪贴板与悬浮球”的原生管理页面，选择备份副本目录并用原 PIN 恢复。原版与新版不要共用自动备份目录。无来源的历史记录归入新版“本机”板块；新接收文字按来源设备分区。原生恢复会替换新版当前库，应在开始积累新版内容前操作；普通跨端 JSON 备份的导入为合并。

## 验证

- Web：此前已通过 TypeScript、24 项 Node 测试、8 项后端测试、生产构建和离线清单；本轮不改变共享前端行为。
- Android：当前身份 assembleDebug、7 项 SDK30 检查及 lintDebug 均通过（任务 task-floatclip_unified_android-e72eee1370b04f32964b）。包含并存权限检查和冻结旧格式的加密备份恢复检查；存储测试注入测试密码器，不冒充实际 Android Keystore 验证。
- Windows：此前 win-x64 自包含单文件发布和 21 项库 / 宿主来源检查通过，安装包字节不变。共享主界面需要 Microsoft Edge WebView2 Runtime，离线原生工具仍可使用；测试密码器不冒充 Windows DPAPI 真机检查。
- 此前部署后已通过真实 HTTPS / WSS 验证认证 API、配对、加密文字、40,960 字节文件和 SHA256、退出撤销。使用两个 5 分钟本地探测会话并始终清理，未重新输入或测试用户密码。原账户配置不变。

本轮未连接手机 ADB、Windows 或 iPad。实际 Android11 覆盖安装与悬浮球、Win10 / Win11 WebView2 / DPAPI、iPad Safari 安装 / 布局 / 文件保存仍待硬件验证。未扫描、改动接测力设备的有线网络。

## 下载与部署

PWA：https://clip.wmy-cloud.cn，版本 0.6.0。离线公共壳 2b14be2366a3；保留旧哈希资源和回滚副本。Windows 0.6.0 ZIP 为 59,266,873 字节，SHA256 691e4a8d6bac9ffb482222a3a6f733ab44faedaa10c7818020cc7b52db91dc1e。安装包公开地址及 Android 最终哈希以 updates/share-preview.json 为准；网站设置内使用同一版本元数据。

Android 0.6.0 APK 已发布：3,022,476 字节，SHA256 f727efd94a8895abcba1c55bf54b344c923ff57bf941f7e93ceb53fe3624e805。公开链接完整下载校验通过；经发布脚本验证与旧 0.4.0 签名一致。Windows 字节仍与先前公开包一致。三端分发状态均为 available，网页版本 API 使用更新后的下载元数据，无需服务重启；认证 API、加密消息及 40KiB 文件传输探测再次通过。
