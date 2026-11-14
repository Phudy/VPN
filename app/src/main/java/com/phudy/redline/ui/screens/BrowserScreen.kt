package com.phudy.redline.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.phudy.redline.PlayerActivity
import com.phudy.redline.data.Prefs
import com.phudy.redline.download.Downloader
import android.widget.Toast
import com.phudy.redline.ui.*
import com.phudy.redline.ui.theme.R
import com.phudy.redline.vpn.Blocklist
import java.io.ByteArrayInputStream
import java.net.URLEncoder

private val MEDIA_RE = Regex("""\.(m3u8|mp4|webm|mpd|mkv|mov)(\?|#|$)""", RegexOption.IGNORE_CASE)

/** Private browser: blocks ads/trackers/pop-ups, wipes data on exit, and catches video streams on the page. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen() {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(100) }
    var canBack by remember { mutableStateOf(false) }
    var blockedHere by remember { mutableIntStateOf(0) }
    val videos = remember { mutableStateListOf<String>() }
    var showVideos by remember { mutableStateOf(false) }
    var castUrl by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }

    fun go(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(t, "UTF-8")
        }
        web?.loadUrl(url)
    }

    DisposableEffect(Unit) {
        onDispose {
            // Incognito: leave nothing behind
            web?.apply { clearHistory(); clearCache(true); clearFormData(); destroy() }
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
    }
    BackHandler(enabled = canBack) { web?.goBack() }

    Column(Modifier.fillMaxSize()) {
        // Address bar
        Row(Modifier.padding(R.gap, 12.dp, R.gap, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("ค้นหา หรือพิมพ์ URL", color = R.Muted) },
                leadingIcon = { Icon(Icons.Rounded.Lock, null, tint = R.Red, modifier = Modifier.size(18.dp)) },
                shape = RoundedCornerShape(R.radiusS),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go(input) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = R.Red, unfocusedBorderColor = R.Stroke,
                    focusedContainerColor = R.Surface, unfocusedContainerColor = R.Surface, cursorColor = R.Red,
                ),
            )
            Spacer(Modifier.width(8.dp))
            BadgedBox(badge = { if (videos.isNotEmpty()) Badge(containerColor = R.Red) { Text("${videos.size}") } }) {
                IconButton(onClick = { showVideos = true }) { Icon(Icons.Rounded.VideoLibrary, "วิดีโอในหน้านี้", tint = if (videos.isEmpty()) R.Muted else R.Red) }
            }
        }
        Row(Modifier.padding(horizontal = R.gap), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pageTitle.ifBlank { "โหมดส่วนตัว • ไม่เก็บประวัติ" }, color = R.Muted, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.Block, null, tint = R.Red, modifier = Modifier.size(14.dp))
            Text(" บล็อก $blockedHere", color = R.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth().padding(top = 6.dp), color = R.Red, trackColor = R.Surface)
        else Spacer(Modifier.height(10.dp))

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#0A0A0C"))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.setSupportMultipleWindows(false) // kills pop-up / pop-under windows
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                            val url = req.url.toString()
                            val host = req.url.host ?: return null
                            if (Prefs.adBlock.value && Blocklist.isBlocked(host)) {
                                post { blockedHere++ }
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }
                            if (MEDIA_RE.containsMatchIn(url)) post { if (url !in videos) videos.add(0, url) }
                            return null
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                            val s = req.url.scheme ?: return true
                            if (s != "http" && s != "https") return true // block intent:// and app-store redirects
                            return Prefs.adBlock.value && Blocklist.isBlocked(req.url.host ?: "")
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            input = url; videos.clear(); blockedHere = 0
                        }

                        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                            canBack = view.canGoBack(); input = url
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, p: Int) { progress = p }
                        override fun onReceivedTitle(view: WebView, title: String?) { pageTitle = title ?: "" }
                    }
                    loadUrl("https://duckduckgo.com/")
                    web = this
                }
            },
        )
    }

    if (showVideos) {
        ModalBottomSheet(onDismissRequest = { showVideos = false }, containerColor = R.Surface) {
            Column(Modifier.padding(horizontal = R.gap).padding(bottom = 32.dp)) {
                Text("วิดีโอที่พบในหน้านี้", color = R.Text, style = MaterialTheme.typography.titleLarge)
                Text("เล่นใน REDLINE Player (ไม่มีโฆษณา + แปลซับไทย) หรือส่งไปทีวี", color = R.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(R.gap))
                if (videos.isEmpty()) Text("ยังไม่พบสตรีมวิดีโอ — กดเล่นวิดีโอบนหน้าเว็บก่อน", color = R.Muted)
                videos.take(12).forEach { v ->
                    Card(Modifier.fillMaxWidth().padding(bottom = R.gapS)) {
                        Text(v.substringAfterLast('/').substringBefore('?').ifBlank { v }, color = R.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(v, color = R.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                        val headers = Downloader.headersFor(web?.url, web?.settings?.userAgentString, v)
                        Row(horizontalArrangement = Arrangement.spacedBy(R.gapS)) {
                            RedButton("เล่น", Icons.Rounded.PlayArrow, Modifier.weight(1f)) {
                                Prefs.addHistory(v)
                                ctx.startActivity(
                                    Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URL, v)
                                        .putExtra(PlayerActivity.EXTRA_TITLE, pageTitle)
                                        .putExtra(PlayerActivity.EXTRA_HEADERS, Downloader.toBundle(headers))
                                )
                            }
                            GhostButton("ทีวี", Icons.Rounded.Cast, Modifier.weight(1f)) { castUrl = v }
                        }
                        Spacer(Modifier.height(R.gapS))
                        Row(horizontalArrangement = Arrangement.spacedBy(R.gapS)) {
                            GhostButton("ดาวน์โหลด", Icons.Rounded.Download, Modifier.weight(1f)) {
                                Downloader.enqueue(ctx, v, pageTitle, headers)
                                Toast.makeText(ctx, "เริ่มดาวน์โหลด — ดูได้ที่แท็บมีเดีย", Toast.LENGTH_SHORT).show()
                            }
                            GhostButton("แชร์", Icons.Rounded.Share, Modifier.weight(1f)) { Downloader.shareLink(ctx, v, pageTitle) }
                        }
                    }
                }
            }
        }
    }
    castUrl?.let { CastSheet(it, pageTitle.ifBlank { "REDLINE" }) { castUrl = null } }
}
