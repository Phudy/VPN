package com.phudy.redline.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phudy.redline.cast.DlnaCaster
import com.phudy.redline.ui.theme.R
import kotlinx.coroutines.launch

/** Bottom sheet: find Samsung / DLNA TVs on Wi-Fi and send the video to one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastSheet(url: String, title: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tvs by remember { mutableStateOf<List<DlnaCaster.Renderer>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var playingOn by remember { mutableStateOf<DlnaCaster.Renderer?>(null) }

    fun scan() { scanning = true; scope.launch { tvs = DlnaCaster.discover(ctx).sortedByDescending { it.isSamsung }; scanning = false } }
    LaunchedEffect(Unit) { scan() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = R.Surface) {
        Column(Modifier.padding(horizontal = R.gap).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Cast, null, tint = R.Red)
                Spacer(Modifier.width(10.dp))
                Text("แคสต์ไปทีวี", color = R.Text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(Modifier.size(20.dp), color = R.Red, strokeWidth = 2.dp)
                else IconButton(onClick = ::scan) { Icon(Icons.Rounded.Refresh, "ค้นหาใหม่", tint = R.Muted) }
            }
            Text("Samsung Smart TV และทีวีที่รองรับ DLNA ใน Wi-Fi เดียวกัน", color = R.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(R.gap))

            if (!scanning && tvs.isEmpty()) {
                Text(
                    "ไม่พบทีวี — ตรวจสอบว่าโทรศัพท์กับทีวีต่อ Wi-Fi เดียวกัน และเปิดทีวีอยู่ " +
                        "(Samsung: Settings › General › External Device Manager › Device Connection Manager › Access Notification)",
                    color = R.Muted, fontSize = 13.sp,
                )
            }
            tvs.forEach { tv ->
                SettingRow(
                    if (tv.isSamsung) Icons.Rounded.Tv else Icons.Rounded.CastConnected,
                    tv.name, if (playingOn == tv) "กำลังเล่นบนทีวีนี้" else tv.manufacturer.ifBlank { "DLNA Renderer" },
                    onClick = {
                        scope.launch {
                            DlnaCaster.play(tv, url, title)
                                .onSuccess { playingOn = tv; Toast.makeText(ctx, "ส่งไปที่ ${tv.name} แล้ว", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(ctx, "แคสต์ไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    },
                )
            }
            playingOn?.let { tv ->
                Spacer(Modifier.height(R.gapS))
                Row(horizontalArrangement = Arrangement.spacedBy(R.gapS)) {
                    GhostButton("หยุดชั่วคราว", Icons.Rounded.Pause, Modifier.weight(1f)) { scope.launch { DlnaCaster.pause(tv) } }
                    GhostButton("เล่นต่อ", Icons.Rounded.PlayArrow, Modifier.weight(1f)) { scope.launch { DlnaCaster.resume(tv) } }
                    GhostButton("หยุด", Icons.Rounded.Stop, Modifier.weight(1f)) { scope.launch { DlnaCaster.stop(tv); playingOn = null } }
                }
            }
            Spacer(Modifier.height(R.gap))
            GhostButton("Smart View / Screen Mirroring", Icons.Rounded.ScreenShare, Modifier.fillMaxWidth()) {
                runCatching { ctx.startActivity(Intent("android.settings.CAST_SETTINGS")) }
                    .onFailure { ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            }
        }
    }
}
