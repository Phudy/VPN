package com.phudy.redline

import android.Manifest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phudy.redline.ui.screens.*
import com.phudy.redline.ui.theme.R
import com.phudy.redline.ui.theme.RedlineTheme
import com.phudy.redline.vpn.VpnController

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("หน้าหลัก", Icons.Rounded.Shield),
    SERVERS("เซิร์ฟเวอร์", Icons.Rounded.Public),
    EXPLORE("สำรวจ 18+", Icons.Rounded.Whatshot),
    BROWSER("เบราว์เซอร์", Icons.Rounded.TravelExplore),
    MEDIA("มีเดีย", Icons.Rounded.PlayCircle),
    TOOLS("เครื่องมือ", Icons.Rounded.Tune),
}

class MainActivity : ComponentActivity() {

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) startVpn() else toast("ต้องอนุญาต VPN ก่อนจึงจะเชื่อมต่อได้")
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            RedlineTheme {
                var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
                Scaffold(
                    containerColor = R.Bg,
                    bottomBar = {
                        NavigationBar(containerColor = R.Surface, tonalElevation = 0.dp) {
                            Tab.entries.filter { it != Tab.SERVERS }.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t || (t == Tab.HOME && tab == Tab.SERVERS), onClick = { tab = t },
                                    icon = { Icon(t.icon, t.label) },
                                    label = { Text(t.label, fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = R.Red, selectedTextColor = R.Red,
                                        unselectedIconColor = R.Muted, unselectedTextColor = R.Muted,
                                        indicatorColor = R.Surface2,
                                    ),
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().background(R.Bg).padding(pad)) {
                        when (tab) {
                            Tab.HOME -> HomeScreen(onToggle = ::toggleVpn, openServers = { tab = Tab.SERVERS })
                            Tab.SERVERS -> {
                                androidx.activity.compose.BackHandler { tab = Tab.HOME }
                                ServersScreen()
                            }
                            Tab.EXPLORE -> ExploreScreen()
                            Tab.BROWSER -> BrowserScreen()
                            Tab.MEDIA -> MediaScreen()
                            Tab.TOOLS -> ToolsScreen()
                        }
                    }
                }
            }
        }
    }

    override fun onStop() { super.onStop(); if (!isChangingConfigurations) ExploreLock.unlocked = false }

    private fun toggleVpn(on: Boolean) {
        if (!on) { VpnController.disconnect(this); return }
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else startVpn()
    }

    private fun startVpn() { VpnController.connect(this)?.let(::toast) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

