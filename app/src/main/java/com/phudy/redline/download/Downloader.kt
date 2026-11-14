package com.phudy.redline.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class DlStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class DlTask(
    val id: String,
    val url: String,
    val title: String,
    val headers: Map<String, String>,
    val status: DlStatus = DlStatus.QUEUED,
    val progress: Float = 0f,       // 0..1, or -1 when the size is unknown
    val bytes: Long = 0,
    val fileUri: String? = null,
    val fileName: String? = null,
    val error: String? = null,
)

/**
 * Saves videos to the gallery (Movies/REDLINE).
 *  - direct files (mp4/webm/mkv…): ranged HTTP with automatic resume after drops
 *  - HLS (.m3u8): picks the best quality, downloads segments in parallel with retries,
 *    handles standard AES-128 HLS encryption and fMP4 init segments, joins them into one file
 * Sends the page's Referer / Cookie / User-Agent so hot-link-protected hosts accept the request.
 * DRM-protected streams (Widevine / PlayReady / FairPlay) cannot be saved.
 */
object Downloader {
    private val _tasks = MutableStateFlow<List<DlTask>>(emptyList())
    val tasks: StateFlow<List<DlTask>> = _tasks
    private val jobs = HashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    val activeCount get() = _tasks.value.count { it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED }

    fun enqueue(ctx: Context, url: String, title: String?, headers: Map<String, String> = emptyMap()): String {
        val id = UUID.randomUUID().toString()
        val t = DlTask(id, url, cleanTitle(title, url), headers)
        _tasks.value = listOf(t) + _tasks.value
        DownloadService.start(ctx)
        jobs[id] = scope.launch { run(ctx.applicationContext, t) }
        return id
    }

    fun retry(ctx: Context, id: String) {
        val t = _tasks.value.firstOrNull { it.id == id } ?: return
        update(id) { it.copy(status = DlStatus.QUEUED, error = null, progress = 0f, bytes = 0) }
        DownloadService.start(ctx)
        jobs[id] = scope.launch { run(ctx.applicationContext, t) }
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        update(id) { it.copy(status = DlStatus.CANCELLED) }
    }

    fun remove(ctx: Context, id: String, deleteFile: Boolean) {
        cancel(id)
        val t = _tasks.value.firstOrNull { it.id == id }
        if (deleteFile) t?.fileUri?.let { runCatching { ctx.contentResolver.delete(Uri.parse(it), null, null) } }
        _tasks.value = _tasks.value.filterNot { it.id == id }
    }

    private fun update(id: String, f: (DlTask) -> DlTask) {
        synchronized(this) { _tasks.value = _tasks.value.map { if (it.id == id) f(it) else it } }
    }

    private suspend fun run(ctx: Context, t: DlTask) {
        update(t.id) { it.copy(status = DlStatus.RUNNING) }
        try {
            val isHls = t.url.substringBefore('?').endsWith(".m3u8", true) || sniffHls(t)
            val ext = if (isHls) "ts" else guessExt(t.url)
            val (uri, out) = createOutput(ctx, t.title, ext)
            try {
                out.use { if (isHls) downloadHls(t, it) else downloadDirect(t, it) }
                finishOutput(ctx, uri)
                update(t.id) { it.copy(status = DlStatus.DONE, progress = 1f, fileUri = uri.toString(), fileName = "${t.title}.$ext") }
            } catch (e: Throwable) {
                runCatching { ctx.contentResolver.delete(uri, null, null) }
                throw e
            }
        } catch (e: CancellationException) {
            update(t.id) { it.copy(status = DlStatus.CANCELLED) }
        } catch (e: Throwable) {
            update(t.id) { it.copy(status = DlStatus.FAILED, error = e.message ?: e.javaClass.simpleName) }
        } finally {
            jobs.remove(t.id)
            if (activeCount == 0) DownloadService.stop(ctx)
        }
    }

    // ---------- HTTP ----------

