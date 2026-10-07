package com.github.rockyx2000.dnslogger

/** DNS メッセージのうち、質問セクション(QNAME / QTYPE)だけを読む最小パーサ。 */
object DnsPacket {
    data class Question(val name: String, val type: Int)

    private val TYPE_NAMES = mapOf(
        1 to "A", 2 to "NS", 5 to "CNAME", 12 to "PTR", 15 to "MX",
        16 to "TXT", 28 to "AAAA", 33 to "SRV", 64 to "SVCB", 65 to "HTTPS",
    )

    fun typeName(type: Int): String = TYPE_NAMES[type] ?: "TYPE$type"

    /** [buf] の [offset] から [length] バイトを DNS メッセージとして解析する。不正なら null。 */
    fun parseQuestion(buf: ByteArray, offset: Int, length: Int): Question? {
        val end = offset + length
        if (length < 12) return null
        // QR ビット(0x80)が立っていたら応答なので対象外
        if (buf[offset + 2].toInt() and 0x80 != 0) return null
        val qdCount = u16(buf, offset + 4)
        if (qdCount < 1) return null

        var pos = offset + 12
        val labels = StringBuilder()
        while (true) {
            if (pos >= end) return null
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) { pos++; break }
            // クエリの QNAME に圧縮ポインタは通常現れない
            if (len and 0xC0 != 0 || pos + 1 + len > end) return null
            if (labels.isNotEmpty()) labels.append('.')
            for (i in 1..len) labels.append((buf[pos + i].toInt() and 0xFF).toChar())
            pos += 1 + len
        }
        if (pos + 4 > end) return null
        return Question(labels.toString().lowercase(), u16(buf, pos))
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
}
