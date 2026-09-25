package com.leneo.ipdevices.net

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class FrameHub {
    data class Frame(val jpeg: ByteArray, val seq: Long, val stampMs: Long)

    private val latest = ConcurrentHashMap<String, Frame>()
    private val waiters = ConcurrentHashMap<String, CopyOnWriteArrayList<java.lang.Object>>()
    private val viewers = ConcurrentHashMap<String, AtomicInteger>()
    private val demandListeners = CopyOnWriteArrayList<(String, Boolean) -> Unit>()
    private val seq = AtomicLong(0)

    fun addDemandListener(listener: (String, Boolean) -> Unit) {
        demandListeners.add(listener)
    }

    fun removeDemandListener(listener: (String, Boolean) -> Unit) {
        demandListeners.remove(listener)
    }

    fun addViewer(streamId: String) {
        val count = viewers.getOrPut(streamId) { AtomicInteger(0) }.incrementAndGet()
        if (count == 1) notifyDemand(streamId, true)
    }

    fun removeViewer(streamId: String) {
        val counter = viewers[streamId] ?: return
        val count = counter.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (count == 0) notifyDemand(streamId, false)
    }

    fun hasViewer(vararg streamIds: String): Boolean =
        streamIds.any { (viewers[it]?.get() ?: 0) > 0 }

    fun hasAnyViewer(): Boolean = viewers.values.any { it.get() > 0 }

    private fun notifyDemand(streamId: String, active: Boolean) {
        demandListeners.forEach { listener ->
            runCatching { listener(streamId, active) }
        }
    }

    fun publish(streamId: String, jpeg: ByteArray) {
        val frame = Frame(jpeg, seq.incrementAndGet(), System.currentTimeMillis())
        latest[streamId] = frame
        waiters[streamId]?.forEach { lock ->
            synchronized(lock) { lock.notifyAll() }
        }
    }

    fun latest(streamId: String): Frame? = latest[streamId]

    fun awaitNext(streamId: String, afterSeq: Long, timeoutMs: Long): Frame? {
        val existing = latest[streamId]
        if (existing != null && existing.seq > afterSeq) return existing
        val lock = java.lang.Object()
        waiters.getOrPut(streamId) { CopyOnWriteArrayList() }.add(lock)
        try {
            synchronized(lock) {
                val now = latest[streamId]
                if (now != null && now.seq > afterSeq) return now
                lock.wait(timeoutMs)
            }
            return latest[streamId]
        } catch (_: InterruptedException) {
            return latest[streamId]
        } finally {
            waiters[streamId]?.remove(lock)
        }
    }

    fun remove(streamId: String) {
        latest.remove(streamId)
    }

    fun streamIds(): Set<String> = latest.keys.toSet()
}
