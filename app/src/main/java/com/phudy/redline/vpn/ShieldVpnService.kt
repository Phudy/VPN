package com.phudy.redline.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.phudy.redline.MainActivity
import com.phudy.redline.R
import com.phudy.redline.RedlineApp
import com.phudy.redline.data.Prefs
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * "Shield" mode: a local VPN that only captures DNS traffic.
 * Ads / trackers / malware domains are answered with a sinkhole, everything
 * else is forwarded to the chosen fast upstream resolver and cached.
 * No traffic leaves the phone through a third-party server.
 */
class ShieldVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var scope: CoroutineScope? = null
    private val cache = ConcurrentHashMap<String, Pair<ByteArray, Long>>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        startForegroundCompat()
        if (tun == null) start()
        return START_STICKY
    }

    private fun start() {
        VpnHub.state.value = ConnState.CONNECTING
        VpnHub.error.value = null
        try {
            val b = Builder()
                .setSession("REDLINE Shield")
                .addAddress(TUN_ADDR, 32)
                .addDnsServer(FAKE_DNS)
                .addRoute(FAKE_DNS, 32)
                .setBlocking(true)
                .setMtu(1500)
            for (pkg in Prefs.excludedApps.value) runCatching { b.addDisallowedApplication(pkg) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
            val pfd = b.establish() ?: throw IllegalStateException("ยังไม่ได้รับอนุญาต VPN")
            tun = pfd
            VpnHub.resetSession()
            VpnHub.state.value = ConnState.ON
            val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = sc
            sc.launch { loop(pfd) }
        } catch (e: Exception) {
            VpnHub.error.value = e.message
            VpnHub.state.value = ConnState.ERROR
            shutdown()
        }
    }

    private suspend fun loop(pfd: ParcelFileDescriptor) = coroutineScope {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        while (isActive) {
            val n = try { input.read(buf) } catch (e: Exception) { break }
            if (n <= 0) continue
            val q = DnsPacket.parseUdp(buf, n) ?: continue
            if (q.dstPort != 53) continue
            launch { handle(q, output) }
        }
    }

    private fun handle(q: DnsPacket.UdpQuery, out: FileOutputStream) {
        val question = DnsPacket.question(q.payload) ?: return
        VpnHub.dnsQueries.value++
        val reply: ByteArray? = if (Prefs.adBlock.value && Blocklist.isBlocked(question.name)) {
            VpnHub.onBlocked(question.name)
            DnsPacket.blockedResponse(q.payload, question)
        } else {
            resolve(q.payload, question)
        }
        reply ?: return
        val pkt = DnsPacket.buildUdp(q.dstIp, q.srcIp, q.dstPort, q.srcPort, reply)
        synchronized(out) { runCatching { out.write(pkt) } }
    }

    private fun resolve(query: ByteArray, question: DnsPacket.Question): ByteArray? {
        val key = "${question.name}/${question.qtype}"
        val now = System.currentTimeMillis()
        cache[key]?.let { (resp, exp) ->
            if (exp > now) {
                VpnHub.cacheHits.value++
                val copy = resp.copyOf()
                copy[0] = query[0]; copy[1] = query[1]
                return copy
            } else cache.remove(key)
        }
        val t0 = System.nanoTime()
        val resp = forward(query, Prefs.dns.value.ip) ?: forward(query, "1.0.0.1") ?: return null
        VpnHub.onLatency((System.nanoTime() - t0) / 1_000_000)
        if (cache.size > 4000) cache.clear()
        cache[key] = resp to now + DnsPacket.minTtl(resp) * 1000L
        return resp
    }

    private fun forward(query: ByteArray, server: String): ByteArray? = runCatching {
        DatagramSocket().use { s ->
            protect(s)
            s.soTimeout = 4000
            s.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
            val rb = ByteArray(4096)
            val p = DatagramPacket(rb, rb.size)
            s.receive(p)
            rb.copyOf(p.length)
        }
    }.getOrNull()

    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ShieldVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, RedlineApp.CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("REDLINE Shield ทำงานอยู่")
            .setContentText("บล็อกโฆษณา • Turbo DNS • ${Prefs.dns.value.name}")
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "ตัดการเชื่อมต่อ", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(1, n)
    }

    private fun shutdown() {
        scope?.cancel(); scope = null
        runCatching { tun?.close() }; tun = null
        cache.clear()
        if (VpnHub.state.value != ConnState.ERROR) VpnHub.state.value = ConnState.OFF
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() { shutdown() }
    override fun onDestroy() { shutdown(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "com.phudy.redline.STOP"
        private const val TUN_ADDR = "10.111.222.2"
        private const val FAKE_DNS = "10.111.222.1"
    }
}
