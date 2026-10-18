FloatClip 局域网 / 蓝牙连接测试 P0

这是独立验证工具，不是正式剪贴板客户端。只收发程序生成的测试文字，
不读取真实剪贴板，不改变 Wi-Fi、代理、DNS 或路由，不需要管理员权限。

Wi-Fi / 有线局域网测试（0.1.1-p0）
1. 手机与电脑使用当前可互通的网络；手机热点也可，无需蓝牙。
2. 手机测试 App 选择已有局域网地址，点击“等待电脑通过局域网连接”。
3. 保持手机 App 前台；电脑双击 EXE，输入 lan，按提示填写地址、端口、
   手机显示的证书 SHA-256 和一次性邀请。邀请90秒有效，用后失效。
4. 两端通过固定证书指纹的 TLS 1.2 和一次性邀请认证，各校验10条生成文字。
   测试完成、切后台、停止或超时都会关闭监听。错误后窗口保持打开。
   当前不是正式剪贴板同步；Wi-Fi Direct、自动重连和真实文件尚未接入。

以下是可选蓝牙测试，要求电脑确实有经典蓝牙适配器：
使用条件：Windows 电脑有经典蓝牙功能，手机安装并打开“FloatClip 连接测试”。
先在系统蓝牙设置里完成手机与电脑的配对。

更新：双击 FloatClip.NearbyProbe.exe 会进入交互界面，输入 test 自检，
输入手机地址连接，输入 q 退出。失败后窗口保持打开，并保存错误日志。
也可以双击 Start-NearbyProbe.cmd；若 EXE 尚未启动就失败，此入口会停留并显示错误。
错误日志：%LOCALAPPDATA%\FloatClip\NearbyProbe\Logs。

1. 手机上选择这台电脑，点击“等待所选设备连接”。
2. 在本文件所在目录打开 Windows PowerShell，运行：
   .\Start-NearbyProbe.ps1 -List
   记下配对手机的 Address（不要把真实地址发到公开问题记录）。
3. 二选一运行测试：
   .\FloatClip.NearbyProbe.exe --connect AA:BB:CC:DD:EE:FF
   .\Start-NearbyProbe.ps1 -PeerAddress AA:BB:CC:DD:EE:FF
   将占位地址替换为手机地址。PowerShell 入口使用系统自带编译器；
   EXE 为打包的 x64 控制台程序，实际 Win10/Win11 兼容性仍需本机验收。
4. 两边各收发十条测试消息，校验成功后显示 PASS，电脑自动断开。
5. 保持原上网连接，随后分别测试蓝牙耳机连接、断开重连等情况。

可先运行 .\Start-NearbyProbe.ps1 -SelfTest 检查协议实现。
手机切到后台会关闭测试连接；后台常驻与自动剪贴板采集尚未接入。
本轮暂不支持 Windows 作为监听端，也未验证 Wi-Fi Direct 并发。
完整开发说明见 README.md。
