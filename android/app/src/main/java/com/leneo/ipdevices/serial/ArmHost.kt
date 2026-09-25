package com.leneo.ipdevices.serial

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.leneo.ipdevices.usb.UsbClassifier
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * LeKiwi-style host: 30 Hz Feetech loop on the phone, next to the USB serial.
 * PC pushes the latest goal and pulls the latest position. Neither side waits
 * for a Wi-Fi round trip inside the servo tick.
 */
class ArmHost(
    private val usbManager: UsbManager,
    private val onChanged: () -> Unit = {},
    private val onPcWrite: () -> Unit = {},
) {
    @Volatile var running: Boolean = false
        private set
    @Volatile var error: String? = null
        private set

    private val stop = AtomicBoolean(false)
    private var thread: Thread? = null
    private var cmdServer: ServerSocket? = null
    private var obsServer: ServerSocket? = null
    private val obsClients = CopyOnWriteArrayList<Socket>()
    private val latestGoal = AtomicReference<IntArray?>(null)
    private val latestVel = AtomicReference(intArrayOf(0, 0))
    private val lastCmdMs = AtomicLong(0)
    private val latestObs = AtomicReference(IntArray(6))
    private val torqueRequested = AtomicBoolean(true)

    private val starting = AtomicBoolean(false)

    fun attach(device: UsbDevice) {
        if (running || !starting.compareAndSet(false, true)) return
        if (!usbManager.hasPermission(device)) {
            starting.set(false)
            return
        }
        start(device)
    }

    fun stop() {
        starting.set(false)
        stop.set(true)
        val worker = thread
        if (worker != null && worker != Thread.currentThread()) worker.join(800)
        thread = null
        obsClients.forEach { runCatching { it.close() } }
        obsClients.clear()
        runCatching { cmdServer?.close() }
        runCatching { obsServer?.close() }
        running = false
    }

    fun latestPositions(): IntArray = latestObs.get().copyOf()

    private fun start(device: UsbDevice) {
        stop.set(false)
        thread = Thread({
            var port: UsbSerialPort? = null
            try {
                val driver = UsbClassifier.serialDriver(device)
                    ?: throw IllegalStateException("no serial driver")
                val connection = usbManager.openDevice(device)
                    ?: throw IllegalStateException("openDevice null")
                port = driver.ports.first()
                port.open(connection)
                runCatching {
                    port.dtr = true
                    port.rts = true
                }
                var setErr: Exception? = null
                for (attempt in 1..3) {
                    try {
                        port.setParameters(1_000_000, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                        setErr = null
                        break
                    } catch (e: Exception) {
                        setErr = e
                        Thread.sleep(150)
                    }
                }
                if (setErr != null) Log.w(TAG, "setParameters failed, keep port open", setErr)
                val bus = FeetechBus(port)
                bus.drain()
                configure(bus)
                val wheels = enableWheels(bus)
                cmdServer = ServerSocket().also {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress(CMD_PORT))
                }
                obsServer = ServerSocket().also {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress(OBS_PORT))
                }
                acceptLoop(cmdServer!!, cmd = true)
                acceptLoop(obsServer!!, cmd = false)
                startPublisher()
                running = true
                error = null
                onChanged()
                Log.i(TAG, "arm host ${CMD_PORT}/${OBS_PORT} @ 30Hz wheels=${wheels.joinToString()}")
                loop(bus, wheels)
            } catch (e: Exception) {
                error = e.message ?: "arm host failed"
                Log.e(TAG, "arm host", e)
                onChanged()
            } finally {
                running = false
                runCatching { port?.close() }
                stop()
            }
        }, "arm-host").also { it.start() }
    }

    private fun configure(bus: FeetechBus) {
        for (id in FeetechBus.IDS) {
            bus.write8(id, 40, 0) // torque off
            bus.write8(id, 33, 0) // position mode
            bus.write8(id, 21, 16) // P
            bus.write8(id, 23, 0) // I
            bus.write8(id, 22, 32) // D
        }
        bus.write16(6, 16, 500)
        bus.write16(6, 28, 250)
        bus.write8(6, 36, 25)
        for (id in FeetechBus.IDS) {
            bus.write8(id, 40, 1)
            bus.write8(id, 55, 1)
        }
    }

    /** Any bus with motor 7/8 gets a velocity wheel. Boards without them stay arm-only. */
    private fun enableWheels(bus: FeetechBus): IntArray {
        val found = ArrayList<Int>()
        for (id in FeetechBus.WHEEL_IDS) {
            if (!bus.ping(id)) continue
            bus.write8(id, 40, 0)
            bus.write8(id, 33, 1) // velocity
            bus.write8(id, 40, 1)
            found.add(id)
        }
        return found.toIntArray()
    }

    private fun acceptLoop(server: ServerSocket, cmd: Boolean) {
        Thread({
            while (!stop.get()) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                socket.tcpNoDelay = true
                if (cmd) {
                    Thread({ readCommands(socket) }, "arm-cmd").start()
                } else {
                    obsClients.add(socket)
                }
            }
        }, if (cmd) "arm-cmd-accept" else "arm-obs-accept").start()
    }

    private fun readCommands(socket: Socket) {
        try {
            socket.getInputStream().bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (stop.get()) break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val json = JSONObject(trimmed)
                    if (json.optInt("torque", 1) == 0 && !json.has("pos") && !json.has("vel")) {
                        latestGoal.set(null)
                        latestVel.set(intArrayOf(0, 0))
                        lastCmdMs.set(0)
                        torqueRequested.set(false)
                        continue
                    }
                    val pos = json.optJSONArray("pos")
                    val vel = json.optJSONArray("vel")
                    if (pos == null && vel == null) continue
                    torqueRequested.set(true)
                    if (pos != null && pos.length() >= 6) {
                        latestGoal.set(IntArray(6) { pos.getInt(it) })
                    }
                    if (vel != null && vel.length() >= 2) {
                        latestVel.set(intArrayOf(vel.getInt(0), vel.getInt(1)))
                    }
                    lastCmdMs.set(System.currentTimeMillis())
                    onPcWrite()
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun loop(bus: FeetechBus, wheels: IntArray) {
        var torqueApplied = true
        while (!stop.get()) {
            val tick = System.nanoTime()
            val wantTorque = torqueRequested.get()
            if (wantTorque != torqueApplied) {
                val ids = FeetechBus.IDS + wheels
                for (id in ids) {
                    runCatching { bus.write8(id, 40, if (wantTorque) 1 else 0) }
                }
                torqueApplied = wantTorque
                Log.i(TAG, if (wantTorque) "torque on" else "torque off")
            }
            val goal = latestGoal.get()
            val age = System.currentTimeMillis() - lastCmdMs.get()
            val fresh = wantTorque && age in 0..WATCHDOG_MS
            if (fresh && goal != null) {
                runCatching { bus.syncWritePositions(FeetechBus.IDS, goal) }
            }
            if (wheels.isNotEmpty()) {
                val commanded = if (fresh) latestVel.get() else intArrayOf(0, 0)
                val vel = IntArray(wheels.size) { i -> if (wheels[i] == 7) commanded[0] else commanded[1] }
                runCatching { bus.syncWriteWords(FeetechBus.GOAL_VELOCITY, wheels, vel) }
            }
            val pos = runCatching { bus.syncReadPositions(FeetechBus.IDS) }.getOrNull()
            val velObs = IntArray(2)
            if (wheels.isNotEmpty()) {
                val raw = runCatching { bus.syncReadWords(FeetechBus.PRESENT_VELOCITY, wheels) }.getOrNull()
                if (raw != null) {
                    wheels.forEachIndexed { i, id ->
                        if (id == 7) velObs[0] = raw[i] else velObs[1] = raw[i]
                    }
                }
            }
            if (pos != null) {
                latestObs.set(pos)
                publish(pos, velObs)
            }
            val spentMs = (System.nanoTime() - tick) / 1_000_000
            val sleep = PERIOD_MS - spentMs
            if (sleep > 0) Thread.sleep(sleep)
        }
    }

    private val latestLine = AtomicReference<ByteArray?>(null)

    private fun publish(pos: IntArray, vel: IntArray) {
        val json = JSONObject()
            .put("pos", JSONArray().apply { pos.forEach { put(it) } })
            .put("ms", System.currentTimeMillis())
        if (vel.isNotEmpty()) {
            json.put("vel", JSONArray().apply { vel.forEach { put(it) } })
        }
        latestLine.set((json.toString() + "\n").toByteArray())
    }

    private fun startPublisher() {
        Thread({
            while (!stop.get()) {
                val line = latestLine.getAndSet(null)
                if (line == null) {
                    Thread.sleep(2)
                    continue
                }
                val dead = ArrayList<Socket>()
                for (socket in obsClients) {
                    try {
                        socket.getOutputStream().write(line)
                        socket.getOutputStream().flush()
                    } catch (_: Exception) {
                        dead.add(socket)
                    }
                }
                dead.forEach {
                    obsClients.remove(it)
                    runCatching { it.close() }
                }
            }
        }, "arm-obs").start()
    }

    companion object {
        private const val TAG = "ArmHost"
        const val CMD_PORT = 9101
        const val OBS_PORT = 9102
        private const val PERIOD_MS = 33L
        private const val WATCHDOG_MS = 500L
    }
}
