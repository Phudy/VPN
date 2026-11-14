package com.phudy.redline.ui.screens

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.phudy.redline.EmbedPlayerActivity
import com.phudy.redline.data.Prefs
import com.phudy.redline.download.Downloader
import com.phudy.redline.explore.*
import com.phudy.redline.translate.SubTranslator
import com.phudy.redline.ui.*
import com.phudy.redline.ui.theme.R
import kotlinx.coroutines.launch

/** Unlock state for the 18+ section; cleared when the app goes to the background. */
object ExploreLock { var unlocked = false }

@Composable
fun ExploreScreen() {
    val adult by Prefs.adultConfirmed.collectAsState()
    val locked by Prefs.exploreLock.collectAsState()
    var unlocked by remember { mutableStateOf(ExploreLock.unlocked) }
    val ctx = LocalContext.current
    val auth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) { ExploreLock.unlocked = true; unlocked = true }
    }

    when {
        !adult -> AgeGate()
        locked && !unlocked -> LockedView {
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent("ปลดล็อก REDLINE 18+", "ยืนยันตัวตนด้วยลายนิ้วมือ / PIN")
            if (i != null) auth.launch(i) else { ExploreLock.unlocked = true; unlocked = true }
        }
        else -> ExploreContent()
    }
}

@Composable
private fun AgeGate() {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("18+", color = R.Red, fontSize = 64.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(R.gap))
        Text("หมวดนี้มีเนื้อหาสำหรับผู้ใหญ่", color = R.Text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "ค้นหาจาก API สาธารณะของเว็บผู้ใหญ่ที่ตรวจสอบอายุนักแสดง ระบบจะปฏิเสธคำค้นและซ่อนผลลัพธ์ที่เกี่ยวกับผู้เยาว์หรือการไม่ยินยอมเสมอ",
            color = R.Muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(24.dp))
        RedButton("ฉันอายุ 18 ปีขึ้นไป", Icons.Rounded.Verified, Modifier.fillMaxWidth()) { Prefs.setAdultConfirmed() }
    }
}

@Composable
private fun LockedView(onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Lock, null, tint = R.Red, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(R.gap))
        Text("ล็อกอยู่", color = R.Text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))
        RedButton("ปลดล็อก", Icons.Rounded.Fingerprint, Modifier.fillMaxWidth(), onClick = onUnlock)
    }
}

