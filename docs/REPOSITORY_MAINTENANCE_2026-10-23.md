# 仓库维护（2026-10-23）

本轮检查发现首页同时宣称 0.5.5、0.6.0、0.7.5，STATUS 又停在 Windows 0.7.7 / Android 0.7.6。收敛为一个当前入口：根 README → docs/README → 当前候选及唯一 Windows 设计规范。原文归档，没有抹除旧版决策和证据。

## 文件整理

- GitHub API 已核对 v0.4.0、v0.5.0–v0.5.5 的 Releases 均保留各自 APK，附件大小与仓库旧包匹配。移除当前目录中 0.4.0–0.5.4 的六个重复 APK 和校验文件；旧 tag/提交仍含原件。普通提交删除不等于清除 Git 历史或缩小既有克隆。
- build 27 起移除 `dist/` 中最后一份 0.5.5 APK、摘要与 `release.env`。tag Release 现在从 `updates/share-preview.json` 读取已冻结双端候选，重新校验 SHA-256/大小后发布 Android APK + Windows ZIP，不在 Git 中保存安装包。
- Windows WPF CI 与 Android CI 同仓维护；CI 自建 debug keystore 只作构建证据，正式 Android Release 必须复用现有稳定签名身份。
- `nearby-probe` 仍是 app 的源码/资源依赖，不能按名字当作废弃试验目录删除。`share-core`、`windows`、`updates`、`web` 仍有使用者。
- 不删除发布记录、tag 或分支，不重写历史，不把本机记录、配对数据、签名私钥和凭据放进 Git。

## 权限核对

2026-10-23 已通过小主机既有部署密钥完成源码分支推送。GitHub main 已合并上一轮 PR #1，提交为 `4da4923d85e6c15921708f36017af395f6fc6d4f`。

GitHub 连接器返回 `pull=true`，`push/maintain/admin=false`；它只能读取。部署密钥可推源码，不代表可以通过 GitHub REST 上传 Release 附件或修改仓库设置。当前无需重新生成源码推送密钥；需要发布/管理权限时再单独处理，不能把这些权限混为一谈。

## 一仓多端

同一仓库维护 Windows、Android、共享协议及更新清单；一个 Release 可以放多个平台的安装包。仓库不是应用用户文件/图片的传输服务器：共享文件通过已授权设备的局域网会话列出和按需下载，不上传 GitHub。
