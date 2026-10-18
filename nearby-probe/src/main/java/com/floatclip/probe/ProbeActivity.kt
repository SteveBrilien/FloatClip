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

// Diagnostic-only UI: capability labels intentionally include raw generated values.
@SuppressLint("SetTextI18n")
class ProbeActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var capability: TextView
    private lateinit var network: TextView
    private lateinit var events: TextView
    private lateinit var devices: Spinner
    private lateinit var probe: BluetoothProbe
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
        if (!callbackRegistered) {
            connectivity.registerDefaultNetworkCallback(callback)
            callbackRegistered = true
        }
        refreshNetwork()
    }

    override fun onResume() { super.onResume(); if (::devices.isInitialized) refresh() }

    override fun onStop() {
        probe.stop()
        if (callbackRegistered) { connectivity.unregisterNetworkCallback(callback); callbackRegistered = false }
        super.onStop()
    }

    override fun onDestroy() { probe.close(); super.onDestroy() }

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
        refreshNetwork()
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
