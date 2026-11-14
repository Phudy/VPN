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

    /** All public IPv4 space, i.e. 0.0.0.0/0 minus 10/8, 172.16/12, 192.168/16, 169.254/16, multicast. */
    private const val PUBLIC_V4 = "0.0.0.0/5, 8.0.0.0/7, 11.0.0.0/8, 12.0.0.0/6, 16.0.0.0/4, 32.0.0.0/3, 64.0.0.0/2, " +
        "128.0.0.0/3, 160.0.0.0/5, 168.0.0.0/6, 172.0.0.0/12, 172.32.0.0/11, 172.64.0.0/10, 172.128.0.0/9, " +
        "173.0.0.0/8, 174.0.0.0/7, 176.0.0.0/4, 192.0.0.0/9, 192.128.0.0/11, 192.160.0.0/13, 192.169.0.0/16, " +
        "192.170.0.0/15, 192.172.0.0/14, 192.176.0.0/12, 192.192.0.0/10, 193.0.0.0/8, 194.0.0.0/7, 196.0.0.0/6, " +
        "200.0.0.0/5, 208.0.0.0/4"

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
        // Keep the home network reachable (Smart TV casting, printers, router) while the
        // internet goes through the tunnel: the same list WireGuard's "Exclude private IPs" uses.
        if (Prefs.allowLan.value) {
            for (i in cleaned.indices) {
                val l = cleaned[i]
                if (l.substringBefore('=').trim().equals("AllowedIPs", true) && l.contains("0.0.0.0/0")) {
                    cleaned[i] = l.replace("0.0.0.0/0", PUBLIC_V4)
                }
            }
        }
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
