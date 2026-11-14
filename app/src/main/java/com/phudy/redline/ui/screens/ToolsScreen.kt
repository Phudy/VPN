package com.phudy.redline.ui.screens

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phudy.redline.data.DNS_SERVERS
import com.phudy.redline.data.Prefs
import com.phudy.redline.net.NetTools
import com.phudy.redline.ui.*
import com.phudy.redline.ui.theme.R
import com.phudy.redline.vpn.Blocklist
import com.phudy.redline.speech.SPEECH_MODELS
import com.phudy.redline.speech.SpeechModels
import com.phudy.redline.speech.WhisperNative
import com.phudy.redline.vpn.VpnController
import com.phudy.redline.vpn.VpnHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun ToolsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showApps by remember { mutableStateOf(false) }
    var showBlocked by remember { mutableStateOf(false) }
    val listSize by Blocklist.size.collectAsState()
    val listDate by Blocklist.updatedAt.collectAsState()
    var updating by remember { mutableStateOf(false) }
    val excluded by Prefs.excludedApps.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("เครื่องมือ", "ทุกฟีเจอร์ปลดล็อกแล้ว") { ProBadge() }
        SpeedTestCard()
        Spacer(Modifier.height(R.gap))
        TurboDnsCard()
        Spacer(Modifier.height(R.gap))
        SpeechModelCard()
        Spacer(Modifier.height(R.gap))

        Card(Modifier.padding(horizontal = R.gap).fillMaxWidth()) {
            SectionLabel("ความปลอดภัย")
            SettingRow(
                Icons.Rounded.GppGood, "Kill Switch + Always-on",
                "ตัดเน็ตทันทีถ้า VPN หลุด — เปิด \"Always-on VPN\" และ \"Block connections without VPN\" ให้ REDLINE",
                onClick = { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) },
                trailing = { Icon(Icons.Rounded.OpenInNew, null, tint = R.Muted) },
            )
            val lan by Prefs.allowLan.collectAsState()
            SettingRow(
                Icons.Rounded.Router, "เข้าถึงอุปกรณ์ในบ้าน (LAN)",
                "ให้แคสต์ทีวี / เครื่องพิมพ์ใช้ได้ขณะเปิด Secure VPN", checked = lan,
                onCheck = { Prefs.setAllowLan(it); VpnController.restartIfOn(ctx) },
            )
            SettingRow(
                Icons.Rounded.CallSplit, "Split Tunneling",
                if (excluded.isEmpty()) "ทุกแอปผ่าน REDLINE" else "${excluded.size} แอปไม่ผ่าน VPN",
                onClick = { showApps = true }, trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = R.Muted) },
            )
            SettingRow(
                Icons.Rounded.FilterList, "รายการบล็อก ${"%,d".format(listSize)} โดเมน",
                if (listDate > 0) "อัปเดต " + DateFormat.getDateInstance().format(Date(listDate)) else "ใช้รายการพื้นฐาน — กดเพื่ออัปเดตเป็นชุดเต็ม ~150,000 โดเมน",
                onClick = {
                    if (!updating) {
                        updating = true
                        scope.launch {
                            Blocklist.update(ctx).onSuccess { Toast.makeText(ctx, "อัปเดตแล้ว ${"%,d".format(it)} โดเมน", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(ctx, "อัปเดตไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
                            updating = false
                        }
                    }
                },
                trailing = { if (updating) CircularProgressIndicator(Modifier.size(20.dp), color = R.Red, strokeWidth = 2.dp) else Icon(Icons.Rounded.Sync, null, tint = R.Muted) },
            )
            SettingRow(Icons.Rounded.History, "บันทึกการบล็อกล่าสุด", "ดูว่าโดเมนไหนถูกบล็อก", onClick = { showBlocked = true })
            SettingRow(
                Icons.Rounded.Dns, "Private DNS ของ Android",
                "ถ้าตั้งเป็นชื่อโฮสต์ (เช่น dns.google) Shield จะบล็อกโฆษณาไม่ได้ — ตั้งเป็น \"อัตโนมัติ\" หรือ \"ปิด\"",
                onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } },
            )
        }
        Spacer(Modifier.height(R.gap))
        Card(Modifier.padding(horizontal = R.gap).fillMaxWidth()) {
            SectionLabel("เกี่ยวกับ")
            Text("REDLINE VPN 1.0 • ไม่มีโฆษณา ไม่เก็บ log ไม่ส่งข้อมูลออกนอกเครื่อง", color = R.Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showApps) AppPicker(excluded, onToggle = { Prefs.toggleExcluded(it) }) { showApps = false; VpnController.restartIfOn(ctx) }
    if (showBlocked) BlockedLog { showBlocked = false }
}

