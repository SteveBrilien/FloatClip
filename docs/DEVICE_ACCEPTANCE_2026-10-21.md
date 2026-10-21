# FloatClip 真机续验（2026-10-21）

## 通道

- 用户重新开启 Windows Remote，并指定尝试校内 5555 救援。
- OrangePi 经已登记 connect_campus_lan_rescue_adb 成功连接 `10.33.221.78:5555`。
- structured adb_devices + getprop：device / PD2115 / V2115A，transport_id=730。
- `127.0.0.1:25557` 仍 offline，不能据此报告全部 ADB 不可用。
- Windows Administrator 在线（设备 ef786c39-4f4a-421d-ae86-8082885e4aba）；Windows 直接连接上述校园 IP 超时，小主机的救援通道正常。
- 本次没有更改网络、热点、SSH/FRP supervisor 或端口映射，也没有修复正式转发通道。

## 覆盖安装

- 安装前实际版本：0.7.1 / build14；原 APK 已单独保存于 analysis/device-acceptance-20261021/installed-before.apk。
- 原包和本轮候选的证书 SHA-256 均为 `0368593f82ab885737b99d78c939111a1ec8ea3d88e8a67b2c3c9a4bd90c3f2c`。
- 安装候选为冻结的 build15，APK SHA-256：`5d7e0c5264661d1ef7038030a11e185ec1237aaaa62a3e6b2fa1fa3ebfffd919`。
- adb install -r 返回 Success，随后 dumpsys package 验证 versionCode=15，firstInstallTime 保留 2026-10-18 19:34:15，lastUpdateTime 为 2026-10-21 18:46:11。
- 没有卸载或清除数据；手机历史、来源和设备授权仍可见。
- package replaced 后 ClipboardSyncService 实际处于前台运行；之后浮窗服务也实际运行。
- 用户原有共享/接收/写入/自动连接和浮窗意愿均保留。

## Windows 测试环境

- 使用既有 Windows 内部候选 artifact-d8cc21a1880c49beb0824e52fc5e5c06。
- 文件 SHA-256：`d5072b8f6ed743cbbce4824da246d321c7446635687a44239f0cb72018b274f0`，下载后 Windows Get-FileHash 一致。
- 路径：Windows 当前用户 LocalAppData/FloatClip/DeviceTest-20261021/FloatClip.exe。
- 临时重复启动的第二个测试实例已关闭，保留 PID8988 一个实例供查看。
- 用于临时传输的签名链接已撤销；没有正式发版或更新 feed。
- 本次不删除现有配对，也没有重新核对/生成新信任。

## Windows → Android：首次真实闭环 PASS

- Windows 真实系统剪贴板写入测试 `FC-WIN-TO-PHONE-20261021-1856-D`。
- Windows 状态实际出现“1 台设备已连接 · 蓝牙加密短连接”。
- Windows 同步状态中该受信手机的 pending 从存在变为 False。
- 手机原始截图 screen-current.png、screen-latest.png 实际出现同一测试正文和电脑来源图标；来源栏包含 ADMINISTRATOR。
- 因为同时有实际手机显示和发送端持久 outbox 释放，不再仅以“设备已授权”判定成功。
- 未开启/切换热点；本次按蓝牙 transport 验证，不把校园 ADB 可达等同于 LAN 应用同步成功。
- Windows 没有收到手机内容事件（Seen=0），不能把此项当作双向闭环通过，也不把当前结果扩展为长期无人值守稳定性通过。

## 视觉观察与限制

- 真机已显示东京夜色、透明面板、来源栏、保护/置顶标识，以及右滑保护按钮。
- 用户同时在手机上调整外观并操作浮窗；这些观察只证明实际渲染，不能代替用户对整套 UI 的最终确认。
- 浮窗打开时 uiautomator 多次返回 null root；此时旧 XML 会是陈旧文件。本次不采用陈旧 XML 作导航/送达依据，改用新截图和 WindowManager 状态。
- 新截图显示测试条目到达；第一张截图同时含个人历史，只留在私有 analysis，不用于公开发布。
- 未执行批量清空、删除用户历史、强行停止、清后台、锁屏或网络切换。

## 仍待验证 / 新发现

1. 手机 → Windows：需要真正的新复制事件。当前不能用主 App/浮窗“复制旧条目”的 OWN_WRITE 标记路径冒充第三方 App 复制。
2. 短连接结束后再次复制、失败后的重试、多设备调度、撤销授权与暂停组合仍待验收。
3. 清理最近任务后的恢复仍待实际测试。
4. Windows 在共享持久状态 Enabled=True 时曾短暂显示“共享已关闭”的启动文案；这是状态显示待修项，不能据单一文案判运行开关。
5. Windows 当前没有单实例限制，重复启动可能产生多个共享 owner；本次已清理重复测试实例，后续应补单实例处理。
6. 原生 Android UI/浮窗的 OWN_WRITE 和明确用户复制之间需要单独复核语义；不能为测试而去掉自动接收写入的防回声保护。

本次校内 QA 的固定动作登记在临时 floatclip_device_qa capability 中，收尾后移除；未扩大通用 adb_pull 前缀或任意 shell 权限。
