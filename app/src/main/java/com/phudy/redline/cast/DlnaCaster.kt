package com.phudy.redline.cast

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Sends a video URL to a Smart TV over DLNA / UPnP AVTransport.
 * Samsung (Tizen) TVs expose a MediaRenderer, as do LG, Sony, Android TV with DLNA apps, etc.
 */
object DlnaCaster {

    data class Renderer(val name: String, val manufacturer: String, val controlUrl: String) {
        val isSamsung get() = manufacturer.contains("samsung", true) || name.contains("samsung", true)
    }

    suspend fun discover(ctx: Context, timeoutMs: Int = 4000): List<Renderer> = withContext(Dispatchers.IO) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("redline-ssdp").apply { setReferenceCounted(false); acquire() }
        val locations = linkedSetOf<String>()
        try {
            MulticastSocket(null).use { sock ->
                sock.reuseAddress = true
                // Bind to the Wi-Fi address so discovery never leaves through a VPN tunnel
                sock.bind(java.net.InetSocketAddress(CastProxy.lanAddress(), 0))
                CastProxy.lanInterface()?.let { runCatching { sock.networkInterface = it } }
                sock.timeToLive = 4
                sock.soTimeout = 800
                val group = InetAddress.getByName("239.255.255.250")
                for (st in listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1", "ssdp:all")) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                    repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
                }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val text = String(p.data, 0, p.length)
                        Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.let { locations += it }
                    } catch (_: SocketTimeoutException) { }
                }
            }
        } catch (_: Exception) {
        } finally { lock.release() }
        locations.mapNotNull { describe(it) }.distinctBy { it.controlUrl }
    }

    /**
     * Finds a TV by IP when multicast discovery is blocked (routers with "AP isolation" /
     * IGMP filtering do this): unicast M-SEARCH first, then Samsung's fixed DLNA address :9197/dmr.
     */
    suspend fun probe(ip: String): Renderer? = withContext(Dispatchers.IO) {
        val host = ip.trim()
        runCatching {
            java.net.DatagramSocket(java.net.InetSocketAddress(CastProxy.lanAddress(), 0)).use { s ->
                s.soTimeout = 1500
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: $host:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n".toByteArray()
                s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(host), 1900))
                val buf = ByteArray(4096); val p = DatagramPacket(buf, buf.size); s.receive(p)
                Regex("(?im)^location:\\s*(\\S+)").find(String(p.data, 0, p.length))?.groupValues?.get(1)?.let { describe(it) }
            }
        }.getOrNull()
            ?: describe("http://$host:9197/dmr")
            ?: describe("http://$host:7676/smp_15_")
    }

    private fun describe(location: String): Renderer? = runCatching {
        val xml = (URL(location).openConnection() as HttpURLConnection).apply { connectTimeout = 2500; readTimeout = 2500 }
            .inputStream.bufferedReader().readText()
        val name = tag(xml, "friendlyName") ?: "TV"
        val maker = tag(xml, "manufacturer") ?: ""
        val svc = Regex("(?s)<service>(.*?)</service>").findAll(xml).map { it.groupValues[1] }
            .firstOrNull { it.contains("AVTransport") } ?: return null
        val ctrl = tag(svc, "controlURL") ?: return null
        val base = URL(tag(xml, "URLBase") ?: location)
        Renderer(unescape(name), maker, URL(base, ctrl).toString())
    }.getOrNull()

    private fun tag(xml: String, t: String) =
        Regex("(?s)<(?:\\w+:)?$t>\\s*(.*?)\\s*</(?:\\w+:)?$t>").find(xml)?.groupValues?.get(1)

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun mimeFor(url: String): String {
        val u = url.lowercase().substringBefore('?')
        return when {
            u.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            u.endsWith(".mkv") -> "video/x-matroska"
            u.endsWith(".webm") -> "video/webm"
            u.endsWith(".ts") -> "video/mp2t"
            else -> "video/mp4"
        }
    }

    /**
     * Sends a video to the TV. The TV streams it through [CastProxy] on the phone, which adds the
     * website's headers, Samsung's DLNA headers, byte ranges, and serves downloaded files.
     */
    suspend fun play(ctx: Context, r: Renderer, source: String, title: String, headers: Map<String, String> = emptyMap()): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mime = if (source.startsWith("content://")) ctx.contentResolver.getType(android.net.Uri.parse(source)) ?: "video/mp4" else mimeFor(source)
                val url = CastProxy.urlFor(ctx, source, headers)
                val dlna = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
                val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                    "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
                    "xmlns:sec=\"http://www.sec.co.kr/\">" +
                    "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${esc(title)}</dc:title>" +
                    "<upnp:class>object.item.videoItem</upnp:class>" +
                    "<res protocolInfo=\"http-get:*:$mime:$dlna\">${esc(url)}</res></item></DIDL-Lite>"
                runCatching { soap(r, "Stop", "") }
                // Some firmware rejects metadata it doesn't like — retry without it
                runCatching { soap(r, "SetAVTransportURI", "<CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>") }
                    .recoverCatching { soap(r, "SetAVTransportURI", "<CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>") }
                    .getOrThrow()
                runCatching { soap(r, "Play", "<Speed>1</Speed>") }.recoverCatching {
                    Thread.sleep(1500); soap(r, "Play", "<Speed>1</Speed>")
                }.getOrThrow()
            }
        }

    suspend fun stop(r: Renderer) = withContext(Dispatchers.IO) { runCatching { soap(r, "Stop", "") } }
    suspend fun pause(r: Renderer) = withContext(Dispatchers.IO) { runCatching { soap(r, "Pause", "") } }
    suspend fun resume(r: Renderer) = withContext(Dispatchers.IO) { runCatching { soap(r, "Play", "<Speed>1</Speed>") } }

    private fun soap(r: Renderer, action: String, args: String) {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"
        val c = URL(r.controlUrl).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 4000; c.readTimeout = 6000
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
            throw IllegalStateException("ทีวีตอบกลับ $code ${Regex("<errorDescription>(.*?)</").find(err)?.groupValues?.get(1) ?: ""}")
        }
    }
}
