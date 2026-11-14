package com.phudy.redline.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phudy.redline.PlayerActivity
import com.phudy.redline.data.Prefs
import com.phudy.redline.download.Downloader
import com.phudy.redline.download.DlStatus
import com.phudy.redline.net.NetTools
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import com.phudy.redline.ui.*
import com.phudy.redline.ui.theme.R

@Composable
fun MediaScreen() {
    val ctx = LocalContext.current
    val history by Prefs.mediaHistory.collectAsState()
    var link by remember { mutableStateOf("") }
    var castUrl by remember { mutableStateOf<String?>(null) }

    fun play(url: String, title: String? = null) {
        if (url.startsWith("http")) Prefs.addHistory(url)
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URL, url).putExtra(PlayerActivity.EXTRA_TITLE, title))
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) play(uri.toString(), uri.lastPathSegment?.substringAfterLast('/'))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("มีเดีย", "REDLINE Player • ซับไทยอัตโนมัติ • แคสต์ทีวี")

        Card(Modifier.padding(horizontal = R.gap).fillMaxWidth()) {
            SectionLabel("เปิดสตรีมจากลิงก์")
            OutlinedTextField(
                link, { link = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("https://…/video.m3u8 หรือ .mp4", color = R.Muted) },
                shape = RoundedCornerShape(R.radiusS),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = R.Red, unfocusedBorderColor = R.Stroke, cursorColor = R.Red),
            )
            Spacer(Modifier.height(R.gapS))
            Row(horizontalArrangement = Arrangement.spacedBy(R.gapS)) {
                RedButton("เล่น", Icons.Rounded.PlayArrow, Modifier.weight(1f), enabled = link.startsWith("http")) { play(link.trim()) }
                GhostButton("ส่งไปทีวี", Icons.Rounded.Cast, Modifier.weight(1f)) { if (link.startsWith("http")) castUrl = link.trim() }
            }
            Spacer(Modifier.height(R.gapS))
            Row(horizontalArrangement = Arrangement.spacedBy(R.gapS)) {
                GhostButton("ดาวน์โหลด", Icons.Rounded.Download, Modifier.weight(1f)) {
                    if (link.startsWith("http")) Downloader.enqueue(ctx, link.trim(), null)
                }
                GhostButton("แชร์ลิงก์", Icons.Rounded.Share, Modifier.weight(1f)) {
                    if (link.startsWith("http")) Downloader.shareLink(ctx, link.trim(), null)
                }
            }
        }
        Spacer(Modifier.height(R.gapS))
        Card(Modifier.padding(horizontal = R.gap).fillMaxWidth()) {
            SettingRow(Icons.Rounded.FolderOpen, "เปิดไฟล์วิดีโอในเครื่อง", "MP4 • MKV • WEBM + ไฟล์ซับ SRT/VTT/ASS", onClick = { filePicker.launch(arrayOf("video/*")) })
        }

        DownloadsSection()
        FavoriteVideos()

        Spacer(Modifier.height(R.gap))
        Card(Modifier.padding(horizontal = R.gap).fillMaxWidth()) {
            SectionLabel("ฟีเจอร์เพลเยอร์")
            Feature(Icons.Rounded.Translate, "แปลซับเป็นไทยแบบเรียลไทม์", "ตรวจจับภาษาอัตโนมัติ 50+ ภาษา แปลบนเครื่อง ใช้ได้แม้ออฟไลน์")
            Feature(Icons.Rounded.HighQuality, "เลือกความคมชัดเอง / อัตโนมัติ", "รองรับ HLS, DASH, MP4 สูงสุดถึง 4K")
            Feature(Icons.Rounded.Speed, "บัฟเฟอร์ใหญ่ 2 นาที", "ลดการกระตุกเมื่อเน็ตแกว่ง")
            Feature(Icons.Rounded.Cast, "แคสต์ Samsung Smart TV", "DLNA ในวง Wi-Fi เดียวกัน + Smart View")
            Feature(Icons.Rounded.PictureInPictureAlt, "ภาพซ้อนภาพ (PiP)", "ดูต่อขณะใช้แอปอื่น")
            Feature(Icons.Rounded.Download, "ดาวน์โหลด & แชร์", "MP4 และ HLS (m3u8) ดาวน์โหลดต่อได้เมื่อเน็ตหลุด บันทึกลงแกลเลอรี")
        }

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(R.gap))
            SectionLabel("เล่นล่าสุด", Modifier.padding(horizontal = R.gap))
            history.forEach { h ->
                Card(Modifier.padding(horizontal = R.gap, vertical = 4.dp).fillMaxWidth(), onClick = { play(h) }) {
                    Text(h.substringAfterLast('/').substringBefore('?'), color = R.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(h, color = R.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = { Prefs.clearHistory() }, modifier = Modifier.padding(horizontal = R.gap)) { Text("ล้างประวัติ", color = R.Red) }
        }
        Spacer(Modifier.height(24.dp))
    }
    castUrl?.let { CastSheet(it, "REDLINE") { castUrl = null } }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String) =
    SettingRow(icon, title, sub)

