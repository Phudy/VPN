package com.phudy.redline.vpn

import java.nio.ByteBuffer

/** Minimal IPv4/UDP/DNS helpers for the local DNS filter tunnel. */
object DnsPacket {

    class UdpQuery(
        val srcIp: ByteArray, val dstIp: ByteArray,
        val srcPort: Int, val dstPort: Int,
        val payload: ByteArray,
    )

    /** Parses an IPv4 UDP packet read from the TUN device; returns null for anything else. */
    fun parseUdp(pkt: ByteArray, len: Int): UdpQuery? {
        if (len < 28) return null
        val version = (pkt[0].toInt() shr 4) and 0xF
        if (version != 4) return null
        val ihl = (pkt[0].toInt() and 0xF) * 4
        if (pkt[9].toInt() != 17) return null // UDP only
        if (len < ihl + 8) return null
        val src = pkt.copyOfRange(12, 16)
        val dst = pkt.copyOfRange(16, 20)
        val sPort = u16(pkt, ihl)
        val dPort = u16(pkt, ihl + 2)
        val udpLen = u16(pkt, ihl + 4)
        val end = minOf(len, ihl + udpLen)
        if (end <= ihl + 8) return null
        return UdpQuery(src, dst, sPort, dPort, pkt.copyOfRange(ihl + 8, end))
    }

    /** Builds an IPv4/UDP packet carrying [payload] from (srcIp:srcPort) to (dstIp:dstPort). */
    fun buildUdp(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val b = ByteBuffer.allocate(total)
        b.put(0x45.toByte()); b.put(0)
        b.putShort(total.toShort()); b.putShort(0); b.putShort(0x4000.toShort())
        b.put(64); b.put(17); b.putShort(0)
        b.put(srcIp); b.put(dstIp)
        b.putShort(srcPort.toShort()); b.putShort(dstPort.toShort())
        b.putShort((8 + payload.size).toShort()); b.putShort(0) // UDP checksum optional on IPv4
        b.put(payload)
        val arr = b.array()
        val cs = checksum(arr, 0, 20)
        arr[10] = (cs shr 8).toByte(); arr[11] = cs.toByte()
        return arr
    }

    private fun checksum(d: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        while (i < off + len) {
            sum += ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun u16(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)

    /** Question name and qtype of a DNS query, plus the offset right after the question. */
    data class Question(val name: String, val qtype: Int, val end: Int)

    fun question(dns: ByteArray): Question? {
        if (dns.size < 17) return null
        if (u16(dns, 4) < 1) return null
        var i = 12
        val sb = StringBuilder()
        while (i < dns.size) {
            val l = dns[i].toInt() and 0xFF
            if (l == 0) { i++; break }
            if (l and 0xC0 != 0 || i + 1 + l > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, i + 1, l, Charsets.US_ASCII))
            i += 1 + l
        }
        if (i + 4 > dns.size) return null
        return Question(sb.toString().lowercase(), u16(dns, i), i + 4)
    }

    /** A "sinkhole" answer: 0.0.0.0 / :: for A / AAAA, NXDOMAIN for anything else. */
    fun blockedResponse(query: ByteArray, q: Question): ByteArray {
        val header = query.copyOfRange(0, q.end)
        header[2] = (0x80 or (header[2].toInt() and 0x79)).toByte() // QR=1, keep opcode+RD
        val answer: ByteArray? = when (q.qtype) {
            1 -> rr(1, ByteArray(4))
            28 -> rr(28, ByteArray(16))
            else -> null
        }
        header[3] = if (answer == null) 0x83.toByte() else 0x80.toByte() // RA + rcode
        header[6] = 0; header[7] = if (answer == null) 0 else 1   // ANCOUNT
        header[8] = 0; header[9] = 0; header[10] = 0; header[11] = 0 // NS / AR
        return if (answer == null) header else header + answer
    }

    private fun rr(type: Int, data: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(12 + data.size)
        b.putShort(0xC00C.toShort()) // pointer to question name
        b.putShort(type.toShort()); b.putShort(1)
        b.putInt(300)
        b.putShort(data.size.toShort()); b.put(data)
        return b.array()
    }

    /** Smallest TTL among answers, for the local DNS cache. */
    fun minTtl(resp: ByteArray): Int {
        return runCatching {
            val qd = u16(resp, 4); val an = u16(resp, 6)
            if (an == 0) return 30
            var i = 12
            repeat(qd) { i = skipName(resp, i) + 4 }
            var min = Int.MAX_VALUE
            repeat(an) {
                i = skipName(resp, i)
                val ttl = ByteBuffer.wrap(resp, i + 4, 4).int
                min = minOf(min, ttl)
                i += 10 + u16(resp, i + 8)
            }
            min.coerceIn(10, 3600)
        }.getOrDefault(30)
    }

    private fun skipName(d: ByteArray, start: Int): Int {
        var i = start
        while (true) {
            val l = d[i].toInt() and 0xFF
            if (l == 0) return i + 1
            if (l and 0xC0 == 0xC0) return i + 2
            i += 1 + l
        }
    }
}
