package com.github.rockyx2000.dnslogger

/**
 * 記録対象の FQDN リスト。
 *  - `dmm.co.jp`   : サフィックス一致(dmm.co.jp と *.dmm.co.jp)
 *  - `=www.dmm.co.jp` : 完全一致のみ
 * 区切りは改行・カンマ・空白、`#` 以降は行コメント。リストが空なら何も記録しない。
 */
class DomainFilter(raw: String) {
    private val suffixes = HashSet<String>()
    private val exacts = HashSet<String>()

    init {
        raw.lineSequence()
            .map { it.substringBefore('#') }
            .flatMap { it.split(',', ' ', '\t').asSequence() }
            .map { it.trim().lowercase().trimEnd('.') }
            .filter { it.isNotEmpty() }
            .forEach {
                when {
                    it.startsWith("=") -> exacts += it.removePrefix("=")
                    it.startsWith("*.") -> suffixes += it.removePrefix("*.")
                    else -> suffixes += it
                }
            }
    }

    val isEmpty get() = suffixes.isEmpty() && exacts.isEmpty()

    fun matches(fqdn: String): Boolean {
        val name = fqdn.trimEnd('.').lowercase()
        if (name in exacts) return true
        // name 自身と、親ドメインを 1 つずつ落としながら suffixes と突き合わせる
        var s = name
        while (true) {
            if (s in suffixes) return true
            val dot = s.indexOf('.')
            if (dot < 0) return false
            s = s.substring(dot + 1)
        }
    }
}
