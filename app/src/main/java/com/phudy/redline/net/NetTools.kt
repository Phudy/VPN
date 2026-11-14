package com.phudy.redline.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

object NetTools {

    /** Round-trip time of a real DNS query (example.com A) to [ip], in ms; null on timeout. */
    suspend fun dnsLatency(ip: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val q = buildQuery("example.com")
            DatagramSocket().use { s ->
                s.soTimeout = 2500
                val t0 = System.nanoTime()
                s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 53))
                val rb = ByteArray(512)
                s.receive(DatagramPacket(rb, rb.size))
                (System.nanoTime() - t0) / 1_000_000
            }
        }.getOrNull()
    }

    private fun buildQuery(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val id = Random.nextInt(0xFFFF)
        out.write(id shr 8); out.write(id and 0xFF)
        out.write(1); out.write(0); out.write(0); out.write(1)
        repeat(6) { out.write(0) }
        for (label in name.split('.')) { out.write(label.length); out.write(label.toByteArray()) }
        out.write(0); out.write(0); out.write(1); out.write(0); out.write(1)
        return out.toByteArray()
    }

    /** Latency to a WireGuard endpoint host (ICMP ping, falling back to TCP connect). */
    suspend fun hostLatency(endpoint: String): Long? = withContext(Dispatchers.IO) {
        val host = endpoint.substringBeforeLast(':').trim('[', ']')
        runCatching {
            val p = ProcessBuilder("ping", "-c", "1", "-W", "2", host).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            Regex("time=([0-9.]+)").find(out)?.groupValues?.get(1)?.toDouble()?.toLong()
        }.getOrNull() ?: runCatching {
            Socket().use { s ->
                val t0 = System.nanoTime()
                s.connect(InetSocketAddress(host, 443), 2500)
                (System.nanoTime() - t0) / 1_000_000
            }
        }.getOrNull()
    }

    data class SpeedResult(val pingMs: Long, val downMbps: Double, val upMbps: Double)

    /** Download / upload test against Cloudflare's public speed endpoints. */
    suspend fun speedTest(onProgress: (phase: String, mbps: Double) -> Unit): SpeedResult = withContext(Dispatchers.IO) {
        val ping = (1..3).mapNotNull {
            runCatching {
                val t0 = System.nanoTime()
                val c = URL("https://speed.cloudflare.com/__down?bytes=0").openConnection() as HttpURLConnection
                c.inputStream.use { it.readBytes() }
                (System.nanoTime() - t0) / 1_000_000
            }.getOrNull()
        }.minOrNull() ?: -1

        // Download: 25 MB stream, measured live
        var down = 0.0
        runCatching {
            val c = URL("https://speed.cloudflare.com/__down?bytes=25000000").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 15000
            val buf = ByteArray(64 * 1024)
            var total = 0L
            val t0 = System.nanoTime()
            c.inputStream.use { ins ->
                while (coroutineContext.isActive) {
                    val n = ins.read(buf); if (n < 0) break
                    total += n
                    val sec = (System.nanoTime() - t0) / 1e9
                    if (sec > 0.2) { down = total * 8 / sec / 1e6; onProgress("down", down) }
                    if (sec > 12) break
                }
            }
        }

        // Upload: 8 MB POST
        var up = 0.0
        runCatching {
            val size = 8_000_000
            val c = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.setFixedLengthStreamingMode(size)
            c.connectTimeout = 8000; c.readTimeout = 20000
            val chunk = ByteArray(64 * 1024)
            var sent = 0
            val t0 = System.nanoTime()
            c.outputStream.use { os ->
                while (sent < size) {
                    val n = minOf(chunk.size, size - sent)
                    os.write(chunk, 0, n); sent += n
                    val sec = (System.nanoTime() - t0) / 1e9
                    if (sec > 0.2) { up = sent * 8 / sec / 1e6; onProgress("up", up) }
                }
            }
            c.inputStream.use { it.readBytes() }
            up = size * 8 / ((System.nanoTime() - t0) / 1e9) / 1e6
            onProgress("up", up)
        }
        SpeedResult(ping, down, up)
    }

    fun formatBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }
}