    private fun open(url: String, headers: Map<String, String>, range: Long = 0): HttpURLConnection {
        var current = url
        repeat(8) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", headers["User-Agent"] ?: UA)
            c.setRequestProperty("Accept", "*/*")
            headers.forEach { (k, v) -> if (k != "User-Agent") c.setRequestProperty(k, v) }
            if (range > 0) c.setRequestProperty("Range", "bytes=$range-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect ไม่มีปลายทาง")
                current = URL(URL(current), loc).toString(); c.disconnect(); return@repeat
            }
            if (code !in 200..299) { c.disconnect(); throw IOException("เซิร์ฟเวอร์ตอบ HTTP $code") }
            return c
        }
        throw IOException("redirect มากเกินไป")
    }

    private suspend fun <T> retrying(times: Int = 5, block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(times) { attempt ->
            try { return block() } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                last = e; delay(800L * (attempt + 1))
            }
        }
        throw last ?: IOException("ล้มเหลว")
    }

    private fun fetchBytes(url: String, h: Map<String, String>): ByteArray =
        open(url, h).let { c -> try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() } }

    private fun sniffHls(t: DlTask): Boolean = runCatching {
        val c = open(t.url, t.headers)
        try {
            val ct = c.contentType.orEmpty().lowercase()
            ct.contains("mpegurl") || c.inputStream.use { ins -> val b = ByteArray(16); var n = 0; while (n < 16) { val r = ins.read(b, n, 16 - n); if (r < 0) break; n += r }; String(b, 0, n).startsWith("#EXTM3U") }
        } finally { c.disconnect() }
    }.getOrDefault(false)

    /** Direct download with resume: if the connection drops, continue from the last byte. */
    private suspend fun downloadDirect(t: DlTask, out: OutputStream) {
        var written = 0L
        var total = -1L
        var failures = 0
        val buf = ByteArray(256 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val c = open(t.url, t.headers, written)
                if (written > 0 && c.responseCode != 206) { c.disconnect(); throw IOException("เซิร์ฟเวอร์ไม่รองรับการดาวน์โหลดต่อ") }
                if (total < 0) total = c.contentLengthLong.let { if (it > 0) it + written else -1 }
                c.inputStream.use { ins ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); written += n
                        val w = written
                        update(t.id) { it.copy(bytes = w, progress = if (total > 0) w.toFloat() / total else -1f) }
                    }
                }
                c.disconnect()
                if (total < 0 || written >= total) return
            } catch (e: CancellationException) { throw e } catch (e: IOException) {
                if (++failures > 8 || e.message?.contains("ไม่รองรับ") == true) throw e
                delay(1000L * failures)
            }
        }
    }

    // ---------- HLS ----------

    private class Seg(val url: String, val keyUrl: String?, val iv: ByteArray?, val seq: Long)

    private suspend fun downloadHls(t: DlTask, out: OutputStream) {
        var playlistUrl = t.url
        var text = String(retrying { fetchBytes(playlistUrl, t.headers) })
        if (!text.trimStart().startsWith("#EXTM3U")) throw IOException("ไม่ใช่ไฟล์ HLS")

        // Master playlist → pick the highest bandwidth variant
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines()
            var best: Pair<Long, String>? = null
            for (i in lines.indices) {
                val l = lines[i]
                if (!l.startsWith("#EXT-X-STREAM-INF")) continue
                val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLong() ?: 0
                val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                if (best == null || bw > best.first) best = bw to next.trim()
            }
            playlistUrl = URL(URL(playlistUrl), best?.second ?: throw IOException("ไม่พบคุณภาพวิดีโอ")).toString()
            text = String(retrying { fetchBytes(playlistUrl, t.headers) })
        }
        if (text.contains("KEYFORMAT=\"com.apple.streamingkeydelivery\"") || text.contains("METHOD=SAMPLE-AES") ||
            text.contains("urn:uuid:edef8ba9")) {
            throw IOException("คลิปนี้ป้องกันด้วย DRM ไม่สามารถบันทึกได้")
        }

        val base = URL(playlistUrl)
        val segs = ArrayList<Seg>()
        var keyUrl: String? = null
        var iv: ByteArray? = null
        var initUrl: String? = null
        var seq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(text)?.groupValues?.get(1)?.toLong() ?: 0
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-KEY") -> {
                    val method = Regex("METHOD=([A-Z0-9-]+)").find(l)?.groupValues?.get(1)
                    if (method == "NONE") { keyUrl = null; iv = null } else {
                        keyUrl = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { URL(base, it).toString() }
                        iv = Regex("IV=0[xX]([0-9a-fA-F]+)").find(l)?.groupValues?.get(1)?.let(::hex)
                    }
                }
                l.startsWith("#EXT-X-MAP") ->
                    initUrl = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { URL(base, it).toString() }
                l.isNotEmpty() && !l.startsWith("#") -> { segs += Seg(URL(base, l).toString(), keyUrl, iv, seq); seq++ }
            }
        }
        if (segs.isEmpty()) throw IOException("เพลย์ลิสต์ว่าง")

        val keys = HashMap<String, ByteArray>()
        initUrl?.let { out.write(retrying { fetchBytes(it, t.headers) }) }

        var done = 0
        var bytes = 0L
        val window = 6
        for (chunk in segs.chunked(window)) {
            val data = coroutineScope {
                chunk.map { s ->
                    async {
                        var d = retrying { fetchBytes(s.url, t.headers) }
                        if (s.keyUrl != null) {
                            val key = synchronized(keys) { keys[s.keyUrl] } ?: retrying { fetchBytes(s.keyUrl, t.headers) }
                                .also { k -> synchronized(keys) { keys[s.keyUrl] = k } }
                            d = decrypt(d, key, s.iv ?: seqIv(s.seq))
                        }
                        d
                    }
                }.awaitAll()
            }
            for (d in data) { out.write(d); bytes += d.size }
            done += chunk.size
            val p = done.toFloat() / segs.size; val b = bytes
            update(t.id) { it.copy(progress = p, bytes = b) }
        }
    }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(16), "AES"), IvParameterSpec(iv.copyOf(16)))
        return c.doFinal(data)
    }

    private fun seqIv(seq: Long) = ByteArray(16).also { for (i in 0 until 8) it[15 - i] = (seq shr (8 * i)).toByte() }
    private fun hex(s: String): ByteArray {
        val h = s.padStart(32, '0').takeLast(32)
        return ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    // ---------- storage ----------

    private fun createOutput(ctx: Context, title: String, ext: String): Pair<Uri, OutputStream> {
        val name = "$title.$ext"
        val mime = when (ext) { "ts" -> "video/mp2t"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"; else -> "video/mp4" }
        return if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/REDLINE")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                ?: throw IOException("สร้างไฟล์ไม่ได้")
            uri to (ctx.contentResolver.openOutputStream(uri) ?: throw IOException("เขียนไฟล์ไม่ได้")).buffered(512 * 1024)
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "REDLINE").apply { mkdirs() }
            val f = File(dir, name)
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            uri to f.outputStream().buffered(512 * 1024)
        }
    }

    private fun finishOutput(ctx: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "REDLINE")
            MediaScannerConnection.scanFile(ctx, arrayOf(dir.absolutePath), null, null)
        }
    }

    private fun guessExt(url: String): String {
        val e = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
        return if (e in setOf("mp4", "webm", "mkv", "mov", "m4v", "ts", "3gp")) e else "mp4"
    }

    private fun cleanTitle(title: String?, url: String): String {
        val raw = title?.takeIf { it.isNotBlank() } ?: url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        val safe = raw.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(60).ifBlank { "video" }
        return "$safe-${System.currentTimeMillis() % 100000}"
    }

    // ---------- sharing ----------

    /** Shares the saved video file itself (to LINE, Messenger, Telegram, Drive…). */
    fun shareFile(ctx: Context, t: DlTask) {
        val uri = Uri.parse(t.fileUri ?: return)
        val i = Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "แชร์วิดีโอ").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Shares the link to a video. */
    fun shareLink(ctx: Context, url: String, title: String?) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title ?: "วิดีโอ").putExtra(Intent.EXTRA_TEXT, url)
        ctx.startActivity(Intent.createChooser(i, "แชร์ลิงก์").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Request headers a page would send, so protected video hosts accept our request. */
    fun headersFor(pageUrl: String?, userAgent: String?, videoUrl: String): Map<String, String> {
        val h = HashMap<String, String>()
        if (!pageUrl.isNullOrBlank()) {
            h["Referer"] = pageUrl
            runCatching { val u = URL(pageUrl); h["Origin"] = "${u.protocol}://${u.host}" }
        }
        if (!userAgent.isNullOrBlank()) h["User-Agent"] = userAgent
        runCatching { android.webkit.CookieManager.getInstance().getCookie(videoUrl) }.getOrNull()?.let { h["Cookie"] = it }
        return h
    }

    fun toBundle(h: Map<String, String>) = android.os.Bundle().apply { h.forEach { (k, v) -> putString(k, v) } }
    fun fromBundle(b: android.os.Bundle?): Map<String, String> =
        b?.keySet()?.associateWith { b.getString(it).orEmpty() } ?: emptyMap()

    fun openFile(ctx: Context, t: DlTask) {
        val uri = Uri.parse(t.fileUri ?: return)
        ctx.startActivity(
            Intent(ctx, com.phudy.redline.PlayerActivity::class.java).setData(uri)
                .putExtra(com.phudy.redline.PlayerActivity.EXTRA_TITLE, t.fileName)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
