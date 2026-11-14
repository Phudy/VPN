package com.phudy.redline.data

import android.content.Context
import android.content.Intent
import com.phudy.redline.EmbedPlayerActivity
import com.phudy.redline.PlayerActivity
import com.phudy.redline.explore.Clip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved clip: either a search result (played via its embed) or a direct video link. */
data class Favorite(
    val id: String, val title: String, val thumb: String, val source: String, val durationSec: Int,
    val embedUrl: String?, val pageUrl: String?, val videoUrl: String?, val addedAt: Long,
) {
    fun open(ctx: Context) {
        val i = if (embedUrl != null) Intent(ctx, EmbedPlayerActivity::class.java)
            .putExtra(EmbedPlayerActivity.EXTRA_EMBED, embedUrl).putExtra(EmbedPlayerActivity.EXTRA_PAGE, pageUrl)
            .putExtra(EmbedPlayerActivity.EXTRA_TITLE, title).putExtra(EmbedPlayerActivity.EXTRA_THUMB, thumb)
            .putExtra(EmbedPlayerActivity.EXTRA_SOURCE, source).putExtra(EmbedPlayerActivity.EXTRA_DURATION, durationSec)
            .putExtra(EmbedPlayerActivity.EXTRA_ID, id)
        else Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URL, videoUrl).putExtra(PlayerActivity.EXTRA_TITLE, title)
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        fun of(c: Clip) = Favorite(c.id, c.title, c.thumb, c.source, c.durationSec, c.embedUrl, c.pageUrl, null, System.currentTimeMillis())
        fun ofVideo(url: String, title: String) =
            Favorite("v-" + url.hashCode(), title, "", "วิดีโอ", 0, null, null, url, System.currentTimeMillis())
    }
}

/** Favorites ("ชื่นชอบ"), stored privately in the app's files (not visible to other apps). */
object Favorites {
    private val _items = MutableStateFlow<List<Favorite>>(emptyList())
    val items: StateFlow<List<Favorite>> = _items
    private lateinit var file: File

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "favorites.json")
        _items.value = runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                Favorite(
                    o.getString("id"), o.optString("title"), o.optString("thumb"), o.optString("source"), o.optInt("dur"),
                    o.optString("embed").ifBlank { null }, o.optString("page").ifBlank { null },
                    o.optString("video").ifBlank { null }, o.optLong("at"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun isFav(id: String) = _items.value.any { it.id == id }

    /** Adds or removes; returns true when the item is now a favorite. */
    fun toggle(f: Favorite): Boolean {
        val now = if (isFav(f.id)) _items.value.filterNot { it.id == f.id } else listOf(f) + _items.value
        _items.value = now
        save()
        return now.any { it.id == f.id }
    }

    fun remove(id: String) { _items.value = _items.value.filterNot { it.id == id }; save() }

    private fun save() {
        val a = JSONArray()
        _items.value.forEach { f ->
            a.put(JSONObject().put("id", f.id).put("title", f.title).put("thumb", f.thumb).put("source", f.source)
                .put("dur", f.durationSec).put("embed", f.embedUrl ?: "").put("page", f.pageUrl ?: "")
                .put("video", f.videoUrl ?: "").put("at", f.addedAt))
        }
        file.writeText(a.toString())
    }
}
