package com.example.dnslogger

import android.content.Context
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * FQDN へのアクセスをアプリ内部ストレージに TSV(時刻 / FQDN / 種別)で追記する。
 * 同一 FQDN は DEDUP_MS 以内なら 1 回とみなす(A / AAAA / HTTPS の連続クエリ対策)。
 */
class AccessLog(context: Context) {
    val file = File(context.filesDir, FILE_NAME)
    private val recent = HashMap<String, Long>()

    @Synchronized
    fun append(fqdn: String, qtype: String) {
        val now = System.currentTimeMillis()
        val last = recent[fqdn]
        if (last != null && now - last < DEDUP_MS) return
        if (recent.size > 5000) recent.entries.removeIf { now - it.value >= DEDUP_MS }
        recent[fqdn] = now

        if (file.length() > MAX_BYTES) {
            // 世代は 1 つだけ保持する(dns_access.log.1 を上書き)
            file.renameTo(File(file.parentFile, "$FILE_NAME.1"))
        }
        val ts = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        file.appendText("$ts\t$fqdn\t$qtype\n")
    }

    fun tail(maxLines: Int): String =
        if (!file.exists()) "" else file.readLines().takeLast(maxLines).joinToString("\n")

    companion object {
        const val FILE_NAME = "dns_access.log"
        private const val MAX_BYTES = 5L * 1024 * 1024
        private const val DEDUP_MS = 60_000L
    }
}
