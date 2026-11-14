package com.phudy.redline.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Domain blocklist with suffix matching (blocking a.com also blocks x.a.com). */
object Blocklist {
    @Volatile private var domains: Set<String> = emptySet()
    private lateinit var downloaded: File
    val size = MutableStateFlow(0)
    val updatedAt = MutableStateFlow(0L)

    const val SOURCE = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

    fun init(ctx: Context) {
        downloaded = File(ctx.filesDir, "hosts.txt")
        val set = HashSet<String>(200_000)
        ctx.assets.open("blocklist.txt").bufferedReader().useLines { parse(it, set) }
        if (downloaded.exists()) {
            downloaded.bufferedReader().useLines { parse(it, set) }
            updatedAt.value = downloaded.lastModified()
        }
        domains = set
        size.value = set.size
    }

    private fun parse(lines: Sequence<String>, out: MutableSet<String>) {
        for (raw in lines) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            val d = (if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) parts[1] else parts[0])
                .lowercase().trimEnd('.')
            if (d.contains('.') && d != "localhost" && d != "0.0.0.0") out.add(d)
        }
    }

    fun isBlocked(host: String): Boolean {
        var h = host.lowercase().trimEnd('.')
        val set = domains
        while (true) {
            if (h in set) return true
            val i = h.indexOf('.')
            if (i < 0 || h.indexOf('.', i + 1) < 0) return false
            h = h.substring(i + 1)
        }
    }

    /** Downloads the StevenBlack unified hosts list (~150k ad/malware domains). */
    suspend fun update(ctx: Context): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(SOURCE).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            val tmp = File(ctx.cacheDir, "hosts.tmp")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(downloaded)
            init(ctx)
            size.value
        }
    }
}
