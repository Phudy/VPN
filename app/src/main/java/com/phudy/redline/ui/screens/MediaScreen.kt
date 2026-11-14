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
        }
        Spacer(Modifier.height(R.gapS))
        Card(Modifier.padding(horizontal = R.gap).fillMaxWidth()) {
            SettingRow(Icons.Rounded.FolderOpen, "เปิดไฟล์วิดีโอในเครื่อง", "MP4 • MKV • WEBM + ไฟล์ซับ SRT/VTT/ASS", onClick = { filePicker.launch(arrayOf("video/*")) })
        }

        Spacer(Modifier.height(R.gap))
        Card(Modifier.padding(horizontal = R.gap).fillMaxWidth()) {
            SectionLabel("ฟีเจอร์เพลเยอร์")
            Feature(Icons.Rounded.Translate, "แปลซับเป็นไทยแบบเรียลไทม์", "ตรวจจับภาษาอัตโนมัติ 50+ ภาษา แปลบนเครื่อง ใช้ได้แม้ออฟไลน์")
            Feature(Icons.Rounded.HighQuality, "เลือกความคมชัดเอง / อัตโนมัติ", "รองรับ HLS, DASH, MP4 สูงสุดถึง 4K")
            Feature(Icons.Rounded.Speed, "บัฟเฟอร์ใหญ่ 2 นาที", "ลดการกระตุกเมื่อเน็ตแกว่ง")
            Feature(Icons.Rounded.Cast, "แคสต์ Samsung Smart TV", "DLNA ในวง Wi-Fi เดียวกัน + Smart View")
            Feature(Icons.Rounded.PictureInPictureAlt, "ภาพซ้อนภาพ (PiP)", "ดูต่อขณะใช้แอปอื่น")
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
