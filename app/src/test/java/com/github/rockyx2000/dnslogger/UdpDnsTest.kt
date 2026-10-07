package com.github.rockyx2000.dnslogger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UdpDnsTest {
    private val client = byteArrayOf(10, 0, 0, 2)
    private val dns = byteArrayOf(10, 0, 0, 1)

    /** [ihl] バイトの IPv4 ヘッダ + UDP ヘッダ + [payload] のパケットを作る。 */
    private fun packet(
        payload: ByteArray = byteArrayOf(1, 2, 3, 4),
        dstPort: Int = 53, srcPort: Int = 40000, proto: Int = 17, version: Int = 4, ihl: Int = 20,
    ): ByteArray {
        val p = ByteArray(ihl + 8 + payload.size)
        p[0] = ((version shl 4) or (ihl / 4)).toByte()
        p[9] = proto.toByte()
        client.copyInto(p, 12); dns.copyInto(p, 16)
        p[ihl] = (srcPort shr 8).toByte(); p[ihl + 1] = srcPort.toByte()
        p[ihl + 2] = (dstPort shr 8).toByte(); p[ihl + 3] = dstPort.toByte()
        payload.copyInto(p, ihl + 8)
        return p
    }

    @Test fun `宛先 53 の UDP パケットからクエリを取り出す`() {
        val q = UdpDns.parse(packet(), packet().size)!!
        assertArrayEquals(client, q.srcIp)
        assertArrayEquals(dns, q.dstIp)
        assertEquals(40000, q.srcPort)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), q.payload)
    }

    @Test fun `IP オプション付き(IHL 24)でも正しい位置から読む`() {
        val p = packet(ihl = 24)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), UdpDns.parse(p, p.size)!!.payload)
    }

    @Test fun `読み取り長 n までだけを使い、バッファの余りは無視する`() {
        val p = packet() + ByteArray(100) { 9 }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), UdpDns.parse(p, 32)!!.payload)
    }

    @Test fun `IPv4 以外・UDP 以外・53 以外・短すぎるものは破棄する`() {
        assertNull(UdpDns.parse(packet(version = 6), 32))
        assertNull(UdpDns.parse(packet(proto = 6), 32))
        assertNull(UdpDns.parse(packet(dstPort = 443), 32))
        assertNull(UdpDns.parse(packet(), 27))
    }

    @Test fun `IHL が不正(20 未満)なら破棄する`() {
        val p = packet()
        p[0] = 0x44                                   // IHL = 4 → 16 バイト(IPv4 の最小 20 に満たない)
        assertNull(UdpDns.parse(p, p.size))
    }

    @Test fun `IHL がデータより大きければ破棄する`() {
        val p = packet()
        p[0] = 0x4F                                   // IHL = 15 → 60 バイト
        assertNull(UdpDns.parse(p, p.size))
    }

    @Test fun `チェックサムは RFC の既知の例と一致する`() {
        // 45 00 00 73 00 00 40 00 40 11 [チェックサム] c0 a8 00 01 c0 a8 00 c7 → 0xB861
        val h = intArrayOf(0x45, 0, 0, 0x73, 0, 0, 0x40, 0, 0x40, 0x11, 0, 0, 0xC0, 0xA8, 0, 1, 0xC0, 0xA8, 0, 0xC7)
            .map { it.toByte() }.toByteArray()
        assertEquals(0xB861, UdpDns.checksum(h, 0, 20))
    }

    @Test fun `応答パケットは送信元と宛先が入れ替わり、ヘッダのチェックサムが正しい`() {
        val q = UdpDns.parse(packet(), packet().size)!!
        val dnsResp = ByteArray(40) { it.toByte() }
        val r = UdpDns.buildResponse(q, dnsResp, dnsResp.size)

        assertEquals(20 + 8 + 40, r.size)
        assertEquals(0x45, r[0].toInt())
        assertEquals(17, r[9].toInt())
        assertArrayEquals(dns, r.copyOfRange(12, 16))     // 送信元 = 元の宛先
        assertArrayEquals(client, r.copyOfRange(16, 20))  // 宛先 = 元の送信元
        // ヘッダ全体(チェックサム欄を含む)の和が 0 になる
        assertEquals(0, UdpDns.checksum(r, 0, 20))
        // IP の全長と UDP の長さ
        assertEquals(68, ((r[2].toInt() and 0xFF) shl 8) or (r[3].toInt() and 0xFF))
        assertEquals(48, ((r[24].toInt() and 0xFF) shl 8) or (r[25].toInt() and 0xFF))
        // ポート: 送信元 53、宛先 = クライアントの送信元ポート
        assertEquals(53, ((r[20].toInt() and 0xFF) shl 8) or (r[21].toInt() and 0xFF))
        assertEquals(40000, ((r[22].toInt() and 0xFF) shl 8) or (r[23].toInt() and 0xFF))
        assertArrayEquals(dnsResp, r.copyOfRange(28, 68))
    }

    @Test fun `応答の長さがバッファより短ければ、その長さだけを載せる`() {
        val q = UdpDns.parse(packet(), packet().size)!!
        val buf = ByteArray(4096) { 7 }
        val r = UdpDns.buildResponse(q, buf, 10)
        assertEquals(38, r.size)
        assertNotNull(r)
    }
}