@Composable
private fun SpeedTestCard() {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }
    var live by remember { mutableDoubleStateOf(0.0) }
    var result by remember { mutableStateOf<NetTools.SpeedResult?>(null) }

    Card(Modifier.padding(horizontal = R.gap).fillMaxWidth()) {
        SectionLabel("Speed Test")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
                val frac = (live / 300.0).coerceIn(0.0, 1.0).toFloat()
                Canvas(Modifier.fillMaxSize()) {
                    drawArc(R.Surface2, 135f, 270f, false, style = Stroke(12.dp.toPx(), cap = StrokeCap.Round))
                    drawArc(R.redGradient, 135f, 270f * frac, false, style = Stroke(12.dp.toPx(), cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%.0f".format(live), color = R.Text, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text("Mbps", color = R.Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(R.gap))
            Column(Modifier.weight(1f)) {
                Metric("ปิง", result?.let { "${it.pingMs} ms" } ?: "—")
                Metric("ดาวน์โหลด", result?.let { "%.1f Mbps".format(it.downMbps) } ?: if (phase == "down") "%.1f…".format(live) else "—")
                Metric("อัปโหลด", result?.let { "%.1f Mbps".format(it.upMbps) } ?: if (phase == "up") "%.1f…".format(live) else "—")
            }
        }
        Spacer(Modifier.height(R.gapS))
        RedButton(if (running) "กำลังทดสอบ…" else "เริ่มทดสอบความเร็ว", Icons.Rounded.Speed, Modifier.fillMaxWidth(), enabled = !running) {
            running = true; result = null; live = 0.0
            scope.launch {
                val r = NetTools.speedTest { p, v -> phase = p; live = v }
                result = r; live = r.downMbps; phase = ""; running = false
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = R.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = R.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun TurboDnsCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by Prefs.dns.collectAsState()
    val lat = remember { mutableStateMapOf<String, Long?>() }
    var testing by remember { mutableStateOf(false) }

    fun bench(autoPick: Boolean) {
        testing = true
        scope.launch {
            // three rounds, keep the best of each resolver
            val res = DNS_SERVERS.map { d ->
                async { d.id to (1..3).mapNotNull { NetTools.dnsLatency(d.ip) }.minOrNull() }
            }.awaitAll()
            res.forEach { (id, ms) -> lat[id] = ms }
            if (autoPick) res.filter { it.second != null }.minByOrNull { it.second!! }?.let { best ->
                DNS_SERVERS.first { it.id == best.first }.let { Prefs.setDns(it); VpnController.restartIfOn(ctx) }
            }
            testing = false
        }
    }

    Card(Modifier.padding(horizontal = R.gap).fillMaxWidth()) {
        SectionLabel("Turbo DNS • ตัวเร่งการเชื่อมต่อ")
        Text("เลือก DNS ที่ตอบเร็วที่สุดจากตำแหน่งของคุณ + แคชบนเครื่อง ทำให้เว็บและสตรีมเริ่มเร็วขึ้น", color = R.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(R.gapS))
        DNS_SERVERS.forEach { d ->
            val ms = lat[d.id]
            SettingRow(
                if (d.id == current.id) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                d.name, "${d.ip} • ${d.note}",
                onClick = { Prefs.setDns(d); VpnController.restartIfOn(ctx) },
                trailing = {
                    Text(
                        if (d.id in lat) ms?.let { "$it ms" } ?: "timeout" else "",
                        color = when { ms == null -> R.Muted; ms < 30 -> R.Ok; ms < 80 -> R.Warn; else -> R.Red },
                        fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    )
                },
            )
        }
        Spacer(Modifier.height(R.gapS))
        RedButton(if (testing) "กำลังวัด…" else "Turbo: เลือกเร็วสุดอัตโนมัติ", Icons.Rounded.Bolt, Modifier.fillMaxWidth(), enabled = !testing) { bench(true) }
    }
}

private data class AppItem(val pkg: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPicker(excluded: Set<String>, onToggle: (String) -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppItem>?>(null) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledApplications(0)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
                .filter { it.packageName != ctx.packageName }
                .map { AppItem(it.packageName, pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
        }
    }
    ModalBottomSheet(onDismissRequest = onDone, containerColor = R.Surface) {
        Column(Modifier.padding(horizontal = R.gap)) {
            Text("Split Tunneling", color = R.Text, style = MaterialTheme.typography.titleLarge)
            Text("เลือกแอปที่ไม่ต้องผ่าน VPN (เช่น แอปธนาคาร เกมออนไลน์)", color = R.Muted, fontSize = 12.sp)
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true, placeholder = { Text("ค้นหาแอป") })
            if (apps == null) CircularProgressIndicator(color = R.Red)
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(apps.orEmpty().filter { q.isBlank() || it.label.contains(q, true) }, key = { it.pkg }) { a ->
                    SettingRow(Icons.Rounded.Android, a.label, a.pkg, checked = a.pkg in excluded, onCheck = { onToggle(a.pkg) })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockedLog(onDone: () -> Unit) {
    val recent by VpnHub.recentBlocked.collectAsState()
    ModalBottomSheet(onDismissRequest = onDone, containerColor = R.Surface) {
        Column(Modifier.padding(horizontal = R.gap).padding(bottom = 32.dp)) {
            Text("บล็อกล่าสุด", color = R.Text, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(R.gapS))
            if (recent.isEmpty()) Text("ยังไม่มี — เปิด Shield แล้วใช้งานแอป/เว็บตามปกติ", color = R.Muted)
            recent.forEach { Text("⛔  $it", color = R.Text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
        }
    }
}

@Composable
private fun SpeechModelCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed by SpeechModels.installed.collectAsState()
    val selected by SpeechModels.selected.collectAsState()
    val dl by SpeechModels.downloading.collectAsState()
    val prog by SpeechModels.progress.collectAsState()
    Card(Modifier.padding(horizontal = R.gap).fillMaxWidth()) {
        SectionLabel("แปลเสียงเป็นไทย • whisper.cpp")
        Text(
            if (WhisperNative.loaded) "ฟังเสียงในคลิปแล้วแปลเป็นซับไทย 99 ภาษา ทำงานบนเครื่อง (${WhisperNative.variant}) — เปิดในเพลเยอร์ที่ปุ่ม 🗣"
            else "อุปกรณ์นี้ไม่รองรับ (ต้องเป็น ARM 64-bit)",
            color = R.Muted, fontSize = 12.sp,
        )
        Spacer(Modifier.height(R.gapS))
        SPEECH_MODELS.forEach { m ->
            val has = m.id in installed
            SettingRow(
                if (selected == m.id) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                "${m.label} • ${m.sizeMb} MB", m.note,
                onClick = { SpeechModels.select(m.id) },
                trailing = {
                    when {
                        dl == m.id -> Text("${(prog * 100).toInt()}%", color = R.Red, fontWeight = FontWeight.Bold)
                        has -> IconButton(onClick = { SpeechModels.delete(m.id) }) { Icon(Icons.Rounded.DeleteOutline, "ลบ", tint = R.Muted) }
                        else -> IconButton(enabled = dl == null, onClick = {
                            scope.launch {
                                SpeechModels.select(m.id)
                                SpeechModels.download(m.id).onFailure { Toast.makeText(ctx, "ดาวน์โหลดไม่สำเร็จ: ${it.message}", Toast.LENGTH_LONG).show() }
                            }
                        }) { Icon(Icons.Rounded.Download, "ดาวน์โหลด", tint = R.Red) }
                    }
                },
            )
        }
    }
}
