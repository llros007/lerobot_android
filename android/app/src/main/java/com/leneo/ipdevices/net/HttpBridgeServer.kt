package com.leneo.ipdevices.net

import android.util.Log
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HttpBridgeServer(
    private val port: Int,
    private val hub: FrameHub,
    private val indexHtml: () -> String,
    private val infoJson: () -> String,
    private val resolveStream: (String) -> String?,
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        pool.execute {
            try {
                server = ServerSocket(port).also { it.reuseAddress = true }
                Log.i(TAG, "HTTP listening on $port")
                while (running.get()) {
                    val socket = try {
                        server?.accept() ?: break
                    } catch (_: Exception) {
                        break
                    }
                    socket.tcpNoDelay = true
                    pool.execute { handle(socket) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "http server", e)
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val header = readHeaders(input) ?: return
            val requestLine = header.lineSequence().firstOrNull().orEmpty()
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val rawPath = parts.getOrNull(1) ?: "/"
            val path = rawPath.substringBefore("?").trimEnd('/').ifEmpty { "/" }
            val out = socket.getOutputStream()
            if (path.endsWith("/mjpeg") || path == "/video" || path == "/stream") {
                socket.soTimeout = 0
            }
            if (method == "OPTIONS") {
                writeText(out, 204, "text/plain", "")
                return
            }
            when {
                path == "/" || path == "/index.html" -> writeText(out, 200, "text/html; charset=utf-8", indexHtml())
                path == "/info.json" || path == "/info" || path == "/status" ->
                    writeText(out, 200, "application/json; charset=utf-8", infoJson())
                path == "/health" -> writeText(out, 200, "text/plain", "ok")
                path.endsWith("/mjpeg") || path == "/video" || path == "/stream" ->
                    streamMjpeg(out, path)
                path.endsWith("/snapshot") || path.endsWith(".jpg") ->
                    writeSnapshot(out, path)
                else -> writeText(out, 404, "text/plain; charset=utf-8", "not found: $path")
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun streamMjpeg(out: OutputStream, path: String) {
        val streamId = resolveStream(path)
        if (streamId == null) {
            writeText(out, 404, "text/plain; charset=utf-8", "unknown camera path $path")
            return
        }
        hub.addViewer(streamId)
        val first = try {
            waitFrame(streamId, 0L, 12_000)
        } catch (e: Exception) {
            hub.removeViewer(streamId)
            throw e
        }
        if (first == null) {
            hub.removeViewer(streamId)
            writeText(out, 503, "text/plain; charset=utf-8", "camera not producing frames yet: $streamId")
            return
        }
        val header = (
            "HTTP/1.0 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray()
        out.write(header)
        var seq = 0L
        try {
            while (running.get()) {
                val frame = waitFrame(streamId, seq, 3_000) ?: hub.latest(streamId) ?: break
                seq = frame.seq
                val part = (
                    "--frame\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${frame.jpeg.size}\r\n\r\n"
                    ).toByteArray()
                out.write(part)
                out.write(frame.jpeg)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } finally {
            hub.removeViewer(streamId)
        }
    }

    private fun writeSnapshot(out: OutputStream, path: String) {
        val streamId = resolveStream(path.replace("/snapshot", "/mjpeg").replace(".jpg", "/mjpeg"))
            ?: resolveStream(path)
        if (streamId != null) hub.addViewer(streamId)
        val frame = try {
            streamId?.let { waitFrame(it, 0L, 5_000) }
        } finally {
            if (streamId != null) hub.removeViewer(streamId)
        }
        if (frame == null) {
            writeText(out, 503, "text/plain; charset=utf-8", "no frame")
            return
        }
        val header = (
            "HTTP/1.0 200 OK\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${frame.jpeg.size}\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray()
        out.write(header)
        out.write(frame.jpeg)
        out.flush()
    }

    private fun waitFrame(streamId: String, afterSeq: Long, timeoutMs: Long): FrameHub.Frame? {
        return hub.awaitNext(streamId, afterSeq, timeoutMs)
    }

    private fun writeText(out: OutputStream, code: Int, mime: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            204 -> "No Content"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        val header = (
            "HTTP/1.0 $code $reason\r\n" +
                "Content-Type: $mime\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray()
        out.write(header)
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }

    private fun readHeaders(input: BufferedInputStream): String? {
        val buf = ByteArray(4096)
        val out = ArrayList<Byte>(512)
        var last4 = 0
        while (out.size < 8192) {
            val n = input.read(buf, 0, 1)
            if (n <= 0) break
            out.add(buf[0])
            last4 = ((last4 shl 8) or (buf[0].toInt() and 0xFF))
            if (last4 == 0x0D0A0D0A) {
                return String(out.toByteArray(), Charsets.ISO_8859_1)
            }
        }
        return if (out.isEmpty()) null else String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    companion object {
        private const val TAG = "HttpBridge"
        const val DEFAULT_PORT = 8080
    }
}
