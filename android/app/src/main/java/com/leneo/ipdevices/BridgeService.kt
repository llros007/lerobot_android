package com.leneo.ipdevices

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import com.leneo.ipdevices.camera.BuiltinCameraManager
import com.leneo.ipdevices.camera.UsbUvcCameraManager
import com.leneo.ipdevices.model.BridgeSnapshot
import com.leneo.ipdevices.net.FrameHub
import com.leneo.ipdevices.net.HttpBridgeServer
import com.leneo.ipdevices.serial.UsbSerialManager
import com.leneo.ipdevices.util.CameraPrefs
import com.leneo.ipdevices.util.NetworkUtils
import org.json.JSONArray
import org.json.JSONObject

class BridgeService : LifecycleService() {
    inner class LocalBinder : Binder() {
        fun service(): BridgeService = this@BridgeService
    }

    private val binder = LocalBinder()
    val snapshot = MutableLiveData<BridgeSnapshot>()
    val pcWriting = MutableLiveData(false)

    private val hub = FrameHub()
    private lateinit var builtin: BuiltinCameraManager
    private lateinit var uvc: UsbUvcCameraManager
    private lateinit var serial: UsbSerialManager
    private var http: HttpBridgeServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val enabledBuiltin = linkedSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closeTasks = HashMap<String, Runnable>()
    private val lastPcWriteMs = java.util.concurrent.atomic.AtomicLong(0)
    private var faceHoldQueued = false
    private var faceOpen = false
    private val hideFace = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() - lastPcWriteMs.get() >= FACE_IDLE_MS) {
                faceOpen = false
                pcWriting.value = false
            } else {
                mainHandler.postDelayed(this, FACE_IDLE_MS)
            }
        }
    }
    @Volatile
    var running: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        builtin = BuiltinCameraManager(this, hub) { publishSnapshot() }
        uvc = UsbUvcCameraManager(this, hub) { publishSnapshot() }
        serial = UsbSerialManager(this, { publishSnapshot() }, { notePcWrite() })
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundInternal()
        if (!running) {
            startBridge()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        stopBridge()
        instance = null
        super.onDestroy()
    }

    fun toggleBuiltin(cameraId: String, enable: Boolean) {
        if (enable) {
            enabledBuiltin += cameraId
        } else {
            enabledBuiltin -= cameraId
            builtin.close(cameraId)
        }
        publishSnapshot()
    }

    fun setFacingEnabled(facing: String, enable: Boolean) {
        CameraPrefs.setFacingEnabled(this, facing, enable)
        if (!enable) {
            builtin.listDevices().filter { it.facing == facing }.forEach { builtin.close(it.cameraId) }
        }
        publishSnapshot()
    }

    fun setBaud(baud: Int) {
        serial.setBaud(baud)
        publishSnapshot()
    }

    fun setSerialTcpPort(deviceId: String, port: Int) {
        serial.setTcpPort(deviceId, port)
        publishSnapshot()
    }

    fun refreshUsb() {
        uvc.requestPermissions()
        serial.scanAndOpen()
        publishSnapshot()
    }

    /** PC started pushing joint goals. The activity turns into the robot face. */
    fun notePcWrite() {
        lastPcWriteMs.set(SystemClock.uptimeMillis())
        if (faceHoldQueued) return
        faceHoldQueued = true
        mainHandler.post {
            faceHoldQueued = false
            if (!faceOpen) {
                faceOpen = true
                pcWriting.value = true
                openFace()
            }
            mainHandler.removeCallbacks(hideFace)
            mainHandler.postDelayed(hideFace, FACE_IDLE_MS)
        }
    }

    private fun openFace() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        }
        runCatching { startActivity(intent) }
    }

    fun currentSnapshot(): BridgeSnapshot {
        val ips = NetworkUtils.ipv4Addresses(this)
        return BridgeSnapshot(
            running = running,
            httpPort = HttpBridgeServer.DEFAULT_PORT,
            ips = ips.ifEmpty { listOf("127.0.0.1") },
            cameras = (builtin.infos() + uvc.infos()).map { cam ->
                cam.copy(allowed = when {
                    cam.kind != "builtin" -> true
                    cam.facing == "back" -> CameraPrefs.backEnabled(this)
                    cam.facing == "front" -> CameraPrefs.frontEnabled(this)
                    else -> enabledBuiltin.contains(cam.id)
                })
            },
            serial = serial.infos(),
            message = if (running) "服务已启动" else "服务未启动",
        )
    }

    private fun startBridge() {
        running = true
        // Hold for the whole service lifetime so ColorOS/Hans cannot freeze the
        // process while LAN clients (LeRobot) talk to HTTP/serial without adb.
        acquireWifiLock()
        acquireCpuLock()
        hub.addDemandListener(deviceDemandListener)
        builtin.start()
        enabledBuiltin.clear()
        uvc.start()
        serial.start()
        http = HttpBridgeServer(
            port = HttpBridgeServer.DEFAULT_PORT,
            hub = hub,
            indexHtml = { dashboardHtml() },
            infoJson = { infoJson() },
            resolveStream = { resolveStream(it) },
        ).also { it.start() }
        publishSnapshot()
    }

    private fun stopBridge() {
        running = false
        faceOpen = false
        mainHandler.removeCallbacks(hideFace)
        pcWriting.value = false
        http?.stop()
        http = null
        closeTasks.values.forEach { mainHandler.removeCallbacks(it) }
        closeTasks.clear()
        builtin.stop()
        uvc.stop()
        serial.stop()
        hub.removeDemandListener(deviceDemandListener)
        releaseLocks()
        publishSnapshot()
    }

    private fun resolveStream(path: String): String? {
        val p = path.trimEnd('/')
        when (p) {
            "/video/mjpeg", "/video", "/stream", "/cam/mjpeg" -> {
                builtin.backCameraId()?.takeIf { isBuiltinAllowed(it) }?.let { return "cam/$it" }
                builtin.listDevices().firstOrNull { isBuiltinAllowed(it.cameraId) }?.let { return "cam/${it.cameraId}" }
                uvc.firstOpenedId()?.let { return "uvc/$it" }
                return null
            }
            "/uvc/mjpeg", "/usb/mjpeg", "/usbcam/mjpeg" -> {
                return uvc.firstOpenedId()?.let { "uvc/$it" } ?: "uvc/first"
            }
        }
        val cam = Regex("^/cam/([^/]+)").find(p)
        if (cam != null) {
            val id = cam.groupValues[1]
            val resolved = builtin.resolveAlias(id) ?: id
            if (!isBuiltinAllowed(resolved)) return null
            return "cam/$resolved"
        }
        val uvcMatch = Regex("^/uvc/([^/]+)").find(p)
        if (uvcMatch != null) {
            return "uvc/${uvcMatch.groupValues[1]}"
        }
        return null
    }

    private fun infoJson(): String {
        val snap = currentSnapshot()
        val root = JSONObject()
        root.put("service", "leneo-ip-devices")
        root.put("httpPort", snap.httpPort)
        root.put("ips", JSONArray(snap.ips))
        val cams = JSONArray()
        snap.cameras.forEach { cam ->
            cams.put(
                JSONObject()
                    .put("id", cam.id)
                    .put("kind", cam.kind)
                    .put("label", cam.label)
                    .put("facing", cam.facing ?: JSONObject.NULL)
                    .put("opened", cam.opened)
                    .put("error", cam.error ?: JSONObject.NULL)
                    .put("mjpeg", httpUrl(snap, cam.path))
                    .put("snapshot", httpUrl(snap, cam.path.replace("/mjpeg", "/snapshot"))),
            )
        }
        root.put("cameras", cams)
        val serialArr = JSONArray()
        snap.serial.forEach { s ->
            serialArr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("label", s.label)
                    .put("serial", s.serial)
                    .put("vid", s.vid)
                    .put("pid", s.pid)
                    .put("tcp", "tcp://${snap.ips.first()}:${s.tcpPort}")
                    .put("baud", s.baud)
                    .put("opened", s.opened)
                    .put("error", s.error ?: JSONObject.NULL),
            )
        }
        root.put("serial", serialArr)
        return root.toString(2)
    }

    private fun dashboardHtml(): String {
        val snap = currentSnapshot()
        val ip = snap.ips.firstOrNull() ?: "127.0.0.1"
        val camRows = snap.cameras.joinToString("") { cam ->
            val url = httpUrl(snap, cam.path)
            """
            <div class="card">
              <h3>${escape(cam.label)}</h3>
              <p>状态: ${if (cam.opened) "已打开" else "未打开"} ${cam.error ?: ""}</p>
              <p><a href="${cam.path}">${escape(url)}</a></p>
            </div>
            """.trimIndent()
        }
        val serialRows = snap.serial.joinToString("") { s ->
            """
            <div class="card">
              <h3>${escape(s.label)}</h3>
              <p>serial ${escape(s.serial.ifEmpty { "-" })} · TCP ${ip}:${s.tcpPort} @ ${s.baud} ${if (s.opened) "已打开" else "未打开"}</p>
              <pre>socat pty,link=/tmp/ttyAndroid,raw,echo=0 tcp:$ip:${s.tcpPort}</pre>
            </div>
            """.trimIndent()
        }
        return """
            <!doctype html>
            <html lang="zh">
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1"/>
              <title>LeNeo 设备桥</title>
              <style>
                body { font-family: sans-serif; background:#0b1220; color:#e8eef7; margin:0; padding:24px; }
                a { color:#3ddc97; }
                .card { background:#151d2e; border-radius:12px; padding:16px; margin:12px 0; }
                img { max-width:100%; background:#000; }
                pre { white-space:pre-wrap; background:#0b1220; padding:8px; }
              </style>
            </head>
            <body>
              <h1>LeNeo 设备桥</h1>
              <p>HTTP :${snap.httpPort} &nbsp; IP: ${snap.ips.joinToString()}</p>
              <p><a href="/info.json">info.json</a></p>
              <h2>摄像头</h2>
              $camRows
              <h2>串口</h2>
              $serialRows
            </body>
            </html>
        """.trimIndent()
    }

    private fun httpUrl(snap: BridgeSnapshot, path: String): String {
        val ip = snap.ips.firstOrNull() ?: "127.0.0.1"
        return "http://$ip:${snap.httpPort}$path"
    }

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun publishSnapshot() {
        snapshot.postValue(currentSnapshot())
    }

    private fun startForegroundInternal() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFY_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private val deviceDemandListener: (String, Boolean) -> Unit = { streamId, active ->
        mainHandler.post {
            if (active) {
                closeTasks.remove(streamId)?.let { mainHandler.removeCallbacks(it) }
                openForStream(streamId)
            } else {
                val task = Runnable {
                    if (!hub.hasViewer(streamId)) closeForStream(streamId)
                }
                closeTasks[streamId] = task
                mainHandler.postDelayed(task, IDLE_CLOSE_MS)
            }
        }
    }

    private fun openForStream(streamId: String) {
        when {
            streamId.startsWith("cam/") -> {
                val id = streamId.removePrefix("cam/")
                val cameraId = builtin.resolveAlias(id) ?: id
                if (!isBuiltinAllowed(cameraId)) return
                builtin.open(cameraId)
            }
            streamId.startsWith("uvc/") -> {
                val token = streamId.removePrefix("uvc/")
                uvc.resolveDeviceId(token)?.let { uvc.openPreview(it) }
            }
        }
    }

    private fun closeForStream(streamId: String) {
        when {
            streamId.startsWith("cam/") -> {
                val id = streamId.removePrefix("cam/")
                val cameraId = builtin.resolveAlias(id) ?: id
                if (!hub.hasViewer("cam/$cameraId", "cam/front", "cam/back")) {
                    builtin.close(cameraId)
                }
            }
            streamId.startsWith("uvc/") -> {
                val token = streamId.removePrefix("uvc/")
                val deviceId = uvc.resolveDeviceId(token) ?: return
                if (!hub.hasViewer("uvc/$deviceId", "uvc/first")) {
                    uvc.closePreview(deviceId)
                }
            }
        }
        publishSnapshot()
    }

    private fun isBuiltinAllowed(cameraId: String): Boolean {
        val facing = builtin.listDevices().firstOrNull { it.cameraId == cameraId }?.facing
        return when (facing) {
            "back" -> CameraPrefs.backEnabled(this)
            "front" -> CameraPrefs.frontEnabled(this)
            else -> enabledBuiltin.contains(cameraId)
        }
    }

    private fun anyCameraOpen(): Boolean =
        builtin.infos().any { it.opened } || uvc.infos().any { it.opened }

    private fun acquireCpuLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ipdevices:cpu").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(mode, "ipdevices:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
    }

    private fun releaseCpuLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun releaseLocks() {
        releaseCpuLock()
        releaseWifiLock()
    }

    companion object {
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFY_ID = 7
        // Keep UVC/builtin warm between record reconnects; cold UVC open is multi-second.
        private const val IDLE_CLOSE_MS = 60_000L
        private const val FACE_IDLE_MS = 1_500L
        @Volatile
        var instance: BridgeService? = null
            private set
    }
}
