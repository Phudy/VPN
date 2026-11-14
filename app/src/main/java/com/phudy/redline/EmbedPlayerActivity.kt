package com.phudy.redline

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.phudy.redline.data.Prefs
import com.phudy.redline.data.Favorite
import com.phudy.redline.data.Favorites
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import kotlinx.coroutines.delay
import com.phudy.redline.download.Downloader
import com.phudy.redline.ui.CastSheet
import com.phudy.redline.ui.theme.R
import com.phudy.redline.ui.theme.RedlineTheme
import com.phudy.redline.vpn.Blocklist
import java.io.ByteArrayInputStream

/**
 * Plays a clip straight from the source's embed player (no site pages, pop-ups or ads),
 * catches the real video stream so it can be downloaded, cast or opened in REDLINE Player.
 */
class EmbedPlayerActivity : ComponentActivity() {
    private var web: WebView? = null
    private var fullView: View? = null
    private var full by mutableStateOf(false)
    private var uiVisible by mutableStateOf(true)
    private var touchTick by mutableIntStateOf(0)

    /** Full screen = landscape + hidden bars; the embed page scales its video to fill the screen. */
    private fun toggleFullscreen(on: Boolean) {
        full = on
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        touchTick++
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val embed = intent.getStringExtra(EXTRA_EMBED) ?: run { finish(); return }
        val page = intent.getStringExtra(EXTRA_PAGE).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val favorite = Favorite(
            intent.getStringExtra(EXTRA_ID) ?: "e-${embed.hashCode()}", title,
            intent.getStringExtra(EXTRA_THUMB).orEmpty(), intent.getStringExtra(EXTRA_SOURCE).orEmpty(),
            intent.getIntExtra(EXTRA_DURATION, 0), embed, page, null, System.currentTimeMillis(),
        )
        val host = runCatching { java.net.URL(embed).host.removePrefix("www.").removePrefix("embed.") }.getOrDefault("")

        setContent {
            RedlineTheme {
                val streams = remember { mutableStateListOf<String>() }
                var castUrl by remember { mutableStateOf<String?>(null) }
                val favs by Favorites.items.collectAsState()
                val isFav = favs.any { it.id == favorite.id || (it.embedUrl == embed) }
                // Controls fade out 3.5 s after the last touch; any touch brings them back
                LaunchedEffect(touchTick) { uiVisible = true; delay(3500); uiVisible = false }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { c ->
                            val root = FrameLayout(c)
                            val w = WebView(c).apply {
                                @SuppressLint("ClickableViewAccessibility")
                                setOnTouchListener { _, _ -> touchTick++; false }
                                setBackgroundColor(android.graphics.Color.BLACK)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.setSupportMultipleWindows(false)
                                settings.javaScriptCanOpenWindowsAutomatically = false
                                webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                                        val url = req.url.toString()
                                        val h = req.url.host.orEmpty()
                                        if (Prefs.adBlock.value && Blocklist.isBlocked(h)) {
                                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                        }
                                        if (STREAM_RE.containsMatchIn(url)) post { if (url !in streams) streams.add(0, url) }
                                        return null
                                    }
                                    // Stay inside the player: no navigation to site pages or ad landing pages
                                    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) =
                                        !(req.url.host.orEmpty().contains(host) && req.url.path.orEmpty().contains("embed"))
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                        fullView = view
                                        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                                        view.setOnTouchListener { _, _ -> touchTick++; false }
                                        toggleFullscreen(true)
                                    }
                                    override fun onHideCustomView() { fullView?.let { root.removeView(it) }; fullView = null; toggleFullscreen(false) }
                                }
                                loadUrl(embed, mapOf("Referer" to page.ifBlank { embed }))
                            }
                            web = w
                            root.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            root
                        },
                    )

                    // Top bar
                    AnimatedVisibility(uiVisible || !full, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                    Row(
                        Modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { if (full) toggleFullscreen(false) else finish() }) { Icon(Icons.Rounded.ArrowBack, "กลับ", tint = Color.White) }
                        Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            val on = Favorites.toggle(favorite)
                            Toast.makeText(this@EmbedPlayerActivity, if (on) "เพิ่มในรายการชื่นชอบแล้ว ♥" else "เอาออกจากรายการชื่นชอบแล้ว", Toast.LENGTH_SHORT).show()
                        }) { Icon(if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "ชื่นชอบ", tint = if (isFav) R.Red else Color.White) }
                        IconButton(onClick = { Downloader.shareLink(this@EmbedPlayerActivity, page.ifBlank { embed }, title) }) {
                            Icon(Icons.Rounded.Share, "แชร์", tint = Color.White)
                        }
                        IconButton(onClick = { toggleFullscreen(!full) }) {
                            Icon(if (full) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, "เต็มจอ", tint = Color.White)
                        }
                    }
                    }

                    // Stream actions (appear once the real stream is found)
                    val s = streams.firstOrNull { it.contains(".m3u8") } ?: streams.firstOrNull()
                    if (uiVisible || !full) Row(
                        Modifier.align(Alignment.BottomCenter).padding(16.dp).clip(RoundedCornerShape(50)).background(Color(0xCC141418))
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (s == null) {
                            Text("กดเล่นวิดีโอเพื่อปลดล็อกดาวน์โหลด / แคสต์ / ซับไทย", color = R.Muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                        } else {
                            val headers = Downloader.headersFor(embed, web?.settings?.userAgentString, s)
                            TextButton(onClick = {
                                Downloader.enqueue(this@EmbedPlayerActivity, s, title, headers)
                                Toast.makeText(this@EmbedPlayerActivity, "เริ่มดาวน์โหลด — ดูได้ที่แท็บมีเดีย", Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Rounded.Download, null, tint = R.Red); Text(" ดาวน์โหลด", color = Color.White) }
                            TextButton(onClick = { castUrl = s }) { Icon(Icons.Rounded.Cast, null, tint = R.Red); Text(" ทีวี", color = Color.White) }
                            TextButton(onClick = {
                                web?.onPause()
                                startActivity(
                                    Intent(this@EmbedPlayerActivity, PlayerActivity::class.java)
                                        .putExtra(PlayerActivity.EXTRA_URL, s).putExtra(PlayerActivity.EXTRA_TITLE, title)
                                        .putExtra(PlayerActivity.EXTRA_HEADERS, Downloader.toBundle(headers))
                                )
                            }) { Icon(Icons.Rounded.Translate, null, tint = R.Red); Text(" ซับไทย", color = Color.White) }
                        }
                    }
                }
                castUrl?.let { CastSheet(it, title, Downloader.headersFor(embed, web?.settings?.userAgentString, it)) { castUrl = null } }
            }
        }
    }

    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onDestroy() { web?.destroy(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullView != null -> web?.webChromeClient?.onHideCustomView()
            full -> toggleFullscreen(false)
            else -> super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_EMBED = "embed"
        const val EXTRA_PAGE = "page"
        const val EXTRA_TITLE = "title"
        const val EXTRA_THUMB = "thumb"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_ID = "clip_id"
        private val STREAM_RE = Regex("""\.(m3u8|mp4|webm)(\?|#|$)""", RegexOption.IGNORE_CASE)
    }
}
