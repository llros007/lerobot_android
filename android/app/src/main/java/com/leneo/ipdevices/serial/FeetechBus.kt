package com.leneo.ipdevices.serial

import com.hoho.android.usbserial.driver.UsbSerialPort

/** Minimal STS/SCS sync-read and sync-write. Little-endian, no sign decode. */
class FeetechBus(private val port: UsbSerialPort) {
    fun ping(id: Int): Boolean {
        port.write(packet(id, INST_PING, byteArrayOf()), 20)
        val pkt = readPacket(40) ?: return false
        return (pkt[2].toInt() and 0xFF) == id
    }

    fun syncReadWords(addr: Int, ids: IntArray, timeoutMs: Int = 50): IntArray {
        val tx = packet(
            BROADCAST,
            INST_SYNC_READ,
            byteArrayOf(addr.toByte(), 2) + ids.map { it.toByte() }.toByteArray(),
        )
        port.write(tx, 20)
        val out = IntArray(ids.size) { -1 }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && out.any { it < 0 }) {
            val left = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val rx = readPacket(left.coerceAtMost(25)) ?: break
            val id = rx[2].toInt() and 0xFF
            val idx = ids.indexOf(id)
            if (idx < 0 || rx.size < 8) continue
            val lo = rx[5].toInt() and 0xFF
            val hi = rx[6].toInt() and 0xFF
            out[idx] = lo or (hi shl 8)
        }
        if (out.all { it < 0 } && !loggedMiss) {
            loggedMiss = true
            android.util.Log.w("FeetechBus", "sync read got no motor packets")
        }
        return IntArray(ids.size) { i -> out[i].coerceAtLeast(0) }
    }

    private var loggedMiss = false

    fun syncReadPositions(ids: IntArray, timeoutMs: Int = 80): IntArray =
        syncReadWords(PRESENT_POSITION, ids, timeoutMs)

    fun syncWriteWords(addr: Int, ids: IntArray, values: IntArray) {
        val params = ArrayList<Byte>(2 + ids.size * 3)
        params.add(addr.toByte())
        params.add(2)
        for (i in ids.indices) {
            val v = values[i] and 0xFFFF
            params.add(ids[i].toByte())
            params.add((v and 0xFF).toByte())
            params.add(((v shr 8) and 0xFF).toByte())
        }
        port.write(packet(BROADCAST, INST_SYNC_WRITE, params.toByteArray()), 20)
    }

    fun syncWritePositions(ids: IntArray, values: IntArray) {
        syncWriteWords(GOAL_POSITION, ids, values)
    }

    fun write8(id: Int, addr: Int, value: Int) {
        val tx = packet(id, INST_WRITE, byteArrayOf(addr.toByte(), (value and 0xFF).toByte()))
        port.write(tx, 20)
        readPacket(15)
    }

    fun write16(id: Int, addr: Int, value: Int) {
        val v = value and 0xFFFF
        val tx = packet(
            id,
            INST_WRITE,
            byteArrayOf(addr.toByte(), (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()),
        )
        port.write(tx, 20)
        readPacket(15)
    }

    fun drain() {
        repeat(4) {
            if (!fill(5)) return
            rx.clear()
        }
    }

    private val rx = ArrayList<Byte>(512)
    private val chunk = ByteArray(256)
    private var loggedRaw = false

    /** USB bulk reads must take the whole packet. A 1-byte read drops the rest. */
    private fun fill(timeoutMs: Int): Boolean {
        val n = port.read(chunk, timeoutMs.coerceAtLeast(1))
        if (n <= 0) return false
        for (i in 0 until n) rx.add(chunk[i])
        if (!loggedRaw) {
            loggedRaw = true
            val hex = (0 until n).joinToString(" ") { "%02X".format(chunk[it].toInt() and 0xFF) }
            android.util.Log.i("FeetechBus", "rx $n bytes $hex")
        }
        return true
    }

    private fun readPacket(timeoutMs: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            extract()?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            val left = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            fill(left.coerceAtMost(20))
        }
    }

    private fun extract(): ByteArray? {
        while (rx.size >= 4) {
            var i = 0
            while (i + 1 < rx.size && !(rx[i] == 0xFF.toByte() && rx[i + 1] == 0xFF.toByte())) i++
            if (i > 0) {
                repeat(i) { rx.removeAt(0) }
                continue
            }
            if (rx.size < 4) return null
            val len = rx[3].toInt() and 0xFF
            if (len < 2 || len > 64) {
                rx.removeAt(0)
                continue
            }
            val total = len + 4
            if (rx.size < total) return null
            val pkt = ByteArray(total) { rx[it] }
            var sum = 0
            for (k in 2 until total - 1) sum += pkt[k].toInt() and 0xFF
            val checksum = sum.inv() and 0xFF
            if ((pkt[total - 1].toInt() and 0xFF) != checksum) {
                rx.removeAt(0)
                continue
            }
            repeat(total) { rx.removeAt(0) }
            return pkt
        }
        return null
    }

    private fun packet(id: Int, inst: Int, params: ByteArray): ByteArray {
        val len = params.size + 2
        val raw = ByteArray(len + 4)
        raw[0] = 0xFF.toByte()
        raw[1] = 0xFF.toByte()
        raw[2] = id.toByte()
        raw[3] = len.toByte()
        raw[4] = inst.toByte()
        params.copyInto(raw, 5)
        var sum = 0
        for (i in 2 until raw.size - 1) sum += raw[i].toInt() and 0xFF
        raw[raw.size - 1] = (sum.inv() and 0xFF).toByte()
        return raw
    }

    companion object {
        const val BROADCAST = 0xFE
        const val INST_PING = 1
        const val INST_WRITE = 3
        const val INST_SYNC_READ = 0x82
        const val INST_SYNC_WRITE = 0x83
        const val PRESENT_POSITION = 56
        const val PRESENT_VELOCITY = 58
        const val GOAL_POSITION = 42
        const val GOAL_VELOCITY = 46
        val IDS = intArrayOf(1, 2, 3, 4, 5, 6)
        val WHEEL_IDS = intArrayOf(7, 8)
    }
}
