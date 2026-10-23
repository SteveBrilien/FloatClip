# 原生更新源与发布说明（0.7.8 候选）

Windows 与 Android 0.7.8 / build 23 共同使用：

| 用途 | 地址 |
| --- | --- |
| 主源 | `https://clip.wmy-cloud.cn/release.json` 的 `nativeUpdates` 字段 |
| 备用 | `https://raw.githubusercontent.com/SteveBrilien/FloatClip/main/updates/native-updates.json` |
| 安装包 | 清单里的既有 `mcp.wmy-cloud.cn` 不可变制品 URL；兼容已有 GitHub 安装包地址 |

原来 Windows 和 Android 预览通道依赖 GitHub Raw，Android 开发通道还指向缺失的 `share-dev.json`。拟用的 `/updates/share-preview.json` 为 404。本轮复用已存在的 release.json 增加原生字段，没有暴露主机目录，也没有修改应用授权、隧道或服务器程序。Web 原有发布信息完整保留。

## 信任与故障处理

- 清单采用标准 JWS Compact / RS256（RSA 3072 位）；两端内置同一公钥，校验算法、key id、签名、签发/到期时间、schema 与通道。签名覆盖 JWS 原始编码字节，不自创 JSON 规范化算法。
- key id：`floatclip-updates-20261023`。公钥 SPKI SHA-256：`b123cc42aa2cf222bc7c6f35c03dde0bfc8ad229d37ece11f979ab900cf42fc2`。公钥在 `updates/native-update-public.pem`。
- 每个源限时：Android 6 秒；Windows build 24 起 12 秒，改用 WinHTTP 与系统代理。外层清单限 128 KiB。连接/超时/HTTP 服务不可用才尝试备用；签名、结构、通道或时间错误直接失败，不能用备用源掩盖。
- HTTPS、下载域名/端口限制、防降级保留。检查失败显示失败状态，不能称“已是最新”；候选高于公共版本时明确说明当前为试用版本。
- Windows 下载继续核对 SHA-256 与精确大小，通过后才进入原有更新/重启流程。Android 本轮校验清单里的包名、证书指纹、摘要与大小格式，下载仍交给浏览器/系统安装器；没有新增应用内 APK 流式下载或对下载文件的 SHA-256 实测，不能宣称这部分已完成。Android 升级仍依赖原有 APK 签名线和系统安装确认。
- 自托管源仍依赖公网入口、隧道、小主机在线及带宽，主备降低单点影响，不能保证永不失败。

## 本轮线上验证

2026-10-23 实测主源与备用均 HTTP 200；Windows 用实际编译的验签代码验证主源和下载下来的备用清单，均为合法的公开 0.7.1 / build 14。APK 与 ZIP 下载地址 HEAD 均 HTTP 200，Content-Length 分别 3,078,476 / 69,688,590，与签名清单一致。该步骤没有安装公共旧包。

公开清单仍指向 0.7.1，候选 0.7.8 尚未提升为公共更新。旧客户端仍使用编译时 GitHub 地址，需要一次安装过渡候选；部署服务器字段不会自动修改旧 EXE/APK。

GitHub main 的元数据提交：`9633336e6ab5ad88b014cc42c34495956c87cada`；保持 Web 源文件中签名字段的提交：`1c1be132f72108bcaf992844b63e4d9cbcd0f0e9`。运行中的公开 release.json 已原子合并相同字段。后续 Web 构建也会保留它。

## 后续发布顺序

1. 在候选通过用户检查后构建两端包，确认版本/build、APK 包名与原签名，验证 ZIP、包大小和 SHA-256。
2. 先发布不可变安装包，再更新 `updates/share-preview.json` 的各平台包信息。不能复用会过期的候选下载链接作正式更新地址。
3. 在持有原发布私钥的 Windows 上运行以下命令；私钥仅保存在本机 `%LOCALAPPDATA%/FloatClip/ReleaseKeys/`，不得复制到 Git 或公网小主机。

```powershell
python scripts/sign-native-updates.py --key "$env:LOCALAPPDATA/FloatClip/ReleaseKeys/native-updates-rsa.pem"
```

4. 脚本生成 `updates/native-updates.json`，并将同一 `nativeUpdates` 合并到 `web/public/release.json`；保持 Web 其他字段。常规更新不应变更编译公钥，缺少原私钥时不能静默换钥。
5. 发布前运行 `FloatClip.UpdateChecks --verify-file <待发布清单>`；主源原子合并字段，GitHub 同步相同签名清单，复查两处 HTTPS 与实际验签，再通过正式包验证升级。不得上传签名私钥、配对库、历史或用户日志。

本次元数据有效期 90 天，`expiresAt=1800487864`。到期前每次发版需用原钥重新签发；到期会明确拒绝更新元数据。没有创建后台续签任务，云端没有私钥。应备份原发布密钥，并把清单有效期检查纳入发布步骤。

参考：[RFC 7515 JWS](https://www.rfc-editor.org/rfc/rfc7515)、[Android 应用签名](https://developer.android.com/studio/publish/app-signing)。
