package com.leneo.ipdevices.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.leneo.ipdevices.model.SerialInfo
import com.leneo.ipdevices.net.SerialTcpServer
import com.leneo.ipdevices.usb.UsbClassifier
import com.leneo.ipdevices.util.SerialPrefs
import java.util.concurrent.ConcurrentHashMap

class UsbSerialManager(
    private val context: Context,
    private val onChanged: () -> Unit = {},
    private val onPcWrite: () -> Unit = {},
) {
    data class OpenedPort(
        val device: UsbDevice,
        val port: UsbSerialPort,
        val io: SerialInputOutputManager,
        val tcp: SerialTcpServer,
        val tcpPort: Int,
        var baud: Int,
    )

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val attached = ConcurrentHashMap<Int, UsbDevice>()
    private val listeners = ConcurrentHashMap<Int, SerialTcpServer>()
    private val opened = ConcurrentHashMap<Int, OpenedPort>()
    private val errors = ConcurrentHashMap<Int, String>()
    private val closeTasks = ConcurrentHashMap<Int, Runnable>()
    private val pendingWrites = ConcurrentHashMap<Int, ArrayDeque<ByteArray>>()
    private val mainHandler = Handler(Looper.getMainLooper())
    val armHost = ArmHost(usbManager, { onChanged() }, onPcWrite)
    private var armHostDeviceId: Int? = null
    var baud: Int = 1_000_000
        private set

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            } ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                attachDevice(device)
                if ((listeners[device.deviceId]?.clientCount() ?: 0) > 0) {
                    openPort(device)
                }
            } else {
                errors[device.deviceId] = "USB 权限被拒绝"
                onChanged()
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val device: UsbDevice = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            } ?: return
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scanAndListen()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> detachDevice(device)
            }
        }
    }

    fun start() {
        val permFilter = IntentFilter(ACTION_USB_PERMISSION)
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(permissionReceiver, permFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, permFilter)
            context.registerReceiver(usbReceiver, usbFilter)
        }
        scanAndListen()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(permissionReceiver) }
        runCatching { context.unregisterReceiver(usbReceiver) }
        closeTasks.values.forEach { mainHandler.removeCallbacks(it) }
        closeTasks.clear()
        armHost.stop()
        armHostDeviceId = null
        opened.values.forEach { closePort(it, stopTcp = false) }
        opened.clear()
        listeners.values.forEach { it.stop() }
        listeners.clear()
        attached.clear()
    }

    fun setBaud(value: Int) {
        baud = value
        opened.values.forEach { item ->
            try {
                item.port.setParameters(value, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                item.baud = value
            } catch (e: Exception) {
                errors[item.device.deviceId] = e.message ?: "set baud failed"
            }
        }
        onChanged()
    }

    fun setTcpPort(deviceId: String, port: Int) {
        val id = deviceId.toIntOrNull() ?: return
        val device = attached[id]
            ?: usbManager.deviceList.values.firstOrNull { it.deviceId == id }
            ?: return
        if (port !in SerialPrefs.MIN_PORT..SerialPrefs.MAX_PORT) return
        val taken = attached.values
            .filter { it.deviceId != id }
            .map { tcpPortFor(it) }
            .toSet()
        if (port in taken) {
            errors[id] = "TCP $port 已被其它串口占用"
            onChanged()
            return
        }
        errors.remove(id)
        val key = UsbClassifier.serialKey(device)
        SerialPrefs.setPort(context, key, port)
        tcpPorts[key] = port
        val reopen = opened.containsKey(id) || (listeners[id]?.clientCount() ?: 0) > 0
        listeners.remove(id)?.stop()
        opened.remove(id)?.let { closePort(it, stopTcp = false) }
        attached[id] = device
        ensureListen(device)
        if (reopen) openPort(device)
        onChanged()
    }

    fun scanAndOpen() = scanAndListen()

    fun scanAndListen() {
        usbManager.deviceList.values.forEach { device ->
            UsbClassifier.serialDriver(device) ?: return@forEach
            if (usbManager.hasPermission(device)) {
                attachDevice(device)
            } else {
                attached[device.deviceId] = device
                requestPermission(device)
            }
            Log.i(TAG, "found serial ${UsbClassifier.displayName(device)}")
        }
        onChanged()
    }

    fun infos(): List<SerialInfo> {
        val ids = (attached.keys + usbManager.deviceList.values.filter {
            UsbClassifier.serialDriver(it) != null
        }.map { it.deviceId }).toSet()
        return ids.mapNotNull { id ->
            val device = attached[id] ?: usbManager.deviceList.values.firstOrNull { it.deviceId == id }
                ?: return@mapNotNull null
            UsbClassifier.serialDriver(device) ?: return@mapNotNull null
            val open = opened[id]
            val hosted = armHostDeviceId == id && armHost.running
            SerialInfo(
                id = id.toString(),
                label = UsbClassifier.displayName(device),
                vid = device.vendorId,
                pid = device.productId,
                serial = UsbClassifier.serialNumber(device),
                tcpPort = if (hosted) ArmHost.CMD_PORT else open?.tcpPort ?: tcpPortFor(device),
                baud = open?.baud ?: baud,
                opened = hosted || open != null,
                error = errors[id] ?: if (hosted) "host 9101/9102" else null,
            )
        }
    }

    private fun attachDevice(device: UsbDevice) {
        if (UsbClassifier.serialDriver(device) == null) return
        attached[device.deviceId] = device
        if (!usbManager.hasPermission(device)) {
            requestPermission(device)
            onChanged()
            return
        }
        // First servo bus runs the 30 Hz host. Extra boards stay raw TCP.
        if (armHostDeviceId == null || armHostDeviceId == device.deviceId) {
            armHostDeviceId = device.deviceId
            armHost.attach(device)
        } else {
            ensureListen(device)
        }
        onChanged()
    }

    private fun detachDevice(device: UsbDevice) {
        closeTasks.remove(device.deviceId)?.let { mainHandler.removeCallbacks(it) }
        if (armHostDeviceId == device.deviceId) {
            armHost.stop()
            armHostDeviceId = null
        }
        opened.remove(device.deviceId)?.let { closePort(it, stopTcp = false) }
        listeners.remove(device.deviceId)?.stop()
        attached.remove(device.deviceId)
        onChanged()
    }

    private fun ensureListen(device: UsbDevice) {
        if (listeners.containsKey(device.deviceId) || opened.containsKey(device.deviceId)) return
        val tcpPort = tcpPortFor(device)
        val tcp = SerialTcpServer(
            port = tcpPort,
            onClientBytes = { bytes -> writeOrReopen(device, bytes) },
            onClientsChanged = { count ->
                mainHandler.post {
                    if (count > 0) {
                        closeTasks.remove(device.deviceId)?.let { mainHandler.removeCallbacks(it) }
                        openPort(device)
                    } else {
                        scheduleClosePort(device.deviceId)
                    }
                }
            },
        )
        tcp.start()
        listeners[device.deviceId] = tcp
        Log.i(TAG, "listen serial ${device.deviceId} tcp=$tcpPort (port closed until client)")
    }

    private fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) }
        val pi = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    private fun enqueueWrite(deviceId: Int, bytes: ByteArray) {
        val q = pendingWrites.getOrPut(deviceId) { ArrayDeque() }
        synchronized(q) {
            if (q.size >= 64) q.removeFirst()
            q.addLast(bytes)
        }
    }

    private fun flushPending(device: UsbDevice) {
        val port = opened[device.deviceId]?.port ?: return
        val q = pendingWrites[device.deviceId] ?: return
        while (true) {
            val chunk = synchronized(q) { if (q.isEmpty()) null else q.removeFirst() } ?: break
            try {
                port.write(chunk, WRITE_TIMEOUT_MS)
                errors.remove(device.deviceId)
            } catch (e: Exception) {
                Log.w(TAG, "flush write ${device.deviceId}", e)
                enqueueWrite(device.deviceId, chunk)
                break
            }
        }
    }

    private fun writeOrReopen(device: UsbDevice, bytes: ByteArray) {
        if (bytes.isNotEmpty()) onPcWrite()
        val port = opened[device.deviceId]?.port
        if (port == null) {
            enqueueWrite(device.deviceId, bytes)
            mainHandler.post {
                openPort(device)
                flushPending(device)
            }
            return
        }
        try {
            port.write(bytes, WRITE_TIMEOUT_MS)
            errors.remove(device.deviceId)
        } catch (e: Exception) {
            errors[device.deviceId] = e.message ?: "serial write failed"
            Log.w(TAG, "write failed ${device.deviceId}, reopen", e)
            enqueueWrite(device.deviceId, bytes)
            mainHandler.post {
                opened.remove(device.deviceId)?.let { closePort(it, stopTcp = false) }
                openPort(device)
                flushPending(device)
            }
        }
    }

    private fun openPort(device: UsbDevice) {
        if (opened.containsKey(device.deviceId)) return
        if (!usbManager.hasPermission(device)) {
            requestPermission(device)
            return
        }
        val driver = UsbClassifier.serialDriver(device) ?: return
        val tcp = listeners[device.deviceId] ?: return
        try {
            val connection = usbManager.openDevice(device)
                ?: throw IllegalStateException("openDevice returned null")
            val port = driver.ports.firstOrNull()
                ?: throw IllegalStateException("no serial port")
            port.open(connection)
            runCatching {
                port.dtr = true
                port.rts = true
            }
            var setErr: Exception? = null
            for (attempt in 1..3) {
                try {
                    port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    setErr = null
                    break
                } catch (e: Exception) {
                    setErr = e
                    Log.w(TAG, "setParameters ${device.deviceId} try $attempt", e)
                    Thread.sleep(150)
                }
            }
            if (setErr != null) {
                Log.w(TAG, "setParameters ${device.deviceId} failed, keep port open", setErr)
            }
            val io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    tcp.broadcast(data)
                }

                override fun onRunError(e: Exception) {
                    errors[device.deviceId] = e.message ?: "serial io error"
                    onChanged()
                    Log.e(TAG, "io error", e)
                }
            })
            io.start()
            opened[device.deviceId] = OpenedPort(device, port, io, tcp, tcpPortFor(device), baud)
            errors.remove(device.deviceId)
            flushPending(device)
            onChanged()
            Log.i(TAG, "opened serial ${device.deviceId} tcp=${tcpPortFor(device)} baud=$baud")
        } catch (e: Exception) {
            errors[device.deviceId] = e.message ?: "open serial failed"
            onChanged()
            Log.e(TAG, "open serial ${device.deviceId}", e)
        }
    }

    private fun scheduleClosePort(deviceId: Int) {
        closeTasks.remove(deviceId)?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            if ((listeners[deviceId]?.clientCount() ?: 0) > 0) return@Runnable
            opened.remove(deviceId)?.let { closePort(it, stopTcp = false) }
            onChanged()
            Log.i(TAG, "close serial port $deviceId (no TCP client)")
        }
        closeTasks[deviceId] = task
        mainHandler.postDelayed(task, IDLE_CLOSE_MS)
    }

    private fun closePort(item: OpenedPort, stopTcp: Boolean) {
        runCatching { item.io.stop() }
        if (stopTcp) runCatching { item.tcp.stop() }
        runCatching { item.port.close() }
    }

    private val tcpPorts = ConcurrentHashMap<String, Int>()

    private fun tcpPortFor(device: UsbDevice): Int {
        val key = UsbClassifier.serialKey(device)
        tcpPorts[key]?.let { return it }
        SerialPrefs.getPort(context, key)?.let { saved ->
            tcpPorts[key] = saved
            return saved
        }
        val taken = attached.values.mapNotNull { other ->
            val otherKey = UsbClassifier.serialKey(other)
            if (otherKey == key) null else tcpPorts[otherKey] ?: SerialPrefs.getPort(context, otherKey)
        }.toSet()
        val preferred = SerialPrefs.PORTS.firstOrNull { it !in taken } ?: SerialPrefs.MIN_PORT
        val port = if (preferred in taken) {
            SerialPrefs.PORTS.firstOrNull { it !in taken } ?: preferred
        } else {
            preferred
        }
        SerialPrefs.setPort(context, key, port)
        tcpPorts[key] = port
        return port
    }

    companion object {
        private const val TAG = "UsbSerial"
        const val ACTION_USB_PERMISSION = "com.leneo.ipdevices.USB_SERIAL_PERMISSION"
        const val BASE_TCP_PORT = 9000
        private const val WRITE_TIMEOUT_MS = 200
        private const val IDLE_CLOSE_MS = 2500L
    }
}
