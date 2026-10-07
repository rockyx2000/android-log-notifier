package com.github.rockyx2000.dnslogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsPacketTest {
    /** 12 バイトのヘッダ + QNAME + QTYPE + QCLASS からなる DNS クエリを作る。 */
    private fun query(name: String, type: Int = 1, flags: Int = 0x0100, qd: Int = 1): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out += (v shr 8).toByte(); out += v.toByte() }
        u16(0x1234); u16(flags); u16(qd); u16(0); u16(0); u16(0)
        if (name.isNotEmpty()) name.split('.').forEach { l -> out += l.length.toByte(); l.forEach { out += it.code.toByte() } }
        out += 0
        u16(type); u16(1)
        return out.toByteArray()
    }

    private fun parse(b: ByteArray) = DnsPacket.parseQuestion(b, 0, b.size)

    @Test fun `通常のクエリから名前と種別を読む`() {
        val q = parse(query("www.example.com", 1))!!
        assertEquals("www.example.com", q.name)
        assertEquals(1, q.type)
    }

    @Test fun `名前は小文字にそろえる`() {
        assertEquals("www.example.com", parse(query("WWW.Example.COM"))!!.name)
    }

    @Test fun `AAAA と HTTPS の種別を読める`() {
        assertEquals(28, parse(query("a.com", 28))!!.type)
        assertEquals(65, parse(query("a.com", 65))!!.type)
    }

    @Test fun `オフセットを指定して途中から読める`() {
        val q = query("a.com")
        val shifted = ByteArray(5) + q
        assertEquals("a.com", DnsPacket.parseQuestion(shifted, 5, q.size)!!.name)
    }

    @Test fun `種別名の変換`() {
        assertEquals("A", DnsPacket.typeName(1))
        assertEquals("AAAA", DnsPacket.typeName(28))
        assertEquals("HTTPS", DnsPacket.typeName(65))
        assertEquals("TYPE999", DnsPacket.typeName(999))
    }

    @Test fun `応答(QR ビット)は対象外`() {
        assertNull(parse(query("a.com", flags = 0x8180)))
    }

    @Test fun `質問が 0 件なら対象外`() {
        assertNull(parse(query("a.com", qd = 0)))
    }

    @Test fun `ヘッダより短いデータは対象外`() {
        assertNull(DnsPacket.parseQuestion(ByteArray(11), 0, 11))
        assertNull(DnsPacket.parseQuestion(ByteArray(0), 0, 0))
    }

    @Test fun `QNAME の圧縮ポインタは対象外`() {
        val b = query("a.com")
        b[12] = 0xC0.toByte()                         // 長さの位置にポインタ(上位 2 ビット)
        assertNull(parse(b))
    }

    @Test fun `ラベル長が全体からはみ出すなら対象外`() {
        val b = query("a.com")
        b[12] = 60                                    // 実際より長いラベル
        assertNull(parse(b))
    }

    @Test fun `QTYPE と QCLASS が欠けていたら対象外`() {
        val b = query("a.com")
        assertNull(DnsPacket.parseQuestion(b, 0, b.size - 3))
        assertNull(DnsPacket.parseQuestion(b, 0, 14))  // 名前の途中で切れている
    }

    @Test fun `ルート名は空文字として読める`() {
        assertNotNull(parse(query("")))
        assertEquals("", parse(query(""))!!.name)
    }
}
