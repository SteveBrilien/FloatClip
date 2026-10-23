# FloatClip

本机优先的跨设备剪贴板与共享文件工具。同一仓库维护 **Windows WPF 桌面端、Android 移动端、共享协议和可选 Web/iPad 入口**。

当前原生开发线为 **0.7.8 / build 25 候选**；公共签名更新仍为 **0.7.1**。GitHub Releases 中的 0.5.x 属于旧 Android 产品线，不能当成当前跨端版本。

[当前文档](docs/README.md) · [本轮候选与验收边界](docs/CANDIDATE_078_BUILD25_2026-10-23.md) · [Windows 设计规范](docs/design/FLOATCLIP_WINDOWS_DESIGN_SYSTEM.md) · [原生更新发布](docs/NATIVE_UPDATES_2026-10-23.md)

## 已有能力

| 功能 | Windows / Android |
| --- | --- |
| 本机剪贴板 | 加密历史、搜索、分类、固定/置顶、清除与复制 |
| 图片 | 本机存储、预览和复制；Android 可主动选择图片或从截图分享菜单加入 |
| 跨设备文字 | 已授权蓝牙或局域网加密同步 |
| 共享文件 | 已授权局域网清单、按需下载；蓝牙连接仅同步文字 |
| 更新 | 自托管 HTTPS 主源、GitHub 备用、签名元数据；候选不自动成为公共更新 |

截图动作不一定会把图片写入 Android 系统剪贴板。使用“分享 → FloatClip 剪贴板”或应用内图片入口可主动加入。跨设备图片自动同步尚未实现。

应用文件在已授权设备之间传输；用户文件和剪贴板不会作为仓库内容上传 GitHub。一个 GitHub Release 可以同时附 Windows ZIP 和 Android APK。

## 目录

- `windows/FloatClip.Desktop`：.NET 10 / WPF / WPF UI 4.3.0；保留原生剪贴板、窗口、配对和同步服务。
- `app`：Android 原生应用，包名 `com.floatclip.probe`，minSdk 28 / targetSdk 30。
- `share-core`：跨端协议；`nearby-probe` 仍供 app 复用源码/资源，不能直接删除。
- `updates`：公共更新元数据与开发版本；`web`：可选 Web/iPad 入口。
- `docs/design`：唯一 Windows Design System、变更与证据；`docs/ui`：交互参考稿。
- `scripts`：构建、协议检查、打包及验签工具；`.github/workflows`：两端 CI。

## 构建

Windows 需要 .NET SDK 10.0.402：

```powershell
dotnet build windows/FloatClip.Desktop -c Release
dotnet run --project windows/FloatClip.DesktopChecks -c Release
```

Android 需要 JDK 17、SDK 35 / build-tools 35.0.1、Gradle Wrapper：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

覆盖升级当前 Android 需要原包名与原签名。开发/CI 默认签名不能替代原发布身份；签名私钥、配对库、用户历史和本机工具链不进入 Git。

用户负责实际外观检查；构建和隔离自动检查不能代表所有 DPI、蓝牙/局域网硬件、后台保活或系统材质均已验收。详见候选记录。

## 历史与维护

[历史首页](docs/archive/README_PRE_078.md)、[历史状态](docs/archive/STATUS_PRE_078.md)、[仓库维护与权限](docs/REPOSITORY_MAINTENANCE_2026-10-23.md)。旧 Releases/tags 保留，不重写历史。`dist` 中暂留旧流程仍引用的 0.5.5；旧版 APK 请使用对应 Release。
