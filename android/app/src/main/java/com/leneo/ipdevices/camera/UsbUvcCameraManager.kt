package com.leneo.ipdevices.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.IFrameCallback
import com.jiangdg.uvc.UVCCamera
import com.leneo.ipdevices.model.CameraInfo
import com.leneo.ipdevices.net.FrameHub
import com.leneo.ipdevices.usb.UsbClassifier
import com.leneo.ipdevices.util.JpegEncoder
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class UsbUvcCameraManager(
    private val context: Context,
    private val hub: FrameHub,
    private val onChanged: () -> Unit = {},
) {
    private var client: MultiCameraClient? = null
    private val cameras = ConcurrentHashMap<Int, MultiCameraClient.ICamera>()
    private val opened = ConcurrentHashMap<Int, Boolean>()
    private val errors = ConcurrentHashMap<Int, String>()
    private val attached = ConcurrentHashMap<Int, UsbDevice>()
    private val loggedSize = ConcurrentHashMap<Int, Boolean>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val openExecutor = Executors.newSingleThreadExecutor()
    private val connecting = ConcurrentHashMap<Int, Boolean>()
    private val rawJpeg = ConcurrentHashMap<Int, Boolean>()
    private val dummyTextures = ConcurrentHashMap<Int, SurfaceTexture>()
    private val ctrlBlocks = ConcurrentHashMap<Int, USBMonitor.UsbControlBlock>()
    private val pendingOpen = ConcurrentHashMap<Int, Boolean>()

    companion object {
        private const val TAG = "UsbUvcCam"
        /** Patched libUVCCamera: deliver USB MJPEG bytes without YUYV decode. */
        private const val PIXEL_FORMAT_MJPEG = 6
    }

    fun start() {
        if (client != null) return
        client = MultiCameraClient(context, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                device ?: return
                if (!UsbClassifier.isUvcCamera(device)) return
                attached[device.deviceId] = device
                if (cameras.containsKey(device.deviceId)) return
                val cam = CameraUVC(context, device)
                cameras[device.deviceId] = cam
                onChanged()
                requestPermissionLater(device)
            }

            override fun onDetachDec(device: UsbDevice?) {
                device ?: return
                attached.remove(device.deviceId)
                cameras.remove(device.deviceId)?.apply {
                    closeCamera()
                    setUsbControlBlock(null)
                }
                opened.remove(device.deviceId)
                rawJpeg.remove(device.deviceId)
                pendingOpen.remove(device.deviceId)
                ctrlBlocks.remove(device.deviceId)
                dummyTextures.remove(device.deviceId)?.release()
                hub.remove("uvc/${device.deviceId}")
                onChanged()
            }

            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device ?: return
                ctrlBlock ?: return
                val cam = cameras[device.deviceId] ?: return
                ctrlBlocks[device.deviceId] = ctrlBlock
                mainHandler.post { cam.setUsbControlBlock(ctrlBlock) }
                Log.i(TAG, "UVC ready ${device.deviceId} ${UsbClassifier.displayName(device)}")
                onChanged()
                if (pendingOpen.remove(device.deviceId) == true) {
                    openPreview(device.deviceId)
                }
            }

            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device ?: return
                cameras[device.deviceId]?.closeCamera()
                opened[device.deviceId] = false
                ctrlBlocks.remove(device.deviceId)
            }

            override fun onCancelDev(device: UsbDevice?) {
                device ?: return
                errors[device.deviceId] = "USB 权限被拒绝"
                onChanged()
            }
        })
        client?.openDebug(false)
        client?.register()
        client?.getDeviceList()?.forEach { device ->
            if (UsbClassifier.isUvcCamera(device)) {
                attached[device.deviceId] = device
                if (!cameras.containsKey(device.deviceId)) {
                    cameras[device.deviceId] = CameraUVC(context, device)
                }
                requestPermissionLater(device)
            }
        }
    }

    private fun requestPermissionLater(device: UsbDevice) {
        openExecutor.execute {
            try {
                Thread.sleep(400)
                mainHandler.post { client?.requestPermission(device) }
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    fun stop() {
        cameras.values.forEach { it.closeCamera() }
        cameras.clear()
        opened.clear()
        attached.clear()
        connecting.clear()
        loggedSize.clear()
        rawJpeg.clear()
        pendingOpen.clear()
        ctrlBlocks.clear()
        dummyTextures.values.forEach { it.release() }
        dummyTextures.clear()
        client?.unRegister()
        client?.destroy()
        client = null
    }

    fun requestPermissions() {
        attached.values.forEach { requestPermissionLater(it) }
        client?.getDeviceList()?.forEach { device ->
            if (UsbClassifier.isUvcCamera(device)) {
                attached[device.deviceId] = device
                if (!cameras.containsKey(device.deviceId)) {
                    cameras[device.deviceId] = CameraUVC(context, device)
                }
                requestPermissionLater(device)
            }
        }
    }

    fun infos(): List<CameraInfo> {
        return attached.values.map { device ->
            CameraInfo(
                id = device.deviceId.toString(),
                kind = "uvc",
                label = UsbClassifier.displayName(device),
                facing = "usb",
                opened = opened[device.deviceId] == true,
                error = errors[device.deviceId],
                path = "/uvc/${device.deviceId}/mjpeg",
            )
        }
    }

    fun firstOpenedId(): String? =
        opened.entries.firstOrNull { it.value }?.key?.toString()
            ?: attached.keys.firstOrNull()?.toString()

    fun resolveDeviceId(token: String): Int? {
        if (token == "first" || token == "mjpeg") return attached.keys.firstOrNull()
        return token.toIntOrNull()?.takeIf { attached.containsKey(it) }
            ?: attached.keys.firstOrNull()
    }

    fun openPreview(deviceId: Int) {
        val device = attached[deviceId] ?: return
        val cam = cameras[deviceId] ?: return
        if (opened[deviceId] == true || connecting[deviceId] == true) return
        val block = ctrlBlocks[deviceId]
        if (block == null) {
            pendingOpen[deviceId] = true
            requestPermissionLater(device)
            return
        }
        if (connecting.putIfAbsent(deviceId, true) == true) return
        openExecutor.execute {
            try {
                val settled = AtomicBoolean(false)
                val done = java.util.concurrent.CountDownLatch(1)
                mainHandler.post {
                    cam.setUsbControlBlock(block)
                    openInternal(cam, device) {
                        if (settled.compareAndSet(false, true)) done.countDown()
                    }
                }
                if (!done.await(8, TimeUnit.SECONDS)) {
                    Log.w(TAG, "open UVC $deviceId timed out")
                }
            } catch (e: Exception) {
                Log.e(TAG, "queue open UVC $deviceId", e)
            } finally {
                connecting.remove(deviceId)
            }
        }
    }

    fun closePreview(deviceId: Int) {
        pendingOpen.remove(deviceId)
        if (opened[deviceId] != true) return
        cameras[deviceId]?.closeCamera()
        opened[deviceId] = false
        rawJpeg.remove(deviceId)
        loggedSize.remove(deviceId)
        dummyTextures.remove(deviceId)?.release()
        hub.remove("uvc/$deviceId")
        Log.i(TAG, "close UVC preview $deviceId")
        onChanged()
    }

    private fun openInternal(
        cam: MultiCameraClient.ICamera,
        device: UsbDevice,
        onSettled: () -> Unit = {},
    ) {
        errors.remove(device.deviceId)
        cam.setCameraStateCallBack(object : ICameraStateCallBack {
            override fun onCameraState(
                self: MultiCameraClient.ICamera,
                code: ICameraStateCallBack.State,
                msg: String?,
            ) {
                when (code) {
                    ICameraStateCallBack.State.OPENED -> {
                        opened[device.deviceId] = true
                        hookNativeMjpeg(cam, device)
                        onSettled()
                    }
                    ICameraStateCallBack.State.CLOSED -> opened[device.deviceId] = false
                    ICameraStateCallBack.State.ERROR -> {
                        opened[device.deviceId] = false
                        errors[device.deviceId] = msg ?: "UVC open error"
                        Log.e(TAG, "UVC error ${device.deviceId}: $msg")
                        onSettled()
                    }
                }
                onChanged()
            }
        })
        val request = CameraRequest.Builder()
            .setPreviewWidth(1280)
            .setPreviewHeight(720)
            .setRenderMode(CameraRequest.RenderMode.NORMAL)
            .setRawPreviewData(true)
            .setAudioSource(CameraRequest.AudioSource.NONE)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .create()
        try {
            cam.openCamera(dummyTexture(device.deviceId), request)
        } catch (e: Exception) {
            errors[device.deviceId] = e.message ?: "openCamera failed"
            onChanged()
            Log.e(TAG, "open UVC ${device.deviceId}", e)
            onSettled()
        }
    }

    private fun dummyTexture(deviceId: Int): SurfaceTexture {
        dummyTextures[deviceId]?.release()
        val texture = if (Build.VERSION.SDK_INT >= 26) {
            SurfaceTexture(false)
        } else {
            SurfaceTexture(0)
        }
        dummyTextures[deviceId] = texture
        return texture
    }

    private fun wanted(deviceId: Int): Boolean =
        hub.hasViewer("uvc/$deviceId", "uvc/first")

    private fun publishUvc(deviceId: Int, jpeg: ByteArray) {
        hub.publish("uvc/$deviceId", jpeg)
        hub.publish("uvc/first", jpeg)
    }

    private fun hookNativeMjpeg(cam: MultiCameraClient.ICamera, device: UsbDevice) {
        try {
            val field = cam.javaClass.getDeclaredField("mUvcCamera")
            field.isAccessible = true
            val uvc = field.get(cam) ?: return

            // AUSBC often falls back to YUYV after open. Restart preview strictly in MJPEG.
            runCatching { uvc.javaClass.getMethod("stopPreview").invoke(uvc) }
            val mjpegSize = chooseMjpegSize(uvc, 1280, 720)
            Log.i(TAG, "UVC force MJPEG ${device.deviceId} ${mjpegSize.first}x${mjpegSize.second}")
            val setSize = uvc.javaClass.methods.first { method ->
                method.name == "setPreviewSize" && method.parameterTypes.size == 6
            }
            setSize.invoke(
                uvc,
                mjpegSize.first,
                mjpegSize.second,
                1,
                30,
                UVCCamera.FRAME_FORMAT_MJPEG,
                1.0f,
            )
            runCatching {
                uvc.javaClass.getMethod("setPreviewTexture", SurfaceTexture::class.java)
                    .invoke(uvc, dummyTexture(device.deviceId))
            }

            val setCb = uvc.javaClass.methods.first { method ->
                method.name == "setFrameCallback" && method.parameterTypes.size == 2
            }
            val callback = IFrameCallback { frame ->
                if (frame == null) return@IFrameCallback
                val jpeg = jpegFromBuffer(frame)
                if (jpeg != null) {
                    if (rawJpeg.putIfAbsent(device.deviceId, true) == null) {
                        Log.i(TAG, "UVC native JPEG ${device.deviceId} ${jpeg.size} bytes")
                        errors.remove(device.deviceId)
                    }
                    publishUvc(device.deviceId, jpeg)
                    return@IFrameCallback
                }
                if (loggedSize.putIfAbsent(device.deviceId, true) == null) {
                    frame.rewind()
                    val n = frame.remaining()
                    val peek = ByteArray(minOf(8, n))
                    frame.get(peek)
                    Log.w(
                        TAG,
                        "UVC still non-JPEG ${device.deviceId} bytes=$n head=${peek.joinToString("") { "%02x".format(it) }} (want MJPEG only)",
                    )
                }
            }
            // 6 = PIXEL_FORMAT_MJPEG (patched libUVCCamera): USB JPEG bytes, no YUYV decode
            setCb.invoke(uvc, callback, PIXEL_FORMAT_MJPEG)
            uvc.javaClass.getMethod("startPreview").invoke(uvc)

            mainHandler.postDelayed({
                if (rawJpeg[device.deviceId] == true || opened[device.deviceId] != true) return@postDelayed
                errors[device.deviceId] = "UVC MJPEG not available (got YUYV/other)"
                Log.e(TAG, "UVC MJPEG missing for ${device.deviceId}, not falling back to YUYV")
                onChanged()
            }, 3000)
        } catch (e: Exception) {
            Log.w(TAG, "hook native MJPEG ${device.deviceId} failed", e)
            errors[device.deviceId] = e.message ?: "hook native MJPEG failed"
            onChanged()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun chooseMjpegSize(uvc: Any, preferW: Int, preferH: Int): Pair<Int, Int> {
        val sizes = runCatching {
            val method = uvc.javaClass.methods.firstOrNull { method ->
                method.name == "getSupportedSizeList" && method.parameterTypes.size == 1
            } ?: return@runCatching emptyList<Any>()
            method.invoke(uvc, UVCCamera.FRAME_FORMAT_MJPEG) as? List<*> ?: emptyList<Any>()
        }.getOrDefault(emptyList())

        data class Wh(val w: Int, val h: Int)
        val parsed = sizes.mapNotNull { size ->
            val w = runCatching { size!!.javaClass.getMethod("getWidth").invoke(size) as Int }.getOrNull()
                ?: runCatching { size!!.javaClass.getField("width").getInt(size) }.getOrNull()
            val h = runCatching { size!!.javaClass.getMethod("getHeight").invoke(size) as Int }.getOrNull()
                ?: runCatching { size!!.javaClass.getField("height").getInt(size) }.getOrNull()
            if (w != null && h != null) Wh(w, h) else null
        }
        if (parsed.isNotEmpty()) {
            Log.i(TAG, "UVC MJPEG sizes: ${parsed.joinToString { "${it.w}x${it.h}" }}")
            parsed.firstOrNull { it.w == preferW && it.h == preferH }?.let { return it.w to it.h }
            return parsed.minBy { kotlin.math.abs(it.w * it.h - preferW * preferH) }.let { it.w to it.h }
        }
        // Fallback request; native setPreviewSize will throw if unsupported.
        return preferW to preferH
    }

    private fun jpegFromBuffer(frame: ByteBuffer): ByteArray? {
        frame.rewind()
        val n = frame.remaining().coerceAtMost(2 * 1024 * 1024)
        if (n < 4) return null
        val data = ByteArray(n)
        frame.get(data, 0, n)
        return JpegEncoder.extractJpeg(data, n)
    }
}
