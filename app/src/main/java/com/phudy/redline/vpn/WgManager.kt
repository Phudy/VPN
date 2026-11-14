package com.phudy.redline.vpn

import android.content.Context
import com.phudy.redline.data.Prefs
import com.phudy.redline.data.WgProfile
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.*

/** Full-device encrypted tunnel using the official WireGuard userspace backend. */
object WgManager {
    private lateinit var backend: GoBackend
    private var statsJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val tunnel = object : Tunnel {
        override fun getName() = "redline"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && VpnHub.state.value == ConnState.ON) {
                VpnHub.state.value = ConnState.OFF
            }
        }
    }

    fun init(ctx: Context) { backend = GoBackend(ctx.applicationContext) }

    /** Builds the final config: user profile + ad-block DNS + split tunneling. */
    fun buildConfig(profile: WgProfile): Config {
        val lines = profile.file.readLines().toMutableList()
        val ifaceIdx = lines.indexOfFirst { it.trim().equals("[Interface]", true) }
        require(ifaceIdx >= 0) { "ไฟล์ไม่ใช่ WireGuard config ([Interface] ไม่พบ)" }
        val cleaned = lines.filterNot {
            val k = it.substringBefore('=').trim().lowercase()
            (Prefs.adBlock.value && k == "dns") || k == "excludedapplications" || k == "includedapplications"
        }.toMutableList()
        val at = cleaned.indexOfFirst { it.trim().equals("[Interface]", true) } + 1
        val extra = mutableListOf<String>()
        if (Prefs.adBlock.value) extra += "DNS = 94.140.14.14, 94.140.15.15" // AdGuard: blocks ads inside the tunnel
        if (Prefs.excludedApps.value.isNotEmpty()) extra += "ExcludedApplications = " + Prefs.excludedApps.value.joinToString(", ")
        cleaned.addAll(at, extra)
        return Config.parse(cleaned.joinToString("\n").byteInputStream())
    }

    fun connect(profile: WgProfile) {
        VpnHub.state.value = ConnState.CONNECTING
        VpnHub.error.value = null
        scope.launch {
            try {
                backend.setState(tunnel, Tunnel.State.UP, buildConfig(profile))
                VpnHub.resetSession()
                VpnHub.state.value = ConnState.ON
                statsJob?.cancel()
                statsJob = scope.launch {
                    while (isActive) {
                        runCatching {
                            val s = backend.getStatistics(tunnel)
                            VpnHub.rxBytes.value = s.totalRx(); VpnHub.txBytes.value = s.totalTx()
                        }
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                VpnHub.error.value = e.message ?: e.javaClass.simpleName
                VpnHub.state.value = ConnState.ERROR
            }
        }
    }

    fun disconnect() {
        statsJob?.cancel()
        scope.launch {
            runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            VpnHub.state.value = ConnState.OFF
        }
    }

    fun isUp(): Boolean = runCatching { backend.getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)
}
