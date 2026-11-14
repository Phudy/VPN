package com.phudy.redline.cast

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Small HTTP relay on the phone that the TV streams from.
 *
 * Why: a TV fetching a video link directly has no Referer / Cookie / User-Agent from the
 * website, so most hosts refuse it; Samsung TVs also want DLNA response headers; and
 * downloaded videos (content://) have no URL at all. The TV asks the phone, the phone
 * fetches with the right headers (HTTP Range supported, HLS playlists rewritten so every
 * segment also goes through the relay).
 * Every link carries a random token, so other devices on the Wi-Fi can't use it as an open proxy.
 */
object CastProxy {
    private var server: ServerSocket? = null
    private lateinit var app: Context
    private class Src(val headers: Map<String, String>)
    private val sources = ConcurrentHashMap<String, Src>()

    @Synchronized
    private fun ensure(ctx: Context): Int {
        app = ctx.applicationContext
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0)
        server = s
        thread(isDaemon = true, name = "cast-proxy") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }
        return s.localPort
    }

    /** IPv4 of the Wi-Fi / Ethernet interface (never the VPN tunnel). */
    fun lanAddress(): InetAddress? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("ppp") }
        .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }

    fun lanInterface(): NetworkInterface? = lanAddress()?.let { NetworkInterface.getByInetAddress(it) }

    /** URL the TV should open for [url] (http(s) or content://). */
    fun urlFor(ctx: Context, url: String, headers: Map<String, String>): String {
        val port = ensure(ctx)
        val ip = lanAddress()?.hostAddress ?: error("ไม่ได้เชื่อมต่อ Wi-Fi")
        val token = UUID.randomUUID().toString().replace("-", "")
        sources[token] = Src(headers)
        val ext = url.substringBefore('?').substringAfterLast('.', "mp4").lowercase().take(4)
        val name = if (isHls(url)) "index.m3u8" else "video.${if (ext in setOf("mp4", "mkv", "webm", "mov", "ts", "m4v")) ext else "mp4"}"
        return "http://$ip:$port/$token/$name?u=" + URLEncoder.encode(url, "UTF-8")
    }

    private fun isHls(u: String) = u.substringBefore('?').endsWith(".m3u8", true)

    private fun handle(sock: Socket) {
        sock.soTimeout = 30000
        val input = BufferedInputStream(sock.getInputStream())
        val reqLine = readLine(input) ?: return
        val reqHeaders = HashMap<String, String>()
        while (true) {
            val l = readLine(input) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':'); if (i > 0) reqHeaders[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val parts = reqLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val uri = Uri.parse("http://x" + parts[1])
        val token = uri.pathSegments.firstOrNull() ?: return
        val src = sources[token]
        val out = sock.getOutputStream()
        val target = uri.getQueryParameter("u")
        if (src == null || target == null) { status(out, 403, "Forbidden", emptyMap(), method); return }

        if (target.startsWith("content://") || target.startsWith("file://")) serveLocal(out, method, Uri.parse(target), reqHeaders["range"])
        else serveRemote(out, method, token, target, src.headers, reqHeaders["range"])
    }

    private fun dlnaHeaders(mime: String) = mapOf(
        "Content-Type" to mime,
        "Accept-Ranges" to "bytes",
        "transferMode.dlna.org" to "Streaming",
        "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
        "Connection" to "close",
    )

    private fun serveRemote(out: OutputStream, method: String, token: String, target: String, h: Map<String, String>, range: String?) {
        var url = target
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 30000
            h.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (!h.containsKey("User-Agent")) c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
            if (range != null) c.setRequestProperty("Range", range)
            val code = c.responseCode
            if (code in 300..399 && hops++ < 6) { url = URL(URL(url), c.getHeaderField("Location")).toString(); c.disconnect(); continue }
            break
        }
        val code = c.responseCode
        if (code !in 200..299) { status(out, code, "Upstream", emptyMap(), method); c.disconnect(); return }
        val ct = c.contentType.orEmpty().lowercase()
        if (isHls(url) || ct.contains("mpegurl")) {
            val body = rewritePlaylist(c.inputStream.bufferedReader().readText(), url, token).toByteArray()
            c.disconnect()
            status(out, 200, "OK", dlnaHeaders("application/vnd.apple.mpegurl") + ("Content-Length" to body.size.toString()), method)
            if (method != "HEAD") out.write(body)
            return
        }
        val mime = when {
            ct.startsWith("video/") || ct.startsWith("audio/") -> ct.substringBefore(';')
            url.substringBefore('?').endsWith(".ts", true) -> "video/mp2t"
            url.substringBefore('?').endsWith(".webm", true) -> "video/webm"
            url.substringBefore('?').endsWith(".mkv", true) -> "video/x-matroska"
            else -> "video/mp4"
        }
        val extra = HashMap<String, String>()
        c.getHeaderField("Content-Length")?.let { extra["Content-Length"] = it }
        c.getHeaderField("Content-Range")?.let { extra["Content-Range"] = it }
        status(out, code, if (code == 206) "Partial Content" else "OK", dlnaHeaders(mime) + extra, method)
        if (method != "HEAD") c.inputStream.use { copy(it, out) }
        c.disconnect()
    }

    /** Points every segment / key / sub-playlist URI back at this relay. */
    private fun rewritePlaylist(text: String, base: String, token: String): String {
        val b = URL(base)
        val port = server!!.localPort
        val ip = lanAddress()!!.hostAddress
        fun prox(u: String): String {
            val abs = URL(b, u).toString()
            val name = if (isHls(abs)) "p.m3u8" else "s.ts"
            return "http://$ip:$port/$token/$name?u=" + URLEncoder.encode(abs, "UTF-8")
        }
        return text.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> Regex("URI=\"([^\"]+)\"").replace(line) { "URI=\"${prox(it.groupValues[1])}\"" }
                else -> prox(t)
            }
        }
    }

    private fun serveLocal(out: OutputStream, method: String, uri: Uri, range: String?) {
        val afd = app.contentResolver.openAssetFileDescriptor(uri, "r") ?: run { status(out, 404, "Not Found", emptyMap(), method); return }
        afd.use {
            val total = it.length
            val mime = app.contentResolver.getType(uri) ?: "video/mp4"
            var start = 0L
            var end = total - 1
            val partial = range != null && total > 0
            if (partial) {
                val m = Regex("bytes=(\\d*)-(\\d*)").find(range!!)
                if (m != null) {
                    val (a, z) = m.destructured
                    if (a.isNotEmpty()) start = a.toLong()
                    if (z.isNotEmpty()) end = minOf(z.toLong(), total - 1)
                    if (a.isEmpty() && z.isNotEmpty()) { start = total - z.toLong(); end = total - 1 }
                }
            }
            val len = end - start + 1
            val h = dlnaHeaders(mime) + ("Content-Length" to len.toString()) +
                (if (partial) mapOf("Content-Range" to "bytes $start-$end/$total") else emptyMap())
            status(out, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", h, method)
            if (method == "HEAD") return
            it.createInputStream().use { ins ->
                ins.skip(start)
                val buf = ByteArray(256 * 1024)
                var left = len
                while (left > 0) {
                    val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break
                    out.write(buf, 0, n); left -= n
                }
            }
        }
    }

    private fun status(out: OutputStream, code: Int, msg: String, h: Map<String, String>, method: String) {
        val sb = StringBuilder("HTTP/1.1 $code $msg\r\n")
        h.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        if (!h.containsKey("Content-Length") && code >= 400) sb.append("Content-Length: 0\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray()); out.flush()
    }

    private fun copy(ins: InputStream, out: OutputStream) {
        val buf = ByteArray(256 * 1024)
        while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) }
        out.flush()
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }
}
