package com.github.rockyx2000.dnslogger

import android.content.Context
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * FQDN へのアクセスをアプリ内部ストレージに TSV(時刻 / FQDN / 種別)で追記する。
 * 同一 FQDN は DEDUP_MS 以内なら 1 回とみなす(A / AAAA / HTTPS の連続クエリ対策)。
 *
 * 端末のロックが解除される前は、ログ(CE)に書けないので、DE の一時バッファに書く。
 * 解除後の最初の追記(または [drainPending])で、バッファをログの先頭側へ取り込む。
 */
class AccessLog(private val context: Context) {
    val file = File(context.filesDir, FILE_NAME)
    private val recent = HashMap<String, Long>()

    fun append(fqdn: String, qtype: String) = synchronized(LOCK) {
        appendLocked(fqdn, qtype)
    }

    /** ロック中に溜めたバッファを、ログへ取り込む。ロック中は何もしない。 */
    fun drainPending() = synchronized(LOCK) { drainLocked() }

    private fun drainLocked() {
        if (!Storage.unlocked(context)) return
        val pending = Storage.pendingFile(context)
        if (!pending.exists()) return
        file.appendText(pending.readText())
        pending.delete()
    }

    private fun appendLocked(fqdn: String, qtype: String) {
        val now = System.currentTimeMillis()
        val last = recent[fqdn]
        if (last != null && now - last < DEDUP_MS) return
        if (recent.size > 5000) recent.entries.removeIf { now - it.value >= DEDUP_MS }
        recent[fqdn] = now

        val ts = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        if (!Storage.unlocked(context)) {
            val pending = Storage.pendingFile(context)
            if (pending.length() < MAX_PENDING_BYTES) pending.appendText("$ts\t$fqdn\t$qtype\n")
            return
        }
        drainLocked()
        if (file.length() > MAX_BYTES) {
            // 世代は 1 つだけ保持する(dns_access.log.1 を上書き)
            file.renameTo(File(file.parentFile, "$FILE_NAME.1"))
        }
        file.appendText("$ts\t$fqdn\t$qtype\n")
    }

    fun tail(maxLines: Int): String =
        if (!file.exists()) "" else file.readLines().takeLast(maxLines).joinToString("\n")

    companion object {
        const val FILE_NAME = "dns_access.log"
        private val LOCK = Any()
        private const val MAX_BYTES = 5L * 1024 * 1024
        private const val MAX_PENDING_BYTES = 1L * 1024 * 1024   // ロック中のバッファの上限
        private const val DEDUP_MS = 60_000L
    }
}
