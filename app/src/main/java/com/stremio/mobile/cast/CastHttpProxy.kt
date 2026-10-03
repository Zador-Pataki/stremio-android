package com.stremio.mobile.cast

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class CastHttpProxy : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var source: String? = null
    @Volatile private var token: String? = null

    @Synchronized fun start(upstream: String, lanIp: String): String {
        stop()
        val t = UUID.randomUUID().toString().replace("-", "")
        val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("0.0.0.0", 0), 32) }
        source = upstream; token = t; server = s; running.set(true)
        pool.execute {
            while (running.get()) {
                val c = try { s.accept() } catch (_: Exception) { break }
                sockets += c
                pool.execute {
                    try { serve(c) } finally { sockets -= c; runCatching { c.close() } }
                }
            }
        }
        return "http://$lanIp:${s.localPort}/$t"
    }

    @Synchronized fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        source = null
        token = null
    }

    private fun serve(client: Socket) {
        client.soTimeout = 30_000
        val reader = BufferedReader(InputStreamReader(BufferedInputStream(client.getInputStream()), StandardCharsets.ISO_8859_1))
        val out = BufferedOutputStream(client.getOutputStream())
        val parts = (reader.readLine() ?: return).split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1].substringBefore('?')
        val headers = mutableMapOf<String,String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val p = line.indexOf(':')
            if (p > 0) headers[line.substring(0,p).trim().lowercase()] = line.substring(p+1).trim()
        }
        if (method == "OPTIONS") {
            out.write("HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\nAccess-Control-Allow-Headers: Range, Content-Type\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush(); return
        }
        if (method != "GET" && method != "HEAD") return
        if (target != "/${token ?: return}") return
        val upstream = source ?: return
        val conn = (URL(upstream).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 0
            headers["range"]?.let { setRequestProperty("Range", it) }
            headers["user-agent"]?.let { setRequestProperty("User-Agent", it) }
        }
        try {
            val code = conn.responseCode
            out.write("HTTP/1.1 $code ${conn.responseMessage ?: ""}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            for (h in listOf("Content-Type","Content-Length","Content-Range","Accept-Ranges","Cache-Control","ETag","Last-Modified")) {
                conn.getHeaderField(h)?.let { out.write("$h: $it\r\n".toByteArray(StandardCharsets.ISO_8859_1)) }
            }
            out.write("Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
            if (method == "HEAD") return
            val body = if (code >= 400) conn.errorStream else conn.inputStream
            body?.use { input ->
                val buf = ByteArray(64 * 1024)
                while (running.get()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            }
        } finally { conn.disconnect() }
    }

    override fun close() {
        stop()
        pool.shutdownNow()
    }
}
