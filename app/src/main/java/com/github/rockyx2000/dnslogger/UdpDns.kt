package com.github.rockyx2000.dnslogger

/** TUN に流れる IPv4 / UDP パケットのうち、DNS(宛先ポート 53)の読み取りと応答の組み立て。 */
object UdpDns {
    class Query(val srcIp: ByteArray, val dstIp: ByteArray, val srcPort: Int, val payload: ByteArray)

    /** IPv4 + UDP + DNS(宛先ポート 53)なら切り出して返す。それ以外は null(破棄)。 */
    fun parse(buf: ByteArray, n: Int): Query? {
        if (n < 28) return null
        if ((buf[0].toInt() shr 4) != 4) return null          // IPv4 のみ
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20) return null
        if (buf[9].toInt() != 17) return null                 // UDP のみ
        if (n < ihl + 8) return null
        val dstPort = u16(buf, ihl + 2)
        if (dstPort != 53) return null
        val payloadOff = ihl + 8
        val payloadLen = n - payloadOff
        return Query(
            srcIp = buf.copyOfRange(12, 16),
            dstIp = buf.copyOfRange(16, 20),
            srcPort = u16(buf, ihl),
            payload = buf.copyOfRange(payloadOff, payloadOff + payloadLen),
        )
    }

    /** 応答 DNS を IPv4/UDP ヘッダで包み直す(送信元と宛先は入れ替え)。 */
    fun buildResponse(q: Query, dns: ByteArray, dnsLen: Int): ByteArray {
        val total = 20 + 8 + dnsLen
        val p = ByteArray(total)
        p[0] = 0x45.toByte()
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[8] = 64                                     // TTL
        p[9] = 17                                     // UDP
        q.dstIp.copyInto(p, 12)                       // src = 元の宛先(10.0.0.1)
        q.srcIp.copyInto(p, 16)                       // dst = 元の送信元
        val sum = checksum(p, 0, 20)
        p[10] = (sum shr 8).toByte(); p[11] = sum.toByte()
        p[20] = 0; p[21] = 53                         // src port
        p[22] = (q.srcPort shr 8).toByte(); p[23] = q.srcPort.toByte()
        val udpLen = 8 + dnsLen
        p[24] = (udpLen shr 8).toByte(); p[25] = udpLen.toByte()
        // UDP チェックサムは IPv4 では 0(未使用)でよい
        dns.copyInto(p, 28, 0, dnsLen)
        return p
    }

    fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        while (i < off + len) {
            sum += u16(b, i)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
}
