package com.phudy.redline.explore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Clip(
    val id: String,
    val source: String,
    val title: String,
    val thumb: String,
    val durationSec: Int,
    val views: Long,
    val rating: Double,   // 0..100
    val pageUrl: String,
    val embedUrl: String,
)

enum class Sort(val label: String) { POPULAR("ยอดนิยม"), NEWEST("ใหม่ล่าสุด"), RATING("เรตติ้งสูง"), LONGEST("ยาวสุด") }

/** One search source. Each uses the site's own public webmaster / developer API. */
interface Provider {
    val id: String
    val name: String
    suspend fun search(query: String, page: Int, sort: Sort): List<Clip>
}

private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

private fun getJson(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 12000; c.readTimeout = 20000
    c.setRequestProperty("User-Agent", UA)
    c.setRequestProperty("Accept", "application/json")
    try {
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().readText()
    } finally { c.disconnect() }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private fun parseDuration(s: String?): Int {
    if (s.isNullOrBlank()) return 0
    return s.split(':').mapNotNull { it.trim().toIntOrNull() }.fold(0) { acc, v -> acc * 60 + v }
}

private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")

/** https://www.eporner.com/api/v2/ — documented public API. */
object Eporner : Provider {
    override val id = "eporner"
    override val name = "Eporner"
    override suspend fun search(query: String, page: Int, sort: Sort): List<Clip> = withContext(Dispatchers.IO) {
        val order = when (sort) {
            Sort.POPULAR -> "most-popular"; Sort.NEWEST -> "latest"; Sort.RATING -> "top-rated"; Sort.LONGEST -> "longest"
        }
        val q = query.ifBlank { "all" }
        val json = JSONObject(getJson(
            "https://www.eporner.com/api/v2/video/search/?query=${enc(q)}&per_page=30&page=$page" +
                "&thumbsize=big&order=$order&gay=0&lq=1&format=json"
        ))
        val arr = json.optJSONArray("videos") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { v ->
            Clip(
                id = "ep-" + v.optString("id"), source = name,
                title = unescape(v.optString("title")),
                thumb = v.optJSONObject("default_thumb")?.optString("src").orEmpty(),
                durationSec = v.optInt("length_sec"),
                views = v.optLong("views"),
                rating = v.optDouble("rate", 0.0) * 20, // 0..5 → 0..100
                pageUrl = v.optString("url"),
                embedUrl = v.optString("embed").ifBlank { "https://www.eporner.com/embed/${v.optString("id")}/" },
            )
        }
    }
}

/** RedTube public API (api.redtube.com). */
object RedTube : Provider {
    override val id = "redtube"
    override val name = "RedTube"
    override suspend fun search(query: String, page: Int, sort: Sort): List<Clip> = withContext(Dispatchers.IO) {
        val order = when (sort) { Sort.NEWEST -> "newest"; Sort.RATING -> "rating"; else -> "mostviewed" }
        val url = "https://api.redtube.com/?data=redtube.Videos.searchVideos&output=json&thumbsize=big" +
            "&ordering=$order&page=$page" + (if (query.isNotBlank()) "&search=${enc(query)}" else "")
        val arr = JSONObject(getJson(url)).optJSONArray("videos") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optJSONObject("video") }.map { v ->
            val vid = v.optString("video_id")
            Clip(
                id = "rt-$vid", source = name,
                title = unescape(v.optString("title")),
                thumb = v.optString("default_thumb").ifBlank { v.optString("thumb") },
                durationSec = parseDuration(v.optString("duration")),
                views = v.optLong("views"),
                rating = v.optString("rating").toDoubleOrNull()?.let { if (it <= 5) it * 20 else it } ?: 0.0,
                pageUrl = v.optString("url"),
                embedUrl = v.optString("embed_url").ifBlank { "https://embed.redtube.com/?id=$vid" },
            )
        }
    }
}

/** Pornhub webmaster API (pornhub.com/webmasters). Blocked in Thailand — works with Secure VPN on. */
object Pornhub : Provider {
    override val id = "pornhub"
    override val name = "Pornhub"
    override suspend fun search(query: String, page: Int, sort: Sort): List<Clip> = withContext(Dispatchers.IO) {
        val order = when (sort) { Sort.NEWEST -> "newest"; Sort.RATING -> "rating"; Sort.POPULAR -> "mostviewed"; Sort.LONGEST -> "mostviewed" }
        val url = "https://www.pornhub.com/webmasters/search?thumbsize=large_hd&ordering=$order&page=$page" +
            (if (query.isNotBlank()) "&search=${enc(query)}" else "")
        val arr = JSONObject(getJson(url)).optJSONArray("videos") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { v ->
            val vid = v.optString("video_id")
            Clip(
                id = "ph-$vid", source = name,
                title = unescape(v.optString("title")),
                thumb = v.optString("default_thumb").ifBlank { v.optString("thumb") },
                durationSec = parseDuration(v.optString("duration")),
                views = v.optLong("views"),
                rating = v.optDouble("rating", 0.0),
                pageUrl = v.optString("url"),
                embedUrl = "https://www.pornhub.com/embed/$vid",
            )
        }
    }
}

val PROVIDERS: List<Provider> = listOf(Eporner, RedTube, Pornhub)

object Explore {
    data class Result(val clips: List<Clip>, val errors: Map<String, String>)

    /** Queries every enabled source in parallel and interleaves the results. */
    suspend fun search(query: String, page: Int, sort: Sort, enabled: Set<String>): Result = coroutineScope {
        val errors = HashMap<String, String>()
        val lists = PROVIDERS.filter { it.id in enabled }.map { p ->
            async {
                runCatching { p.search(query, page, sort) }
                    .onFailure { synchronized(errors) { errors[p.name] = it.message ?: "ผิดพลาด" } }
                    .getOrDefault(emptyList())
            }
        }.awaitAll()
        val merged = ArrayList<Clip>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) if (i < l.size) merged += l[i]
        val filtered = merged.filter { SafetyFilter.allowed(it.title) }.distinctBy { it.id }
        val sorted = if (sort == Sort.LONGEST) filtered.sortedByDescending { it.durationSec } else filtered
        Result(sorted, errors)
    }
}