@Composable
private fun DownloadsSection() {
    val ctx = LocalContext.current
    val tasks by Downloader.tasks.collectAsState()
    if (tasks.isEmpty()) return
    Spacer(Modifier.height(R.gap))
    SectionLabel("ดาวน์โหลด • บันทึกไว้ที่ Movies/REDLINE", Modifier.padding(horizontal = R.gap))
    tasks.forEach { t ->
        Card(Modifier.padding(horizontal = R.gap, vertical = 4.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = R.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = when (t.status) {
                        DlStatus.QUEUED -> "รอคิว…"
                        DlStatus.RUNNING -> (if (t.progress >= 0) "${(t.progress * 100).toInt()}% • " else "") + NetTools.formatBytes(t.bytes)
                        DlStatus.DONE -> "เสร็จแล้ว • " + NetTools.formatBytes(t.bytes)
                        DlStatus.FAILED -> "ล้มเหลว: ${t.error}"
                        DlStatus.CANCELLED -> "ยกเลิกแล้ว"
                    }
                    Text(status, color = if (t.status == DlStatus.FAILED) R.Red else R.Muted, fontSize = 12.sp, maxLines = 2)
                }
                when (t.status) {
                    DlStatus.RUNNING, DlStatus.QUEUED ->
                        IconButton(onClick = { Downloader.cancel(t.id) }) { Icon(Icons.Rounded.Close, "ยกเลิก", tint = R.Muted) }
                    DlStatus.DONE -> {
                        IconButton(onClick = { Downloader.openFile(ctx, t) }) { Icon(Icons.Rounded.PlayArrow, "เล่น", tint = R.Red) }
                        IconButton(onClick = { Downloader.shareFile(ctx, t) }) { Icon(Icons.Rounded.Share, "แชร์ไฟล์", tint = R.Red) }
                    }
                    DlStatus.FAILED, DlStatus.CANCELLED ->
                        IconButton(onClick = { Downloader.retry(ctx, t.id) }) { Icon(Icons.Rounded.Refresh, "ลองใหม่", tint = R.Red) }
                }
                IconButton(onClick = { Downloader.remove(ctx, t.id, deleteFile = false) }) { Icon(Icons.Rounded.DeleteOutline, "เอาออกจากรายการ", tint = R.Muted) }
            }
            if (t.status == DlStatus.RUNNING) {
                Spacer(Modifier.height(8.dp))
                if (t.progress >= 0) LinearProgressIndicator(progress = { t.progress }, Modifier.fillMaxWidth(), color = R.Red, trackColor = R.Surface2)
                else LinearProgressIndicator(Modifier.fillMaxWidth(), color = R.Red, trackColor = R.Surface2)
            }
        }
    }
}

@Composable
private fun FavoriteVideos() {
    val ctx = LocalContext.current
    val favs by com.phudy.redline.data.Favorites.items.collectAsState()
    val videos = favs.filter { it.embedUrl == null }
    if (videos.isEmpty()) return
    Spacer(Modifier.height(R.gap))
    SectionLabel("♥ วิดีโอที่ชื่นชอบ", Modifier.padding(horizontal = R.gap))
    videos.forEach { f ->
        Card(Modifier.padding(horizontal = R.gap, vertical = 4.dp).fillMaxWidth(), onClick = { f.open(ctx) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Favorite, null, tint = R.Red)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.title, color = R.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(f.videoUrl.orEmpty(), color = R.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { com.phudy.redline.data.Favorites.remove(f.id) }) { Icon(Icons.Rounded.DeleteOutline, "ลบ", tint = R.Muted) }
            }
        }
    }
}
