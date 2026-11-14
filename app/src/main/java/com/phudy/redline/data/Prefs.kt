package com.phudy.redline.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Upstream DNS resolvers the "Turbo DNS" picker can choose from. */
data class DnsServer(val id: String, val name: String, val ip: String, val note: String)

val DNS_SERVERS = listOf(
    DnsServer("cloudflare", "Cloudflare", "1.1.1.1", "เร็วสุดในหลายพื้นที่"),
    DnsServer("google", "Google", "8.8.8.8", "เสถียร ครอบคลุม"),
    DnsServer("quad9", "Quad9", "9.9.9.9", "บล็อกเว็บอันตราย"),
    DnsServer("adguard", "AdGuard", "94.140.14.14", "บล็อกโฆษณาฝั่งเซิร์ฟเวอร์"),
    DnsServer("opendns", "OpenDNS", "208.67.222.222", "Cisco Umbrella"),
)

enum class VpnMode { SHIELD, WIREGUARD }

data class WgProfile(val name: String, val file: File) {
    val endpoint: String? by lazy {
        runCatching {
            file.readLines().firstOrNull { it.trim().lowercase().startsWith("endpoint") }
                ?.substringAfter("=")?.trim()
        }.getOrNull()
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences
    private lateinit var profileDir: File

    private val _mode = MutableStateFlow(VpnMode.SHIELD)
    val mode: StateFlow<VpnMode> = _mode
    private val _dns = MutableStateFlow(DNS_SERVERS.first())
    val dns: StateFlow<DnsServer> = _dns
    private val _adBlock = MutableStateFlow(true)
    val adBlock: StateFlow<Boolean> = _adBlock
    private val _excluded = MutableStateFlow<Set<String>>(emptySet())
    val excludedApps: StateFlow<Set<String>> = _excluded
    private val _profiles = MutableStateFlow<List<WgProfile>>(emptyList())
    val profiles: StateFlow<List<WgProfile>> = _profiles
    private val _selected = MutableStateFlow<String?>(null)
    val selectedProfile: StateFlow<String?> = _selected
    private val _history = MutableStateFlow<List<String>>(emptyList())
    val mediaHistory: StateFlow<List<String>> = _history

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("redline", Context.MODE_PRIVATE)
        profileDir = File(ctx.filesDir, "profiles").apply { mkdirs() }
        _mode.value = runCatching { VpnMode.valueOf(sp.getString("mode", "SHIELD")!!) }.getOrDefault(VpnMode.SHIELD)
        _dns.value = DNS_SERVERS.firstOrNull { it.id == sp.getString("dns", null) } ?: DNS_SERVERS.first()
        _adBlock.value = sp.getBoolean("adblock", true)
        _excluded.value = sp.getStringSet("excluded", emptySet())!!.toSet()
        _selected.value = sp.getString("profile", null)
        _history.value = sp.getString("history", "")!!.split('\n').filter { it.isNotBlank() }
        reloadProfiles()
    }

    fun setMode(m: VpnMode) { _mode.value = m; sp.edit().putString("mode", m.name).apply() }
    fun setDns(d: DnsServer) { _dns.value = d; sp.edit().putString("dns", d.id).apply() }
    fun setAdBlock(on: Boolean) { _adBlock.value = on; sp.edit().putBoolean("adblock", on).apply() }
    fun toggleExcluded(pkg: String) {
        val s = _excluded.value.toMutableSet()
        if (!s.add(pkg)) s.remove(pkg)
        _excluded.value = s
        sp.edit().putStringSet("excluded", s).apply()
    }

    fun selectProfile(name: String?) { _selected.value = name; sp.edit().putString("profile", name).apply() }

    fun addProfile(name: String, text: String): WgProfile {
        val safe = name.replace(Regex("[^A-Za-z0-9ก-๙_.-]"), "_").take(40).ifBlank { "server" }
        val f = File(profileDir, "$safe.conf")
        f.writeText(text)
        reloadProfiles()
        if (_selected.value == null) selectProfile(safe)
        return WgProfile(safe, f)
    }

    fun deleteProfile(p: WgProfile) {
        p.file.delete()
        if (_selected.value == p.name) selectProfile(null)
        reloadProfiles()
    }

    private fun reloadProfiles() {
        _profiles.value = profileDir.listFiles { f -> f.extension == "conf" }
            ?.sortedBy { it.name }?.map { WgProfile(it.nameWithoutExtension, it) } ?: emptyList()
    }

    fun addHistory(url: String) {
        val list = (listOf(url) + _history.value.filter { it != url }).take(30)
        _history.value = list
        sp.edit().putString("history", list.joinToString("\n")).apply()
    }

    fun clearHistory() { _history.value = emptyList(); sp.edit().remove("history").apply() }
}
