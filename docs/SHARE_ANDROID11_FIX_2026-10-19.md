# FloatClip Share：Android 11 启动崩溃修复

日期：2026-10-19（Asia/Shanghai）。修复对象为独立共享预览包 com.floatclip.probe；原 com.floatclip.app 不在本次修改范围。

## 手机证据与根因

远程正式 ADB 127.0.0.1:25555 已重新连接并核实 V2115A / PD2115 / Android 11。安装包 0.3.1-preview / build 5，UID 10230。系统 exit-info 记录多次 reason=4 APP CRASH(EXCEPTION)，在手机上重新启动后复现。

崩溃缓冲区的实际堆栈：

```text
java.lang.NullPointerException:
  Attempt to invoke virtual method
  android.view.WindowInsetsController ...DecorView.getWindowInsetsController()
  on a null object reference
at ...PhoneWindow.getInsetsController(PhoneWindow.java:4033)
at com.floatclip.probe.ShareActivity.palette(ShareActivity.kt:102)
at com.floatclip.probe.ShareActivity.build(ShareActivity.kt:110)
at com.floatclip.probe.ShareActivity.onCreate(ShareActivity.kt:71)
```

palette() 在 setContentView() 之前执行。Android 11 的 PhoneWindow.getInsetsController() 直接解引用尚未创建的 DecorView；Kotlin 的 ?. 只能处理 getter 返回 null，不能拦住 getter 内部抛出的空指针。

对照的 Android 11 AOSP 源码：
https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/core/java/com/android/internal/policy/PhoneWindow.java

## 修改

- 移除 palette() 中过早读取 Window.insetsController 的代码。
- 系统栏图标颜色改由已经接收 insets 的 View.windowInsetsController 设置；保留三键/手势区域的颜色和留白处理。
- 真机截图发现底栏文字沿单元格左侧对齐；0.4.0 增加文字居中，使标签对齐图标，并继续压缩顶栏与卡片、增加详情页层级。已经冻结的 0.3.2 不覆盖；中间 0.3.3 构建已取消，最终候选使用 0.4.0 / build 8。
- 新增 Android 11 / SDK 30 的 onCreate 测试，覆盖浅色、深色、跟随系统，只创建 Activity，不调用 onStart，不启动局域网监听、不访问剪贴板。
- Robolectric 的 Conscrypt 桌面 JNI 不支持当前 Linux ARM64 主机；这组 UI 测试使用 ConscryptMode.OFF 和 LEGACY 图形。这个设置只作用于 JVM 测试，没有改变手机上的 TLS、证书或授权机制。
- 启用 AndroidX 配置供测试依赖使用，生产依赖仍只有 share-core。新增 Android 单独快照脚本，使 Android 热修复不必重新构建或重命名 Windows 包。

## 验证与边界

0.3.2 / build 6 已覆盖安装到同一 UID，真实手机启动成功，设置页可打开；截图核实浅色界面，原崩溃未再出现。用户 14:35 的共享与设置截图也确认旧启动崩溃已解决。随后用户拿走手机，明确要求继续优化 UI，因此停止后续 ADB、截图、安装与触控；0.4.0 新界面只做离线构建和 JVM 验证。

构建与单元测试不等于完整传输验收。本轮处理启动、底栏对齐与 Android 页面层级；Windows 0.4.0 配套包只同步版本元数据，桌面界面沿用上一版。原来的完整配对超时和 Clash TUN 兼容性没有在本轮完成双端验收。没有读写真实剪贴板，没有选择或传输私人文件，没有改动 OrangePi 测力设备 LAN、系统代理、路由、DNS、防火墙或蓝牙。

ADB 诊断：普通全缓冲 logcat 动作遇到 UnicodeDecodeError；改为有限的 crash 缓冲区 / AndroidRuntime 错误日志，并仅在证据中保留本应用堆栈。部分 Vivo logcat 不支持 --uid 参数，未依赖该参数。截图与按钮操作带当前应用前台检查，前台改变时停止操作。原 com.floatclip.app 在设备上仍为 0.5.5 / build 10。

## 最终结果

0.3.2 热修复真实手机启动成功，设置页可打开；深色真机检查因前台已切换到用户其他应用而停止，没有宣称通过。0.4.0 继承修复并完成页面层级重构；最终任务、哈希与检查结果见同日 SHARE_UI_DEPTH_2026-10-19.md。新 UI 未再次安装到手机。
