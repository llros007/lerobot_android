package com.leneo.ipdevices.net

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SerialTcpServer(
    private val port: Int,
    private val onClientBytes: (ByteArray) -> Unit,
    private val onClientsChanged: (Int) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()
    private val acceptPool = Executors.newSingleThreadExecutor()
    private val ioPool = Executors.newCachedThreadPool()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptPool.execute {
            try {
                server = ServerSocket(port).also { it.reuseAddress = true }
                while (running.get()) {
                    val socket = try {
                        server?.accept() ?: break
                    } catch (_: Exception) {
                        break
                    }
                    socket.tcpNoDelay = true
                    clients.add(socket)
                    onClientsChanged(clients.size)
                    ioPool.execute { readLoop(socket) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "serial tcp $port", e)
            }
        }
    }

    fun stop() {
        running.set(false)
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { server?.close() }
        server = null
    }

    fun broadcast(data: ByteArray) {
        val dead = mutableListOf<Socket>()
        clients.forEach { socket ->
            try {
                val out = socket.getOutputStream()
                out.write(data)
                out.flush()
            } catch (_: Exception) {
                dead += socket
            }
        }
        dead.forEach {
            clients.remove(it)
            runCatching { it.close() }
        }
    }

    fun clientCount(): Int = clients.size

    private fun readLoop(socket: Socket) {
        val buf = ByteArray(4096)
        try {
            val input = socket.getInputStream()
            while (running.get() && !socket.isClosed) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) onClientBytes(buf.copyOf(n))
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(socket)
            runCatching { socket.close() }
            onClientsChanged(clients.size)
        }
    }

    companion object {
        private const val TAG = "SerialTcp"
    }
}
