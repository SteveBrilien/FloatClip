package com.floatclip.probe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.ArrayDeque
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

// Diagnostic-only UI: capability labels intentionally include raw generated values.
@SuppressLint("SetTextI18n")
class ProbeActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var capability: TextView
    private lateinit var network: TextView
    private lateinit var events: TextView
    private lateinit var devices: Spinner
    private lateinit var probe: BluetoothProbe
    private lateinit var lanAddresses: Spinner
    private lateinit var lanDetails: TextView
    private var lanIps: List<String> = emptyList()
    private var lanProbe: LanProbe? = null
    private var lanUiEpoch = 0
    private var foreground = false
    private val lines = ArrayDeque<String>()
    private var paired: List<BluetoothDevice> = emptyList()
    private val adapter: BluetoothAdapter? get() = getSystemService(BluetoothManager::class.java)?.adapter
    private val connectivity get() = getSystemService(ConnectivityManager::class.java)
    private var callbackRegistered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) = refreshNetwork()
        override fun onLost(n: Network) = refreshNetwork()
        override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = refreshNetwork()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probe = BluetoothProbe { message -> runOnUiThread { log(message) } }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(dp(20), dp(16) + insets.systemWindowInsetTop, dp(20), dp(24) + insets.systemWindowInsetBottom)
            insets
        }
        label("FloatClip 连接测试", 25f)
        label("独立测试版 · 仅发送生成的测试文字", 15f)
        capability = label("", 14f)
        network = label("", 14f)
        button("刷新连接能力与已配对设备") { refresh() }
        label("Wi-Fi / 有线局域网", 18f)
        lanAddresses = Spinner(this)
        root.addView(lanAddresses)
        button("等待电脑通过局域网连接（90秒）") { startLan() }
        button("停止局域网测试") { stopLan(); log("LAN 已手动停止") }
        lanDetails = label("选择手机已有的局域网地址；不切换 Wi-Fi 或路由。", 12f)
        lanDetails.setTextIsSelectable(true)
        label("可选：蓝牙测试", 18f)
        button("打开系统蓝牙设置") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        devices = Spinner(this)
        root.addView(devices)
        button("等待所选设备连接") { withPeer { peer -> adapter?.let { probe.listen(it, peer) } } }
        button("连接所选设备（另一台 Android）") { withPeer { probe.connect(it) } }
        button("再发送 10 条测试文字") { probe.sendBatch() }
        button("断开") { probe.stop(); log("已手动断开") }
        events = label("", 13f)
        events.setTextIsSelectable(true)
        label("切到后台会结束测试连接；本轮不验证长期后台能力。", 13f)
        refresh()
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (!callbackRegistered) {
            connectivity.registerDefaultNetworkCallback(callback)
            callbackRegistered = true
        }
        refreshNetwork()
    }

    override fun onResume() { super.onResume(); if (::devices.isInitialized) refresh() }

    override fun onStop() {
        foreground = false
        stopLan()
        probe.stop()
        if (callbackRegistered) { connectivity.unregisterNetworkCallback(callback); callbackRegistered = false }
        super.onStop()
    }

    override fun onDestroy() { stopLan(); probe.close(); super.onDestroy() }

    private fun hasBluetoothPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun refresh() {
        val bt = adapter
        val permitted = hasBluetoothPermission()
        val wifi = getSystemService(WifiManager::class.java)
        val concurrentAp = if (Build.VERSION.SDK_INT >= 30) wifi?.isStaApConcurrencySupported?.toString() ?: "未知" else "系统未提供查询"
        capability.text = listOf(
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
            "蓝牙硬件：${bt != null}；蓝牙开关：${if (permitted) bt?.isEnabled ?: false else "待授权"}",
            "BLE：${packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)}",
            "Wi-Fi Direct：${packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)}",
            "Wi-Fi + 热点并发报告：$concurrentAp",
            "Wi-Fi + Wi-Fi Direct 同时上网：尚未实测"
        ).joinToString("\n")
        paired = if (permitted) bt?.bondedDevices?.sortedBy { it.name ?: "" }.orEmpty() else emptyList()
        val names = paired.map { "${it.name ?: "未命名设备"} · …${it.address.takeLast(5)}" }
        devices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            names.ifEmpty { listOf(if (permitted) "尚无已配对设备" else "需要附近设备授权") })
        val previousIp = lanIps.getOrNull(lanAddresses.selectedItemPosition)
        lanIps = try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).filter {
                it.isUp && (it.name.startsWith("wlan") || it.name.startsWith("eth") ||
                    it.name.startsWith("ap") || it.name.startsWith("en"))
            }.flatMap { nic -> Collections.list(nic.inetAddresses) }
                .filter { it is Inet4Address && it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }.distinct().sorted()
        } catch (_: java.net.SocketException) { emptyList() }
        lanAddresses.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            lanIps.ifEmpty { listOf("未发现局域网地址：先连接 Wi-Fi 或开启热点") })
        previousIp?.let { old -> lanIps.indexOf(old).takeIf { it >= 0 }?.let { lanAddresses.setSelection(it) } }
        refreshNetwork()
    }

    private fun startLan() {
        val ip = lanIps.getOrNull(lanAddresses.selectedItemPosition)
        if (ip == null) { log("请先建立可用 Wi-Fi / 热点 / 有线网络，再刷新"); return }
        stopLan()
        val epoch = lanUiEpoch
        lanProbe = LanProbe(object : LanProbe.Events {
            override fun ready(address: String, port: Int, pin: String, invitation: String) = runOnUiThread {
                if (epoch != lanUiEpoch || !foreground) return@runOnUiThread
                lanDetails.text = "地址：$address\n端口：$port\n证书 SHA-256：\n$pin\n一次性邀请：\n$invitation\n在 Windows 输入 lan 后填入这些字段。"
                log("LAN 已监听：$address:$port；邀请90秒有效，仅本次使用")
            }
            override fun event(message: String) = runOnUiThread {
                if (epoch == lanUiEpoch && foreground) log(message)
            }
            override fun ended(success: Boolean) = runOnUiThread {
                if (epoch == lanUiEpoch) {
                    lanDetails.text = if (success) "LAN 测试通过，邀请已失效。" else "LAN 测试结束，请重新开启邀请。"
                    lanProbe?.close()
                    lanProbe = null
                }
            }
        })
        try { lanProbe?.start(ip, 46237) }
        catch (error: Exception) { stopLan(); log("LAN 启动失败：${error.message}") }
    }

    private fun stopLan() {
        lanUiEpoch++
        lanProbe?.close()
        lanProbe = null
        if (::lanDetails.isInitialized) lanDetails.text = "LAN 未监听；邀请已失效。"
    }

    private fun withPeer(action: (BluetoothDevice) -> Unit) {
        if (!hasBluetoothPermission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 100)
            return
        }
        val peer = paired.getOrNull(devices.selectedItemPosition)
        if (peer == null) { log("请先在系统蓝牙设置完成配对，再刷新"); return }
        action(peer)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) refresh()
    }

    private fun refreshNetwork() = runOnUiThread {
        if (!::network.isInitialized) return@runOnUiThread
        val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        network.text = "当前默认网络：" + when {
            caps == null -> "无"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "其他"
        } + "（未修改网络配置）"
    }

    private fun log(message: String) {
        if (isDestroyed) return
        if (lines.size == 60) lines.removeFirst()
        lines.addLast(message)
        events.text = lines.joinToString("\n")
    }

    private fun label(text: String, size: Float): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(0xff202124.toInt())
        setPadding(0, dp(7), 0, dp(7))
        root.addView(this)
    }
    private fun button(text: String, action: () -> Unit) {
        root.addView(Button(this).apply { this.text = text; setOnClickListener { action() } })
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
