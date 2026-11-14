package com.phudy.redline

import android.app.PictureInPictureParams
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.content.Intent
import android.widget.Toast
import com.phudy.redline.download.Downloader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.phudy.redline.translate.SubTranslator
import com.phudy.redline.ui.CastSheet
import com.phudy.redline.ui.theme.R
import com.phudy.redline.ui.theme.RedlineTheme
import kotlinx.coroutines.launch

/**
 * REDLINE Player: ExoPlayer (HLS / DASH / MP4), always-highest-quality start,
 * big buffer for smooth streaming, live Thai subtitle translation, TV cast and PiP.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private var headers: Map<String, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val url = intent.getStringExtra(EXTRA_URL) ?: intent.data?.toString() ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: Uri.parse(url).lastPathSegment ?: "Video"

        trackSelector = DefaultTrackSelector(this).apply {
            // Start at the best quality the network/screen allows ("ภาพคมชัด")
            setParameters(buildUponParameters().setForceHighestSupportedBitrate(false).setPreferredTextLanguage("th"))
        }
        // Larger buffer = smoother playback on shaky connections
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 1_500, 3_000)
            .build()
        headers = Downloader.fromBundle(intent.getBundleExtra(EXTRA_HEADERS))
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers.filterKeys { it != "User-Agent" })
            .apply { headers["User-Agent"]?.let { setUserAgent(it) } }
        player = ExoPlayer.Builder(this).setTrackSelector(trackSelector).setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        setMedia(url, null)
        player.playWhenReady = true

        setContent { RedlineTheme { PlayerUi(url, title) } }
    }

    private fun setMedia(url: String, subtitle: Uri?) {
        val pos = player.currentPosition
        val b = MediaItem.Builder().setUri(url)
        if (url.contains(".m3u8", true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (url.contains(".mpd", true)) b.setMimeType(MimeTypes.APPLICATION_MPD)
        if (subtitle != null) {
            val name = subtitle.lastPathSegment.orEmpty().lowercase()
            val mime = when {
                name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            b.setSubtitleConfigurations(
                listOf(MediaItem.SubtitleConfiguration.Builder(subtitle).setMimeType(mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build())
            )
        }
        player.setMediaItem(b.build(), pos)
        player.prepare()
    }

    @Composable
    private fun PlayerUi(url: String, title: String) {
        val scope = rememberCoroutineScope()
        var translate by remember { mutableStateOf(true) }
        var original by remember { mutableStateOf("") }
        var thai by remember { mutableStateOf("") }
        var srcLang by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var showCast by remember { mutableStateOf(false) }
        var controlsVisible by remember { mutableStateOf(true) }
        var zoom by remember { mutableStateOf(false) }
        var speed by remember { mutableFloatStateOf(1f) }
        var playerView by remember { mutableStateOf<PlayerView?>(null) }

        val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) setMedia(url, uri)
        }

        DisposableEffect(Unit) {
            val l = object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) {
                    val text = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n").trim()
                    original = text
                    if (text.isEmpty()) { thai = ""; return }
                    if (translate) scope.launch {
                        val lang = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
                            ?.getTrackFormat(0)?.language
                        thai = runCatching { SubTranslator.toThai(text, lang) }.getOrElse { "⚠ ${it.message}" }
                        srcLang = SubTranslator.lastSource
                    }
                }
                override fun onPlayerError(e: PlaybackException) { error = "เล่นไม่ได้: ${e.errorCodeName}" }
            }
            player.addListener(l)
            onDispose { player.removeListener(l) }
        }
        LaunchedEffect(translate, playerView) {
            playerView?.subtitleView?.visibility = if (translate) android.view.View.GONE else android.view.View.VISIBLE
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    PlayerView(c).apply {
                        this.player = this@PlayerActivity.player
                        setShowNextButton(false); setShowPreviousButton(false)
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controlsVisible = v == android.view.View.VISIBLE })
                        playerView = this
                    }
                },
                update = { it.resizeMode = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
            )

            // Thai subtitle overlay
            if (translate && thai.isNotBlank()) {
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = if (controlsVisible) 96.dp else 28.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        thai, textAlign = TextAlign.Center, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        style = TextStyle(shadow = Shadow(Color.Black, blurRadius = 8f)),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    if (original.isNotBlank() && original != thai) {
                        Text(original, textAlign = TextAlign.Center, fontSize = 12.sp, color = Color(0xCCFFFFFF), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // Top bar
            AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { finish() }) { Icon(Icons.Rounded.ArrowBack, "กลับ", tint = Color.White) }
                    Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).widthIn(max = 220.dp))
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState(), reverseScrolling = true), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Chip(if (translate) "ซับไทย ON" + (srcLang?.let { " • " + SubTranslator.languageName(it) } ?: "") else "ซับไทย OFF", translate) { translate = !translate }
                    IconButton(onClick = { subPicker.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.Subtitles, "โหลดไฟล์ซับ", tint = Color.White) }
                    IconButton(onClick = {
                        TrackSelectionDialogBuilder(this@PlayerActivity, "ความคมชัด", player, C.TRACK_TYPE_VIDEO).build().show()
                    }) { Icon(Icons.Rounded.HighQuality, "ความคมชัด", tint = Color.White) }
                    IconButton(onClick = {
                        speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; 2f -> 0.75f; else -> 1f }
                        player.setPlaybackSpeed(speed)
                    }) { Text("${speed}x", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    IconButton(onClick = { zoom = !zoom }) { Icon(if (zoom) Icons.Rounded.FitScreen else Icons.Rounded.ZoomOutMap, "ขยาย", tint = Color.White) }
                    IconButton(onClick = {
                        if (url.startsWith("http")) {
                            Downloader.enqueue(this@PlayerActivity, url, title, headers)
                            Toast.makeText(this@PlayerActivity, "เริ่มดาวน์โหลด — บันทึกไว้ที่ Movies/REDLINE", Toast.LENGTH_SHORT).show()
                        } else error = "ไฟล์นี้อยู่ในเครื่องแล้ว"
                    }) { Icon(Icons.Rounded.Download, "ดาวน์โหลด", tint = Color.White) }
                    IconButton(onClick = {
                        if (url.startsWith("http")) Downloader.shareLink(this@PlayerActivity, url, title)
                        else startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, Uri.parse(url))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "แชร์วิดีโอ"))
                    }) { Icon(Icons.Rounded.Share, "แชร์", tint = Color.White) }
                    IconButton(onClick = { enterPip() }) { Icon(Icons.Rounded.PictureInPictureAlt, "PiP", tint = Color.White) }
                    IconButton(onClick = { if (url.startsWith("http")) showCast = true else error = "แคสต์ได้เฉพาะวิดีโอจากลิงก์ออนไลน์" }) {
                        Icon(Icons.Rounded.Cast, "แคสต์ไปทีวี", tint = R.Red)
                    }
                    }
                }
            }

            error?.let {
                Text(
                    it, color = Color.White, modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp))
                        .background(R.Red.copy(alpha = .85f)).clickable { error = null }.padding(16.dp),
                )
            }
        }
        if (showCast) CastSheet(url, title) { showCast = false; player.pause() }
    }

    @Composable
    private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
        Text(
            text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) R.Red else Color(0x55FFFFFF))
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }

    private fun enterPip() {
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
    }

    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (player.isPlaying) enterPip() }
    override fun onStop() { super.onStop(); if (!isInPictureInPictureMode) player.pause() }
    override fun onDestroy() { if (::player.isInitialized) player.release(); super.onDestroy() }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_HEADERS = "headers"
    }
}
