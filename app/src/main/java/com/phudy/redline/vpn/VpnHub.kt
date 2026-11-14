package com.phudy.redline.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ConnState { OFF, CONNECTING, ON, ERROR }

/** Shared live state, observed by the UI. */
object VpnHub {
    val state = MutableStateFlow(ConnState.OFF)
    val error = MutableStateFlow<String?>(null)
    val connectedAt = MutableStateFlow(0L)
    val dnsQueries = MutableStateFlow(0L)
    val blocked = MutableStateFlow(0L)
    val cacheHits = MutableStateFlow(0L)
    val avgDnsMs = MutableStateFlow(0.0)
    val rxBytes = MutableStateFlow(0L)
    val txBytes = MutableStateFlow(0L)
    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recentBlocked: StateFlow<List<String>> = _recent

    fun onBlocked(domain: String) {
        blocked.value++
        _recent.value = (listOf(domain) + _recent.value.filter { it != domain }).take(40)
    }

    fun onLatency(ms: Long) {
        val n = (dnsQueries.value - cacheHits.value).coerceAtLeast(1)
        avgDnsMs.value = avgDnsMs.value + (ms - avgDnsMs.value) / n
    }

    fun resetSession() {
        dnsQueries.value = 0; blocked.value = 0; cacheHits.value = 0; avgDnsMs.value = 0.0
        rxBytes.value = 0; txBytes.value = 0
        connectedAt.value = System.currentTimeMillis()
    }
}
