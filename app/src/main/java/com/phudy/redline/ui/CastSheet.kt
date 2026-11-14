package com.phudy.redline.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phudy.redline.cast.CastProxy
import com.phudy.redline.cast.DlnaCaster
import com.phudy.redline.ui.theme.R
import kotlinx.coroutines.launch

/** Bottom sheet: find Samsung / DLNA TVs on Wi-Fi and send the video to one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastSheet(url: String, title: String, headers: Map<String, String> = emptyMap(), onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sp = remember { ctx.getSharedPreferences("redline", Context.MODE_PRIVATE) }
    var tvs by remember { mutableStateOf<List<DlnaCaster.Renderer>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var playingOn by remember { mutableStateOf<DlnaCaster.Renderer?>(null) }
    var sending by remember { mutableStateOf<DlnaCaster.Renderer?>(null) }
    var ip by remember { mutableStateOf(sp.getString("tv_ip", "") ?: "") }
    var showHelp by remember { mutableStateOf(false) }
    val onWifi = remember { CastProxy.lanAddress() != null }

    fun add(r: DlnaCaster.Renderer?) { if (r != null && tvs.none { it.controlUrl == r.controlUrl }) tvs = (listOf(r) + tvs).sortedByDescending { it.isSamsung } }

    fun scan() {
        scanning = true
        scope.launch {
            tvs = DlnaCaster.discover(ctx).sortedByDescending { it.isSamsung }
            // Also try the TV remembered from last time directly (works even when the router blocks discovery)
            sp.getString("tv_ip", null)?.takeIf { it.isNotBlank() }?.let { add(DlnaCaster.probe(it)) }
            scanning = false
        }
    }
    LaunchedEffect(Unit) { scan() }

    fun send(tv: DlnaCaster.Renderer) {
        sending = tv
        scope.launch {
            DlnaCaster.play(ctx, tv, url, title, headers)
                .onSuccess {
                    playingOn = tv
                    runCatching { sp.edit().putString("tv_ip", java.net.URL(tv.controlUrl).host).apply() }
                    Toast.makeText(ctx, "ส่งไปที่ ${tv.name} แล้ว — เปิดแอปนี้ค้างไว้ระหว่างดู", Toast.LENGTH_LONG).show()
                }
                .onFailure { Toast.makeText(ctx, "แคสต์ไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show(); showHelp = true }
            sending = null
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = R.Surface) {
        Column(Modifier.padding(horizontal = R.gap).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Cast, null, tint = R.Red)
                Spacer(Modifier.width(10.dp))
                Text("แคสต์ไปทีวี", color = R.Text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(Modifier.size(20.dp), color = R.Red, strokeWidth = 2.dp)
                else IconButton(onClick = ::scan) { Icon(Icons.Rounded.Refresh, "ค้นหาใหม่", tint = R.Muted) }
            }
            Text("Samsung Smart TV และทีวี DLNA ใน Wi-Fi เดียวกัน • วิดีโอส่งผ่านมือถือ จึงเล่นได้แม้เว็บป้องกันลิงก์", color = R.Muted, fontSize = 12.sp)
            if (!onWifi) Text("⚠ มือถือยังไม่ได้ต่อ Wi-Fi — ต้องอยู่วงเดียวกับทีวี", color = R.Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(R.gap))

            tvs.forEach { tv ->
                SettingRow(
                    if (tv.isSamsung) Icons.Rounded.Tv else Icons.Rounded.CastConnected,
                    tv.name,
                    when { sending == tv -> "กำลังส่ง…"; playingOn == tv -> "กำลังเล่นบนทีวีนี้"; else -> tv.manufacturer.ifBlank { "DLNA" } + " • " + runCatching { java.net.URL(tv.controlUrl).host }.getOrDefault("") },
                    onClick = { if (sending == null) send(tv) },
                )
            }
            if (!scanning && tvs.isEmpty()) {
                Text("ยังไม่พบทีวี — ลองใส่ IP ของทีวีด้านล่าง", color = R.Muted, fontSize = 13.sp)
            }

            // Manual IP (router blocks discovery / VPN etc.)
            Spacer(Modifier.height(R.gapS))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    ip, { ip = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.weight(1f), singleLine = true,
                    label = { Text("IP ทีวี เช่น 192.168.1.50") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(R.radiusS),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = R.Red, unfocusedBorderColor = R.Stroke, cursorColor = R.Red),
                )
                Spacer(Modifier.width(8.dp))
                GhostButton("เชื่อม", Icons.Rounded.Link) {
                    scope.launch {
                        val r = DlnaCaster.probe(ip)
                        if (r == null) { Toast.makeText(ctx, "ติดต่อทีวีที่ $ip ไม่ได้", Toast.LENGTH_LONG).show(); showHelp = true }
                        else { add(r); sp.edit().putString("tv_ip", ip).apply(); send(r) }
                    }
                }
            }

            playingOn?.let { tv ->
                Spacer(Modifier.height(R.gap))
                Row(horizontalArrangement = Arrangement.spacedBy(R.gapS)) {
                    GhostButton("พัก", Icons.Rounded.Pause, Modifier.weight(1f)) { scope.launch { DlnaCaster.pause(tv) } }
                    GhostButton("เล่น", Icons.Rounded.PlayArrow, Modifier.weight(1f)) { scope.launch { DlnaCaster.resume(tv) } }
                    GhostButton("หยุด", Icons.Rounded.Stop, Modifier.weight(1f)) { scope.launch { DlnaCaster.stop(tv); playingOn = null } }
                }
            }

            Spacer(Modifier.height(R.gap))
            GhostButton("Smart View (สะท้อนหน้าจอทั้งเครื่อง)", Icons.Rounded.ScreenShare, Modifier.fillMaxWidth()) { openSmartView(ctx) }
            Spacer(Modifier.height(R.gapS))
            TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) "ซ่อนวิธีแก้ปัญหา" else "แคสต์ไม่ได้? ดูวิธีแก้", color = R.Red) }
            if (showHelp) SamsungHelp()
        }
    }
}

@Composable
private fun SamsungHelp() {
    Card(Modifier.fillMaxWidth()) {
        Text("ตั้งค่าบน Samsung TV (รุ่นปี 2023-2025)", color = R.Text, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        listOf(
            "1. มือถือกับทีวีต้องต่อ Wi-Fi ตัวเดียวกัน (ถ้าเราเตอร์มี 2.4G/5G แยกชื่อ ให้ต่อชื่อเดียวกัน) และปิดโหมด \"Guest / AP Isolation\" ของเราเตอร์",
            "2. ทีวี: การตั้งค่า › การเชื่อมต่อ › ตัวจัดการอุปกรณ์ภายนอก › ตัวจัดการการเชื่อมต่ออุปกรณ์ › การแจ้งเตือนการเข้าถึง = \"ครั้งแรกเท่านั้น\" แล้วกด \"อนุญาต\" เมื่อทีวีถามครั้งแรก",
            "3. ในหน้าเดียวกัน › รายการอุปกรณ์: ถ้ามือถือถูก \"ปฏิเสธ\" ให้ลบออกหรือเปลี่ยนเป็นอนุญาต",
            "4. หา IP ทีวี: การตั้งค่า › ทั่วไปและความเป็นส่วนตัว (หรือ การเชื่อมต่อ) › เครือข่าย › สถานะเครือข่าย › การตั้งค่า IP แล้วใส่ในช่อง IP ด้านบน",
            "5. ถ้าใช้โหมด Secure VPN: ต้องเปิด \"เข้าถึงอุปกรณ์ในบ้าน (LAN)\" ในแท็บเครื่องมือ (เปิดไว้แล้วโดยค่าเริ่มต้น)",
            "6. ทีวีของคุณมีบันทึกว่าเครือข่ายหลุด/ภาพต่ำจากเน็ต — ถ้าภาพกระตุก ลองย้ายเราเตอร์ใกล้ทีวี ใช้คลื่น 5GHz หรือสาย LAN",
            "7. ยังไม่ได้: ใช้ปุ่ม Smart View ด้านบน (สะท้อนจอทั้งเครื่อง) — มือถือ Samsung อยู่ในแถบด่วน (ปัดลงจากบน)",
        ).forEach { Text(it, color = R.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
    }
}

/** Opens screen mirroring: Samsung Smart View first, then Android's cast settings. */
fun openSmartView(ctx: Context) {
    val attempts = listOf(
        Intent().setClassName("com.samsung.android.smartmirroring", "com.samsung.android.smartmirroring.CaptureActivity"),
        Intent("com.samsung.android.smartmirroring.SMART_VIEW"),
        ctx.packageManager.getLaunchIntentForPackage("com.samsung.android.smartmirroring"),
        Intent("android.settings.CAST_SETTINGS"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
    )
    for (i in attempts) {
        if (i == null) continue
        if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
            if (i.action == Settings.ACTION_WIRELESS_SETTINGS || i.action == "android.settings.CAST_SETTINGS")
                Toast.makeText(ctx, "มือถือ Samsung: ปัดแถบด่วนลงแล้วกด \"Smart View\"", Toast.LENGTH_LONG).show()
            return
        }
    }
}
