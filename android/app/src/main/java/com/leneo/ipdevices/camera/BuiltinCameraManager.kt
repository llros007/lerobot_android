package com.leneo.ipdevices.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.leneo.ipdevices.model.CameraInfo
import com.leneo.ipdevices.net.FrameHub
import com.leneo.ipdevices.util.JpegEncoder
import java.util.concurrent.ConcurrentHashMap

class BuiltinCameraManager(
    private val context: Context,
    private val hub: FrameHub,
    private val onChanged: () -> Unit = {},
) {
    data class DeviceMeta(
        val cameraId: String,
        val facing: String,
        val label: String,
    )

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sessions = ConcurrentHashMap<String, OpenedCamera>()
    private val errors = ConcurrentHashMap<String, String>()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var jpegQuality = 70

    fun start() {
        if (thread != null) return
        thread = HandlerThread("builtin-camera").also { it.start() }
        handler = Handler(thread!!.looper)
    }

    fun stop() {
        sessions.keys.toList().forEach { close(it) }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    fun listDevices(): List<DeviceMeta> {
        return try {
            cameraManager.cameraIdList.map { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = facingName(chars.get(CameraCharacteristics.LENS_FACING))
                DeviceMeta(id, facing, facingLabel(facing, id))
            }
        } catch (e: Exception) {
            Log.e(TAG, "listDevices", e)
            emptyList()
        }
    }

    fun infos(): List<CameraInfo> {
        return listDevices().map { meta ->
            CameraInfo(
                id = meta.cameraId,
                kind = "builtin",
                label = meta.label,
                facing = meta.facing,
                opened = sessions.containsKey(meta.cameraId),
                error = errors[meta.cameraId],
                path = aliasPath(meta),
            )
        }
    }

    fun backCameraId(): String? = listDevices().firstOrNull { it.facing == "back" }?.cameraId
    fun frontCameraId(): String? = listDevices().firstOrNull { it.facing == "front" }?.cameraId
    fun resolveAlias(alias: String): String? = when (alias) {
        "back" -> backCameraId()
        "front" -> frontCameraId()
        else -> if (listDevices().any { it.cameraId == alias }) alias else null
    }

    @SuppressLint("MissingPermission")
    fun open(cameraId: String, targetWidth: Int = 1280, targetHeight: Int = 720) {
        start()
        if (sessions.containsKey(cameraId)) return
        val h = handler ?: return
        errors.remove(cameraId)
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        startSession(camera, targetWidth, targetHeight)
                    } catch (e: Exception) {
                        errors[cameraId] = e.message ?: "open session failed"
                        camera.close()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    close(cameraId)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    errors[cameraId] = "camera error $error"
                    close(cameraId)
                }
            }, h)
        } catch (e: Exception) {
            errors[cameraId] = e.message ?: "openCamera failed"
            onChanged()
            Log.e(TAG, "open $cameraId", e)
        }
    }

    fun close(cameraId: String) {
        val facing = listDevices().firstOrNull { it.cameraId == cameraId }?.facing
        sessions.remove(cameraId)?.release()
        hub.remove("cam/$cameraId")
        if (facing == "front" || facing == "back") hub.remove("cam/$facing")
        onChanged()
    }

    fun isOpen(cameraId: String) = sessions.containsKey(cameraId)

    private fun startSession(camera: CameraDevice, targetWidth: Int, targetHeight: Int) {
        val chars = cameraManager.getCameraCharacteristics(camera.id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("no stream map")
        // Hardware JPEG from this phone's front ISP is grayscale (sensor itself is
        // Bayer). Take YUV and compress. USB cameras do not use this path.
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
        if (jpegSizes.isEmpty()) {
            errors[camera.id] = "no hardware JPEG; refusing software encode"
            camera.close()
            onChanged()
            return
        }
        val size = chooseSize(jpegSizes, targetWidth, targetHeight)
        val facing = facingName(chars.get(CameraCharacteristics.LENS_FACING))
        Log.i(TAG, "open ${camera.id} $facing ${size.width}x${size.height} JPEG passthrough")
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 3)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!wanted(camera.id, facing)) return@setOnImageAvailableListener
                val jpeg = JpegEncoder.imageToJpeg(image, jpegQuality) ?: return@setOnImageAvailableListener
                hub.publish("cam/${camera.id}", jpeg)
                if (facing == "back" || facing == "front") hub.publish("cam/$facing", jpeg)
            } finally {
                image.close()
            }
        }, handler)

        val surface: Surface = reader.surface
        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    chooseFps(chars)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
                }.build()
                val opened = OpenedCamera(camera, session, reader, req, facing)
                sessions[camera.id] = opened
                opened.startRepeating(handler)
                onChanged()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                errors[camera.id] = "configure failed"
                reader.close()
                camera.close()
                onChanged()
            }
        }, handler)
    }

    private fun wanted(cameraId: String, facing: String): Boolean {
        val ids = mutableListOf("cam/$cameraId")
        if (facing == "front" || facing == "back") ids += "cam/$facing"
        return hub.hasViewer(*ids.toTypedArray())
    }

    private fun aliasPath(meta: DeviceMeta): String {
        val primary = when (meta.facing) {
            "back" -> backCameraId()
            "front" -> frontCameraId()
            else -> null
        }
        return if (primary == meta.cameraId) "/cam/${meta.facing}/mjpeg" else "/cam/${meta.cameraId}/mjpeg"
    }

    private fun facingLabel(facing: String, cameraId: String): String = when (facing) {
        "back" -> "后置摄像头"
        "front" -> "前置摄像头"
        "external" -> "外接摄像头 $cameraId"
        else -> "内置摄像头 $cameraId"
    }

    private fun facingName(facing: Int?): String = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        2 -> "external"
        else -> "unknown"
    }

    private fun formatName(format: Int): String = when (format) {
        ImageFormat.JPEG -> "JPEG"
        ImageFormat.YUV_420_888 -> "YUV_420_888"
        else -> format.toString()
    }

    private fun chooseSize(sizes: Array<Size>, tw: Int, th: Int): Size {
        if (sizes.isEmpty()) return Size(640, 480)
        val exact = sizes.find { it.width == tw && it.height == th }
        if (exact != null) return exact
        return sizes.minBy { kotlin.math.abs(it.width * it.height - tw * th) }
    }

    private fun chooseFps(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        return ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.filter { it.upper >= 24 }.minByOrNull { it.upper - it.lower }
            ?: ranges.maxByOrNull { it.upper }
    }

    private class OpenedCamera(
        val device: CameraDevice,
        val session: CameraCaptureSession,
        val reader: ImageReader,
        val request: CaptureRequest,
        val facing: String,
    ) {
        @Volatile
        private var repeating = false

        fun startRepeating(handler: Handler?) {
            if (repeating) return
            try {
                session.setRepeatingRequest(request, null, handler)
                repeating = true
            } catch (e: Exception) {
                Log.w(TAG, "startRepeating ${device.id}", e)
            }
        }

        fun stopRepeating() {
            if (!repeating) return
            try {
                session.stopRepeating()
            } catch (_: Exception) {
            }
            repeating = false
        }

        fun release() {
            try { session.stopRepeating() } catch (_: Exception) {}
            try { session.close() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            try { device.close() } catch (_: Exception) {}
            repeating = false
        }
    }

    companion object {
        private const val TAG = "BuiltinCam"

        fun availableFacings(context: Context): Set<String> {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            return try {
                manager.cameraIdList.mapNotNull { id ->
                    when (manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "front"
                        CameraCharacteristics.LENS_FACING_BACK -> "back"
                        else -> null
                    }
                }.toSet()
            } catch (_: Exception) {
                emptySet()
            }
        }
    }
}
