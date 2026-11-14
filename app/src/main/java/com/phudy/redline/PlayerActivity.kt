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
import android.content.Context
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.phudy.redline.speech.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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

    // ---- speech → Thai ----
    private enum class SpeechMode { OFF, LIVE, FULL }
    private val speechCues = mutableStateListOf<Pair<TimedCue, Long>>() // cue + player position when it arrived
    private var speechMode by mutableStateOf(SpeechMode.OFF)
    private var speechStatus by mutableStateOf<String?>(null)
    private var fullProgress by mutableFloatStateOf(0f)
    private var speechJob: Job? = null
    private val chunks = Channel<Pair<FloatArray, Long>>(2, BufferOverflow.DROP_OLDEST)
    private val tap = AudioTap(5) { pcm, start -> chunks.trySend(pcm to start) }
    private var chunksDone by mutableIntStateOf(0)
    private var lastSecs by mutableFloatStateOf(0f)

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
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(TeeAudioProcessor(tap))).build()
        }
        player = ExoPlayer.Builder(this, renderers).setTrackSelector(trackSelector).setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        setMedia(url, null)
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                tap.baseMs = new.positionMs
                if (speechMode == SpeechMode.LIVE) speechCues.clear()
            }
        })

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

    private fun startSpeech(mode: SpeechMode, url: String) {
        speechJob?.cancel(); speechCues.clear(); tap.enabled = false
        speechMode = mode; speechStatus = null; fullProgress = 0f
        if (mode == SpeechMode.OFF) return
        if (!SpeechTranslator.available) { speechStatus = "อุปกรณ์นี้ไม่รองรับการแปลเสียง (ต้องเป็น ARM 64-bit)"; speechMode = SpeechMode.OFF; return }
        if (SpeechModels.usablePath() == null) { speechStatus = "ต้องดาวน์โหลดโมเดลแปลเสียงก่อน"; speechMode = SpeechMode.OFF; return }
        speechJob = lifecycleScope.launch {
            when (mode) {
                SpeechMode.LIVE -> {
                    tap.baseMs = player.currentPosition
                    tap.enabled = true
                    speechStatus = "กำลังฟัง…"
                    chunksDone = 0
                    for ((pcm, start) in chunks) {
                        val gen = tap.generation
                        val t0 = System.currentTimeMillis()
                        val cues = runCatching { SpeechTranslator.process(pcm, start) }
                            .onFailure { speechStatus = "แปลเสียงไม่ได้: ${it.message}" }.getOrDefault(emptyList())
                        lastSecs = (System.currentTimeMillis() - t0) / 1000f
                        chunksDone++
                        if (gen != tap.generation) continue
                        if (cues.isEmpty()) {
                            if (!speechStatus.orEmpty().startsWith("แปลเสียงไม่ได้"))
                                speechStatus = "กำลังฟัง… ยังไม่ได้ยินเสียงพูด (%d ช่วง, %.1f วิ/ช่วง)".format(chunksDone, lastSecs)
                            continue
                        }
                        // Results come back after the words were spoken. Delay this batch as a whole so
                        // its first line appears now and the rest keep their natural spacing.
                        val now = player.currentPosition
                        val lag = (now - cues.first().startMs).coerceAtLeast(0)
                        speechCues.addAll(cues.map { it.copy(startMs = it.startMs + lag, endMs = it.endMs + lag) to Long.MAX_VALUE })
                        if (speechCues.size > 200) speechCues.removeRange(0, speechCues.size - 200)
                        speechStatus = "แปลจาก %s • ช้ากว่าเสียง %.0f วิ".format(SubTranslator.languageName(cues.first().lang), lag / 1000f)
                    }
                }
                SpeechMode.FULL -> {
                    speechStatus = "กำลังแปลทั้งคลิป…"
                    runCatching {
                        FullClipTranslator.run(this@PlayerActivity, url, headers, player.currentPosition, onProgress = { fullProgress = it }) { cues ->
                            runOnUiThread { speechCues.addAll(cues.map { it to Long.MAX_VALUE }) }
                        }
                    }.onSuccess { speechStatus = "แปลครบทั้งคลิปแล้ว (${speechCues.size} ประโยค)" }
                        .onFailure { if (it !is CancellationException) speechStatus = "แปลทั้งคลิปไม่ได้: ${it.message} — ลองโหมดแปลสด" }
                }
                SpeechMode.OFF -> {}
            }
        }
    }

    /** Cue to show at [pos]: exact match first; in live mode a just-arrived cue stays up a few seconds. */
    private fun cueAt(pos: Long): TimedCue? {
        for (i in speechCues.indices.reversed()) {
            val c = speechCues[i].first
            if (pos in c.startMs..c.endMs + 400) return c
        }
        return null
    }

    @Composable
    private fun SpeechDialog(url: String, onDismiss: () -> Unit) {
        val installed by SpeechModels.installed.collectAsState()
        val dl by SpeechModels.downloading.collectAsState()
        val prog by SpeechModels.progress.collectAsState()
        val scope = rememberCoroutineScope()
        val canFull = !url.contains(".m3u8", true) && !url.contains(".mpd", true)
        AlertDialog(
            onDismissRequest = onDismiss, containerColor = R.Surface,
            title = { Text("แปลเสียงเป็นไทย (AI)", color = R.Text) },
            text = {
                Column {
                    Text("ฟังเสียงพูดในคลิป ตรวจภาษาอัตโนมัติ 99 ภาษา แล้วแปลเป็นซับไทย ทำงานบนเครื่อง ไม่ส่งเสียงออกไปไหน", color = R.Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    if (installed.isEmpty()) {
                        Text("ยังไม่มีโมเดล — ดาวน์โหลดครั้งเดียว ใช้ได้ตลอด", color = R.Warn, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        if (dl != null) LinearProgressIndicator(progress = { prog }, Modifier.fillMaxWidth(), color = R.Red, trackColor = R.Surface2)
                        else SPEECH_MODELS.forEach { m ->
                            TextButton(onClick = { scope.launch { SpeechModels.select(m.id); SpeechModels.download(m.id) } }) {
                                Text("ดาวน์โหลด ${m.label} (${m.sizeMb} MB) — ${m.note}", color = R.Red, fontSize = 13.sp)
                            }
                        }
                    } else {
                        ModeRow("ปิด", speechMode == SpeechMode.OFF) { startSpeech(SpeechMode.OFF, url); onDismiss() }
                        ModeRow("แปลสด (ซับขึ้นช้ากว่าเสียงเล็กน้อย)", speechMode == SpeechMode.LIVE) { startSpeech(SpeechMode.LIVE, url); onDismiss() }
                        if (canFull) ModeRow("แปลทั้งคลิปล่วงหน้า (ซับตรงเวลา แนะนำ)", speechMode == SpeechMode.FULL) { startSpeech(SpeechMode.FULL, url); onDismiss() }
                        else Text("สตรีม HLS ใช้ได้เฉพาะแปลสด — ดาวน์โหลดคลิปก่อนเพื่อแปลทั้งคลิปให้ตรงเวลา", color = R.Muted, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด", color = R.Red) } },
        )
    }

    @Composable
    private fun ModeRow(text: String, selected: Boolean, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked, null, tint = R.Red)
            Spacer(Modifier.width(10.dp))
            Text(text, color = R.Text, fontSize = 14.sp)
        }
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
        var showSpeech by remember { mutableStateOf(false) }
        var speechCue by remember { mutableStateOf<TimedCue?>(null) }
        val mlDownloading by SubTranslator.downloadingModel.collectAsState()
        var hasTextTrack by remember { mutableStateOf(false) }
        DisposableEffect(Unit) {
            val l = object : Player.Listener {
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    hasTextTrack = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
                }
            }
            player.addListener(l); onDispose { player.removeListener(l) }
        }
        LaunchedEffect(speechMode) {
            while (speechMode != SpeechMode.OFF) { speechCue = cueAt(player.currentPosition); delay(150) }
            speechCue = null
        }

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
            speechCue?.let { c ->
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = if (controlsVisible) 96.dp else 28.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        c.thai, textAlign = TextAlign.Center, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        style = TextStyle(shadow = Shadow(Color.Black, blurRadius = 8f)),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    if (c.original != c.thai) Text(c.original, textAlign = TextAlign.Center, fontSize = 12.sp, color = Color(0xCCFFFFFF), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // Always-visible entry point for speech translation
            if (speechMode == SpeechMode.OFF && speechStatus == null && controlsVisible) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 12.dp).clip(RoundedCornerShape(50)).background(R.redGradient)
                        .clickable { showSpeech = true }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.RecordVoiceOver, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("แปลเสียงเป็นไทย", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (speechMode != SpeechMode.OFF || speechStatus != null) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 12.dp).clip(RoundedCornerShape(50)).background(Color(0xAA000000))
                        .clickable { if (speechMode == SpeechMode.OFF) speechStatus = null; showSpeech = true }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.RecordVoiceOver, null, tint = R.Red, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        (mlDownloading?.let { "กำลังโหลดโมเดลแปล ${SubTranslator.languageName(it)}→ไทย ครั้งแรก (~30 MB)…" } ?: speechStatus ?: "") + if (speechMode == SpeechMode.FULL && fullProgress < 1f) " ${(fullProgress * 100).toInt()}%" else "",
                        color = Color.White, fontSize = 12.sp,
                    )
                }
            }
            if (speechCue == null && speechMode == SpeechMode.OFF && translate && thai.isNotBlank()) {
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
                    if (!hasTextTrack) Chip("คลิปไม่มีซับ → แปลเสียง", speechMode != SpeechMode.OFF) { showSpeech = true }
                    else Chip(if (translate) "ซับไทย ON" + (srcLang?.let { " • " + SubTranslator.languageName(it) } ?: "") else "ซับไทย OFF", translate) { translate = !translate }
                    IconButton(onClick = { showSpeech = true }) {
                        Icon(Icons.Rounded.RecordVoiceOver, "แปลเสียงเป็นไทย", tint = if (speechMode != SpeechMode.OFF) R.Red else Color.White)
                    }
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
        if (showSpeech) SpeechDialog(url) { showSpeech = false }
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
    override fun onDestroy() { speechJob?.cancel(); tap.enabled = false; if (::player.isInitialized) player.release(); super.onDestroy() }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_HEADERS = "headers"
    }
}