@Composable
private fun ExploreContent() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by Prefs.exploreSources.collectAsState()
    val locked by Prefs.exploreLock.collectAsState()
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(Sort.POPULAR) }
    val clips = remember { mutableStateListOf<Clip>() }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var refused by remember { mutableStateOf(false) }
    var thaiTitles by remember { mutableStateOf(false) }
    val thai = remember { mutableStateMapOf<String, String>() }
    var endReached by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Clip?>(null) }

    fun load(reset: Boolean) {
        if (loading) return
        if (!SafetyFilter.allowed(submitted)) { refused = true; clips.clear(); return }
        refused = false
        if (reset) { page = 1; clips.clear(); endReached = false }
        loading = true
        scope.launch {
            val r = Explore.search(submitted, page, sort, sources)
            errors = r.errors
            val fresh = r.clips.filter { c -> clips.none { it.id == c.id } }
            if (fresh.isEmpty()) endReached = true
            clips.addAll(fresh); page++
            loading = false
        }
    }
    LaunchedEffect(sort, sources) { load(true) }
    LaunchedEffect(thaiTitles, clips.size) {
        if (thaiTitles) clips.filter { it.id !in thai }.forEach { c ->
            launch { runCatching { thai[c.id] = SubTranslator.toThai(c.title) } }
        }
    }

    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= clips.size - 6 } }
    LaunchedEffect(nearEnd) { if (nearEnd && clips.isNotEmpty() && !endReached) load(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(R.gap, 12.dp, R.gap, 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("สำรวจ 18+", style = MaterialTheme.typography.headlineLarge, color = R.Text, modifier = Modifier.weight(1f))
            IconButton(onClick = { thaiTitles = !thaiTitles }) { Icon(Icons.Rounded.Translate, "แปลชื่อเป็นไทย", tint = if (thaiTitles) R.Red else R.Muted) }
            IconButton(onClick = { Prefs.setExploreLock(!locked) }) {
                Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, "ล็อกหมวดนี้", tint = if (locked) R.Red else R.Muted)
            }
        }
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = R.gap, vertical = 8.dp), singleLine = true,
            placeholder = { Text("ค้นหาจากทุกแหล่งในครั้งเดียว", color = R.Muted) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = R.Red) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; submitted = ""; load(true) }) { Icon(Icons.Rounded.Close, null, tint = R.Muted) } },
            shape = RoundedCornerShape(R.radiusS),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitted = query.trim(); load(true) }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = R.Red, unfocusedBorderColor = R.Stroke, focusedContainerColor = R.Surface, unfocusedContainerColor = R.Surface, cursorColor = R.Red),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = R.gap), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Sort.entries) { s -> Pill(s.label, sort == s) { sort = s } }
            item { Spacer(Modifier.width(8.dp)) }
            items(PROVIDERS) { p -> Pill(p.name, p.id in sources, outline = true) { Prefs.toggleSource(p.id) } }
        }
        if (errors.isNotEmpty()) {
            Text(
                "โหลดไม่ได้: " + errors.entries.joinToString { "${it.key} (${it.value})" } + " — ลองเปิด VPN",
                color = R.Warn, fontSize = 11.sp, modifier = Modifier.padding(horizontal = R.gap, vertical = 4.dp),
            )
        }
        if (refused) {
            Card(Modifier.padding(R.gap).fillMaxWidth()) {
                Text("ไม่สามารถค้นหาคำนี้ได้", color = R.Red, fontWeight = FontWeight.Bold)
                Text("คำค้นนี้เกี่ยวข้องกับผู้เยาว์หรือการไม่ยินยอม ระบบไม่อนุญาตเด็ดขาด", color = R.Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            GridCells.Adaptive(170.dp), state = grid,
            contentPadding = PaddingValues(R.gap, 4.dp, R.gap, 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(clips, key = { it.id }) { c ->
                ClipCard(c, if (thaiTitles) thai[c.id] else null, onClick = {
                    ctx.startActivity(
                        Intent(ctx, EmbedPlayerActivity::class.java)
                            .putExtra(EmbedPlayerActivity.EXTRA_EMBED, c.embedUrl)
                            .putExtra(EmbedPlayerActivity.EXTRA_PAGE, c.pageUrl)
                            .putExtra(EmbedPlayerActivity.EXTRA_TITLE, c.title)
                    )
                }, onMore = { menuFor = c })
            }
            if (loading) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = R.Red) }
            }
            if (!loading && clips.isEmpty() && !refused) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("ไม่พบผลลัพธ์", color = R.Muted, modifier = Modifier.padding(20.dp))
            }
        }
    }

    menuFor?.let { c ->
        AlertDialog(
            onDismissRequest = { menuFor = null }, containerColor = R.Surface,
            title = { Text(c.title, color = R.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 16.sp) },
            text = {
                Column {
                    SettingRow(Icons.Rounded.Share, "แชร์ลิงก์", c.source, onClick = { Downloader.shareLink(ctx, c.pageUrl, c.title); menuFor = null })
                    SettingRow(Icons.Rounded.Download, "ดาวน์โหลด", "เปิดคลิป กดเล่น แล้วกด \"ดาวน์โหลด\" ด้านล่าง", onClick = {
                        ctx.startActivity(Intent(ctx, EmbedPlayerActivity::class.java)
                            .putExtra(EmbedPlayerActivity.EXTRA_EMBED, c.embedUrl).putExtra(EmbedPlayerActivity.EXTRA_PAGE, c.pageUrl)
                            .putExtra(EmbedPlayerActivity.EXTRA_TITLE, c.title))
                        menuFor = null
                    })
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("ปิด", color = R.Red) } },
        )
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, outline: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = if (selected) (if (outline) R.Red else Color.White) else R.Muted,
        modifier = Modifier.clip(shape)
            .then(if (selected && !outline) Modifier.background(R.redGradient) else Modifier.background(R.Surface))
            .border(1.dp, if (selected && outline) R.Red else R.Stroke, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun ClipCard(c: Clip, thaiTitle: String?, onClick: () -> Unit, onMore: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(R.radiusS)).background(R.Surface2)) {
            AsyncImage(c.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
            Text(
                c.source, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(R.Red).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (c.durationSec > 0) Text(
                "%d:%02d".format(c.durationSec / 60, c.durationSec % 60), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Row(Modifier.padding(top = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(thaiTitle ?: c.title, color = R.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        if (c.views > 0) compact(c.views) + " วิว" else null,
                        if (c.rating > 0) "👍 ${c.rating.toInt()}%" else null,
                    ).joinToString(" • "),
                    color = R.Muted, fontSize = 11.sp,
                )
            }
            Icon(Icons.Rounded.MoreVert, "เพิ่มเติม", tint = R.Muted, modifier = Modifier.size(20.dp).clickable(onClick = onMore))
        }
    }
}

private fun compact(n: Long) = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.0fK".format(n / 1e3)
    else -> "$n"
}
