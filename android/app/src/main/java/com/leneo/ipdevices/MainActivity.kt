package com.leneo.ipdevices

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.leneo.ipdevices.camera.BuiltinCameraManager
import com.leneo.ipdevices.databinding.ActivityMainBinding
import com.leneo.ipdevices.model.BridgeSnapshot
import com.leneo.ipdevices.model.CameraInfo
import com.leneo.ipdevices.model.SerialInfo
import com.leneo.ipdevices.util.CameraPrefs
import com.leneo.ipdevices.util.NetworkUtils
import com.leneo.ipdevices.util.SerialPrefs

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var service: BridgeService? = null
    private val baudRates = intArrayOf(9600, 115200, 230400, 460800, 921600, 1_000_000, 2_000_000, 4_000_000)
    private var updatingUi = false
    private var faceOn = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) {
            startBridge()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as BridgeService.LocalBinder).service()
            service?.snapshot?.observe(this@MainActivity) { render(it) }
            service?.pcWriting?.observe(this@MainActivity) { setFace(it == true) }
            render(service?.currentSnapshot())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.spinnerBaud.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            baudRates.map { if (it >= 1_000_000) "${it / 1_000_000}000000" else it.toString() },
        )
        binding.spinnerBaud.setSelection(baudRates.indexOf(1_000_000).coerceAtLeast(0))
        binding.spinnerBaud.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updatingUi) service?.setBaud(baudRates[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.switchService.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (checked) ensurePermissionsAndStart() else stopBridge()
        }
        binding.btnRefresh.setOnClickListener { service?.refreshUsb() ?: ensurePermissionsAndStart() }
        binding.btnBattery.setOnClickListener { requestIgnoreBattery() }
        binding.switchBack.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            CameraPrefs.setFacingEnabled(this, "back", checked)
            service?.setFacingEnabled("back", checked)
        }
        binding.switchFront.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            CameraPrefs.setFacingEnabled(this, "front", checked)
            service?.setFacingEnabled("front", checked)
        }

        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            ensurePermissionsAndStart()
        }
        renderLocal()
    }

    override fun onStart() {
        super.onStart()
        if (BridgeService.instance != null) {
            bindService(Intent(this, BridgeService::class.java), connection, Context.BIND_AUTO_CREATE)
            updatingUi = true
            binding.switchService.isChecked = true
            updatingUi = false
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unbindService(connection) }
        service = null
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startBridge()
        }
    }

    private fun startBridge() {
        val intent = Intent(this, BridgeService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: RuntimeException) {
            updatingUi = true
            binding.switchService.isChecked = false
            updatingUi = false
            binding.txtStatus.text = "服务启动失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun stopBridge() {
        setFace(false)
        runCatching { unbindService(connection) }
        service = null
        stopService(Intent(this, BridgeService::class.java))
        renderLocal()
    }

    private fun setFace(on: Boolean) {
        if (on == faceOn) return
        faceOn = on
        binding.robotEyes.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, binding.robotEyes).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(false)
                setTurnScreenOn(false)
            }
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, binding.root).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun render(snap: BridgeSnapshot?) {
        if (snap == null) {
            renderLocal()
            return
        }
        updatingUi = true
        binding.switchService.isChecked = snap.running
        binding.txtStatus.text = buildStatus(snap)
        bindFacingSwitches(snap)
        val primaryIds = primaryFacingIds(snap.cameras)
        fillCameras(
            binding.listBuiltin,
            snap.cameras.filter { it.kind == "builtin" && it.id !in primaryIds },
            emptyHint = null,
        )
        fillCameras(binding.listUvc, snap.cameras.filter { it.kind == "uvc" })
        fillSerial(snap.serial)
        val idx = baudRates.indexOf(snap.serial.firstOrNull()?.baud ?: 1_000_000)
        if (idx >= 0) binding.spinnerBaud.setSelection(idx)
        updatingUi = false
    }

    private fun renderLocal() {
        val ips = NetworkUtils.ipv4Addresses(this).ifEmpty { listOf("127.0.0.1") }
        binding.txtStatus.text = "服务未启动\n本机 IP: ${ips.joinToString()}\n启动后 Termux 可用 http://127.0.0.1:8080"
        bindFacingSwitches(null)
        binding.listBuiltin.removeAllViews()
        binding.listUvc.removeAllViews()
        binding.listSerial.removeAllViews()
    }

    private fun bindFacingSwitches(snap: BridgeSnapshot?) {
        val facings = BuiltinCameraManager.availableFacings(this)
        val previous = updatingUi
        updatingUi = true
        binding.switchBack.isEnabled = facings.contains("back")
        binding.switchFront.isEnabled = facings.contains("front")
        binding.switchBack.isChecked = CameraPrefs.backEnabled(this)
        binding.switchFront.isChecked = CameraPrefs.frontEnabled(this)
        val backOpen = snap?.cameras?.any { it.kind == "builtin" && it.facing == "back" && it.opened } == true
        val frontOpen = snap?.cameras?.any { it.kind == "builtin" && it.facing == "front" && it.opened } == true
        binding.txtBackPath.text = when {
            !facings.contains("back") -> "未检测到后置摄像头"
            !CameraPrefs.backEnabled(this) -> "/cam/back/mjpeg  · 已禁用"
            backOpen -> "/cam/back/mjpeg  · Ubuntu 拉流中"
            else -> "/cam/back/mjpeg  · 按需打开"
        }
        binding.txtFrontPath.text = when {
            !facings.contains("front") -> "未检测到前置摄像头"
            !CameraPrefs.frontEnabled(this) -> "/cam/front/mjpeg  · 已禁用"
            frontOpen -> "/cam/front/mjpeg  · Ubuntu 拉流中"
            else -> "/cam/front/mjpeg  · 按需打开"
        }
        updatingUi = previous
    }

    private fun primaryFacingIds(cameras: List<CameraInfo>): Set<String> {
        val back = cameras.firstOrNull { it.kind == "builtin" && it.facing == "back" }?.id
        val front = cameras.firstOrNull { it.kind == "builtin" && it.facing == "front" }?.id
        return setOfNotNull(back, front)
    }

    private fun buildStatus(snap: BridgeSnapshot): String {
        val ip = snap.ips.joinToString()
        val lines = mutableListOf(
            if (snap.running) "服务运行中" else "服务未启动",
            "IP: $ip",
            "控制台: http://${snap.ips.first()}:${snap.httpPort}/",
            "info: http://${snap.ips.first()}:${snap.httpPort}/info.json",
            "拉流: /cam/back/mjpeg  /cam/front/mjpeg  /uvc/mjpeg",
        )
        val host = "http://${snap.ips.first()}:${snap.httpPort}"
        val listedCams = if (landscape()) snap.cameras else snap.cameras.filter { it.opened }
        listedCams.forEach { cam ->
            val state = when {
                cam.error != null -> cam.error
                cam.opened -> "拉流中"
                else -> "按需打开"
            }
            lines += "${cam.label} [$state]: $host${cam.path}"
        }
        val listedSerial = if (landscape()) snap.serial else snap.serial.filter { it.opened }
        listedSerial.forEach {
            val state = if (it.opened) "已打开" else "等待连接"
            val serial = it.serial.ifEmpty { "-" }
            lines += "${it.label} [$state] serial $serial VID:PID ${it.vid.toString(16)}:${it.pid.toString(16)} tcp://${snap.ips.first()}:${it.tcpPort} @ ${it.baud}"
        }
        snap.cameras.mapNotNull { it.error }.forEach { lines += "错误: $it" }
        snap.serial.mapNotNull { it.error }.forEach { lines += "错误: $it" }
        return lines.joinToString("\n")
    }

    private fun landscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun fillCameras(container: LinearLayout, cams: List<CameraInfo>, emptyHint: String? = "未检测到") {
        container.removeAllViews()
        if (cams.isEmpty()) {
            if (emptyHint != null) container.addView(hint(emptyHint))
            return
        }
        cams.forEach { cam ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(16, 12, 16, 12)
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.card))
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(TextView(this).apply {
                text = "${cam.label}\n${cam.path}" + (cam.error?.let { "\n$it" } ?: "")
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                layoutParams = lp
            })
            if (cam.kind == "builtin") {
                row.addView(MaterialSwitch(this).apply {
                    isChecked = cam.allowed
                    setOnCheckedChangeListener { _, checked ->
                        if (!updatingUi) service?.toggleBuiltin(cam.id, checked)
                    }
                })
            }
            val itemLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8 }
            container.addView(row, itemLp)
        }
    }

    private fun fillSerial(items: List<SerialInfo>) {
        binding.listSerial.removeAllViews()
        if (items.isEmpty()) {
            binding.listSerial.addView(hint("未检测到 USB 串口。插入 CH340 / CP2102 / FTDI / CDC 后点刷新。"))
            return
        }
        val ports = SerialPrefs.PORTS
        items.forEach { s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 12, 16, 12)
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.card))
            }
            val serialText = s.serial.ifEmpty { "读取中（需 USB 权限）" }
            row.addView(TextView(this).apply {
                text = "${s.label}\nserial $serialText\nVID:PID ${s.vid.toString(16)}:${s.pid.toString(16)}\n${if (s.opened) "已打开" else "已识别，等待连接"} ${s.error ?: ""}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            })
            val portRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 0)
            }
            portRow.addView(TextView(this).apply {
                text = "TCP 端口"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            })
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                ports.map { it.toString() },
            )
            val idx = ports.indexOf(s.tcpPort).coerceAtLeast(0)
            spinner.setSelection(idx)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val port = ports[position]
                    if (!updatingUi && port != s.tcpPort) service?.setSerialTcpPort(s.id, port)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            portRow.addView(spinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12
            })
            row.addView(portRow)
            val itemLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8 }
            binding.listSerial.addView(row, itemLp)
        }
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
        setPadding(8, 8, 8, 8)
    }

    private fun requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT < 23) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }
    }
}
