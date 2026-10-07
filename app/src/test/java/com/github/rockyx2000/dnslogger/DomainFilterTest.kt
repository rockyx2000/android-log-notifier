package com.github.rockyx2000.dnslogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainFilterTest {
    @Test fun `リストが空なら何も記録しない`() {
        val f = DomainFilter("")
        assertTrue(f.isEmpty)
        assertFalse(f.matches("example.com"))
    }

    @Test fun `コメントと空白だけの入力は空として扱う`() {
        val f = DomainFilter("  \n# コメントだけ\n\t,, \n")
        assertTrue(f.isEmpty)
        assertFalse(f.matches("example.com"))
    }

    @Test fun `サフィックス指定は本体とサブドメインに一致する`() {
        val f = DomainFilter("dmm.co.jp")
        assertTrue(f.matches("dmm.co.jp"))
        assertTrue(f.matches("www.dmm.co.jp"))
        assertTrue(f.matches("a.b.c.dmm.co.jp"))
    }

    @Test fun `サフィックス指定は名前の途中や別ドメインに一致しない`() {
        val f = DomainFilter("dmm.co.jp")
        assertFalse(f.matches("notdmm.co.jp"))        // 文字列としての後方一致ではなく、ラベル単位で見る
        assertFalse(f.matches("dmm.co.jp.evil.com"))
        assertFalse(f.matches("co.jp"))
        assertFalse(f.matches("jp"))
    }

    @Test fun `完全一致指定はその名前だけに一致する`() {
        val f = DomainFilter("=www.dmm.co.jp")
        assertTrue(f.matches("www.dmm.co.jp"))
        assertFalse(f.matches("dmm.co.jp"))
        assertFalse(f.matches("a.www.dmm.co.jp"))
    }

    @Test fun `大文字と末尾のドットを無視する`() {
        val f = DomainFilter("DMM.co.JP.")
        assertTrue(f.matches("WWW.Dmm.Co.Jp."))
        assertTrue(f.matches("www.dmm.co.jp"))
    }

    @Test fun `ワイルドカード記法はサフィックス指定として扱う`() {
        val f = DomainFilter("*.dmm.co.jp")
        assertTrue(f.matches("www.dmm.co.jp"))
        assertTrue(f.matches("dmm.co.jp"))            // 本体にも一致する(サフィックス指定と同じ)
    }

    @Test fun `改行カンマ空白で区切れて、シャープ以降はコメント`() {
        val f = DomainFilter("a.com, b.com c.com # d.com\n=e.com\n#f.com")
        assertTrue(f.matches("a.com"))
        assertTrue(f.matches("b.com"))
        assertTrue(f.matches("c.com"))
        assertFalse(f.matches("d.com"))
        assertTrue(f.matches("e.com"))
        assertFalse(f.matches("f.com"))
    }

    @Test fun `サフィックスと完全一致を併用できる`() {
        val f = DomainFilter("example.com\n=www.iana.org")
        assertTrue(f.matches("sub.example.com"))
        assertTrue(f.matches("www.iana.org"))
        assertFalse(f.matches("iana.org"))
        assertEquals(false, f.isEmpty)
    }
}
