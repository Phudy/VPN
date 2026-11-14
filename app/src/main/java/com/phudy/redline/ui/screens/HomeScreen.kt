package com.phudy.redline.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phudy.redline.data.Prefs
import com.phudy.redline.data.VpnMode
import com.phudy.redline.net.NetTools
import com.phudy.redline.ui.*
import com.phudy.redline.ui.theme.R
import com.phudy.redline.vpn.ConnState
import com.phudy.redline.vpn.VpnHub
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(onToggle: (Boolean) -> Unit, openServers: () -> Unit) {
    val state by VpnHub.state.collectAsState()
    val error by VpnHub.error.collectAsState()
    val mode by Prefs.mode.collectAsState()
    val profile by Prefs.selectedProfile.collectAsState()
    val dns by Prefs.dns.collectAsState()
    val adBlock by Prefs.adBlock.collectAsState()
    val blocked by VpnHub.blocked.collectAsState()
    val queries by VpnHub.dnsQueries.collectAsState()
    val hits by VpnHub.cacheHits.collectAsState()
    val avg by VpnHub.avgDnsMs.collectAsState()
    val rx by VpnHub.rxBytes.collectAsState()
    val tx by VpnHub.txBytes.collectAsState()
    val since by VpnHub.connectedAt.collectAsState()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) { while (state == ConnState.ON) { now = System.currentTimeMillis(); delay(1000) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("REDLINE", "VPN • Shield • Turbo") { ProBadge() }

        // Mode switch
        Row(
            Modifier.padding(horizontal = R.gap).fillMaxWidth().clip(RoundedCornerShape(R.radiusS)).background(R.Surface)
                .border(1.dp, R.Stroke, RoundedCornerShape(R.radiusS)).padding(4.dp),
        ) {
            ModeChip("Shield + Turbo", "บล็อกโฆษณา • DNS เร็ว", mode == VpnMode.SHIELD, Modifier.weight(1f)) { Prefs.setMode(VpnMode.SHIELD) }
            ModeChip("Secure VPN", "WireGuard เข้ารหัส", mode == VpnMode.WIREGUARD, Modifier.weight(1f)) { Prefs.setMode(VpnMode.WIREGUARD) }
        }

        Spacer(Modifier.height(28.dp))
        PowerButton(state) { onToggle(state == ConnState.OFF || state == ConnState.ERROR) }
        Spacer(Modifier.height(20.dp))

        val (label, color) = when (state) {
            ConnState.ON -> "ปลอดภัย • เชื่อมต่อแล้ว" to R.Ok
            ConnState.CONNECTING -> "กำลังเชื่อมต่อ…" to R.Warn
            ConnState.ERROR -> "เชื่อมต่อไม่สำเร็จ" to R.Red
            ConnState.OFF -> "ยังไม่ได้ป้องกัน" to R.Muted
        }
        Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        if (state == ConnState.ON) {
            Text(
                uptime(now - since), color = R.Muted, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
            )
        }
        if (state == ConnState.ERROR && error != null) {
            Text(error!!, color = R.Red, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(R.gap))
        }

        Spacer(Modifier.height(24.dp))

        // Current location / route
        Card(Modifier.padding(horizontal = R.gap).fillMaxWidth(), onClick = openServers) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(R.Surface2), contentAlignment = Alignment.Center) {
                    Icon(if (mode == VpnMode.SHIELD) Icons.Rounded.Bolt else Icons.Rounded.Public, null, tint = R.Red)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (mode == VpnMode.SHIELD) "Turbo DNS • ${dns.name}" else (profile ?: "เลือกเซิร์ฟเวอร์"),
                        color = R.Text, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (mode == VpnMode.SHIELD) "ทำงานบนเครื่อง ไม่ผ่านเซิร์ฟเวอร์ใคร" else "อุโมงค์เข้ารหัส ChaCha20 ทั้งเครื่อง",
                        color = R.Muted, fontSize = 12.sp,
                    )
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = R.Muted)
            }
        }

        Spacer(Modifier.height(R.gap))
        SectionLabel("สถิติเซสชันนี้", Modifier.padding(horizontal = R.gap))
        Row(Modifier.padding(horizontal = R.gap), horizontalArrangement = Arrangement.spacedBy(R.gapS)) {
            if (mode == VpnMode.SHIELD) {
                StatTile("โฆษณาที่บล็อก", "$blocked", Icons.Rounded.Block, Modifier.weight(1f))
                StatTile("DNS เฉลี่ย", if (avg > 0) "${avg.toInt()} ms" else "—", Icons.Rounded.Speed, Modifier.weight(1f))
                StatTile("แคชเร่งความเร็ว", if (queries > 0) "${hits * 100 / queries}%" else "—", Icons.Rounded.FlashOn, Modifier.weight(1f))
            } else {
                StatTile("ดาวน์โหลด", NetTools.formatBytes(rx), Icons.Rounded.ArrowDownward, Modifier.weight(1f))
                StatTile("อัปโหลด", NetTools.formatBytes(tx), Icons.Rounded.ArrowUpward, Modifier.weight(1f))
                StatTile("Ad-block", if (adBlock) "เปิด" else "ปิด", Icons.Rounded.Block, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(R.gap))

        Card(Modifier.padding(horizontal = R.gap).fillMaxWidth()) {
            SettingRow(
                Icons.Rounded.Block, "บล็อกโฆษณา & ตัวติดตาม",
                "ตัดโฆษณา ป๊อปอัป มัลแวร์ ทั้งในแอปและเว็บ", checked = adBlock,
                onCheck = { Prefs.setAdBlock(it) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModeChip(title: String, sub: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) R.Surface2 else Color.Transparent, label = "chip")
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(bg)
            .then(if (selected) Modifier.border(1.dp, R.Red.copy(alpha = .6f), RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 12.dp),
    ) {
        Text(title, color = if (selected) R.Text else R.Muted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(sub, color = R.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ColumnScope.PowerButton(state: ConnState, onClick: () -> Unit) {
    val on = state == ConnState.ON
    val busy = state == ConnState.CONNECTING
    val inf = rememberInfiniteTransition(label = "pwr")
    val pulse by inf.animateFloat(1f, 1.12f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "s")
    val ring by animateColorAsState(if (on) R.Red else if (busy) R.Warn else R.Stroke, label = "ring")

    Box(Modifier.align(Alignment.CenterHorizontally).size(230.dp), contentAlignment = Alignment.Center) {
        // Glow
        Canvas(Modifier.fillMaxSize().scale(if (on) pulse else 1f)) {
            drawCircle(
                Brush.radialGradient(
                    listOf(if (on) R.RedGlow else Color(0x22FF1E3C), Color.Transparent),
                    center = center, radius = size.minDimension / 2,
                ),
            )
        }
        // Rings
        Canvas(Modifier.size(190.dp)) {
            drawCircle(R.Surface2, style = Stroke(10.dp.toPx()))
            if (busy) {
                drawArc(R.Warn, spin, 90f, false, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
            } else {
                drawArc(ring, -90f, 360f, false, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
            }
            // tick marks
            for (i in 0 until 60) {
                val a = Math.toRadians(i * 6.0)
                val r1 = size.minDimension / 2 + 14.dp.toPx()
                val r2 = r1 + if (i % 5 == 0) 7.dp.toPx() else 3.dp.toPx()
                val c = center
                drawLine(
                    if (on) R.Red.copy(alpha = .5f) else R.Stroke,
                    Offset(c.x + (r1 * Math.cos(a)).toFloat(), c.y + (r1 * Math.sin(a)).toFloat()),
                    Offset(c.x + (r2 * Math.cos(a)).toFloat(), c.y + (r2 * Math.sin(a)).toFloat()),
                    strokeWidth = 2f,
                )
            }
        }
        Box(
            Modifier.size(150.dp).clip(CircleShape)
                .background(if (on) R.redGradient else Brush.linearGradient(listOf(R.Surface2, R.Surface)))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.PowerSettingsNew, null, tint = if (on) Color.White else R.Red, modifier = Modifier.size(56.dp))
                Text(if (on) "ON" else "OFF", color = if (on) Color.White else R.Muted, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            }
        }
    }
}

private fun uptime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}
