package com.phudy.redline.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phudy.redline.data.Prefs
import com.phudy.redline.data.VpnMode
import com.phudy.redline.net.NetTools
import com.phudy.redline.ui.*
import com.phudy.redline.ui.theme.R
import com.phudy.redline.vpn.VpnController
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

@Composable
fun ServersScreen() {
    val ctx = LocalContext.current
    val profiles by Prefs.profiles.collectAsState()
    val selected by Prefs.selectedProfile.collectAsState()
    val scope = rememberCoroutineScope()
    val pings = remember { mutableStateMapOf<String, Long?>() }
    var testing by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "server"
            Prefs.addProfile(name, text)
        }
    }

    fun pingAll() {
        testing = true
        scope.launch {
            profiles.map { p -> async { p.name to p.endpoint?.let { NetTools.hostLatency(it) } } }.awaitAll()
                .forEach { (n, ms) -> pings[n] = ms }
            testing = false
        }
    }
    LaunchedEffect(profiles.size) { if (profiles.isNotEmpty()) pingAll() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("เซิร์ฟเวอร์", "WireGuard • ${profiles.size} ตำแหน่ง")
        Row(Modifier.padding(horizontal = R.gap), horizontalArrangement = Arrangement.spacedBy(R.gapS)) {
            RedButton("นำเข้าไฟล์ .conf", Icons.Rounded.FileOpen, Modifier.weight(1f)) { picker.launch(arrayOf("*/*")) }
            GhostButton("วางข้อความ", Icons.Rounded.ContentPaste, Modifier.weight(1f)) { showPaste = true }
        }
        Spacer(Modifier.height(R.gapS))
        Row(Modifier.padding(horizontal = R.gap), horizontalArrangement = Arrangement.spacedBy(R.gapS)) {
            GhostButton(if (testing) "กำลังวัด…" else "วัดปิง", Icons.Rounded.NetworkPing, Modifier.weight(1f)) { pingAll() }
            GhostButton("เลือกเร็วสุดอัตโนมัติ", Icons.Rounded.AutoAwesome, Modifier.weight(1f)) {
                pings.filterValues { it != null }.minByOrNull { it.value!! }?.key?.let {
                    Prefs.selectProfile(it); Prefs.setMode(VpnMode.WIREGUARD); VpnController.restartIfOn(ctx)
                }
            }
        }
        Spacer(Modifier.height(R.gap))

        if (profiles.isEmpty()) {
            Card(Modifier.padding(horizontal = R.gap).fillMaxWidth()) {
                Icon(Icons.Rounded.Public, null, tint = R.Red, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(8.dp))
                Text("ยังไม่มีเซิร์ฟเวอร์", color = R.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "โหมด Secure VPN ใช้ไฟล์ WireGuard (.conf) จากผู้ให้บริการที่คุณใช้อยู่ " +
                        "(เช่น Mullvad, Proton VPN, Surfshark, NordVPN ส่วนใหญ่ให้ดาวน์โหลดได้) หรือเซิร์ฟเวอร์ของคุณเอง " +
                        "นำเข้าได้หลายประเทศ แล้วกด \"เลือกเร็วสุดอัตโนมัติ\"\n\n" +
                        "ถ้ายังไม่มี ใช้โหมด Shield + Turbo ได้เลย ไม่ต้องมีเซิร์ฟเวอร์",
                    color = R.Muted, fontSize = 13.sp,
                )
            }
        }

        LazyColumn(contentPadding = PaddingValues(horizontal = R.gap, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(R.gapS)) {
            items(profiles, key = { it.name }) { p ->
                val isSel = p.name == selected
                val ms = pings[p.name]
                Card(
                    Modifier.fillMaxWidth().then(if (isSel) Modifier.border(1.5.dp, R.Red, RoundedCornerShape(R.radius)) else Modifier),
                    onClick = { Prefs.selectProfile(p.name); Prefs.setMode(VpnMode.WIREGUARD); VpnController.restartIfOn(ctx) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(R.Surface2), contentAlignment = Alignment.Center) {
                            Text(flagFor(p.name), fontSize = 20.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = R.Text, fontWeight = FontWeight.Bold)
                            Text(p.endpoint ?: "ไม่มี Endpoint", color = R.Muted, fontSize = 12.sp, maxLines = 1)
                        }
                        PingBadge(ms)
                        IconButton(onClick = { Prefs.deleteProfile(p) }) { Icon(Icons.Rounded.DeleteOutline, "ลบ", tint = R.Muted) }
                    }
                }
            }
        }
    }

    if (showPaste) PasteDialog(onDismiss = { showPaste = false }) { name, text -> Prefs.addProfile(name, text); showPaste = false }
}

@Composable
private fun PingBadge(ms: Long?) {
    val color = when {
        ms == null -> R.Muted
        ms < 80 -> R.Ok
        ms < 180 -> R.Warn
        else -> R.Red
    }
    Text(
        ms?.let { "$it ms" } ?: "—", color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(R.Surface2).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = R.Surface,
        title = { Text("วาง WireGuard config", color = R.Text) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("ชื่อ (เช่น JP-Tokyo)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it }, label = { Text("[Interface] … [Peer] …") }, minLines = 6, modifier = Modifier.heightIn(max = 260.dp))
            }
        },
        confirmButton = { TextButton(enabled = text.contains("[Interface]"), onClick = { onSave(name.ifBlank { "server" }, text) }) { Text("บันทึก", color = R.Red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก", color = R.Muted) } },
    )
}

/** Guess a flag emoji from a 2-letter country prefix like "jp-tokyo" or "us_nyc". */
private fun flagFor(name: String): String {
    val cc = Regex("^([A-Za-z]{2})(?:[-_ ]|$)").find(name)?.groupValues?.get(1)?.uppercase() ?: return "🌐"
    return cc.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}
