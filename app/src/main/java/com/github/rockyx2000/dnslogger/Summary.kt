package com.github.rockyx2000.dnslogger

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** ログの集計と Discord 向け本文の組み立て。毎日の区切りは端末ローカル時間の 23:00。 */
object Summary {
    private const val HOUR = 23
    private const val DISCORD_LIMIT = 2000

    /** [now] 以前で最も新しい 23:00。 */
    fun lastBoundary(now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate().atTime(HOUR, 0).atZone(now.zone)
        return if (!now.isBefore(today)) today else today.minusDays(1)
    }

    fun nextBoundary(now: ZonedDateTime): ZonedDateTime = lastBoundary(now).plusDays(1)

    class Row(val fqdn: String, val count: Int, val last: Long)

    /** [startMs, endMs) の範囲を FQDN ごとに集計する(回数の多い順)。 */
    fun aggregate(ctx: Context, startMs: Long, endMs: Long): List<Row> {
        val dir = ctx.filesDir
        val lines = listOf(File(dir, "${AccessLog.FILE_NAME}.1"), File(dir, AccessLog.FILE_NAME))
            .filter { it.exists() }
            .asSequence()
            .flatMap { it.readLines().asSequence() }
        return aggregate(lines, startMs, endMs)
    }

    /** ログの行(`時刻⇥FQDN⇥種別`)を集計する。壊れた行は読み飛ばす。 */
    fun aggregate(lines: Sequence<String>, startMs: Long, endMs: Long): List<Row> {
        val counts = HashMap<String, Int>()
        val lasts = HashMap<String, Long>()
        for (line in lines) {
            val cols = line.split('\t')
            if (cols.size < 2) continue
            val ts = runCatching { OffsetDateTime.parse(cols[0]).toInstant().toEpochMilli() }.getOrNull() ?: continue
            if (ts < startMs || ts >= endMs) continue
            counts.merge(cols[1], 1, Int::plus)
            lasts.merge(cols[1], ts, ::maxOf)
        }
        return counts.map { Row(it.key, it.value, lasts.getValue(it.key)) }
            .sortedWith(compareByDescending<Row> { it.count }.thenBy { it.fqdn })
    }

    /** 23:00 のアラームなどで起動したとき、何をするか。 */
    sealed interface Plan {
        /** 初回: 直近の 23:00 を起点として記録するだけで、送らない。 */
        class Init(val boundaryMs: Long) : Plan
        /** 直近の 23:00 の分は送信済み。 */
        object AlreadySent : Plan
        /** 前回の終端 [startMs] から [endMs] までを送る(送信漏れがあっても取りこぼさない)。 */
        class Send(val startMs: Long, val endMs: Long) : Plan
    }

    fun plan(now: ZonedDateTime, lastEndMs: Long): Plan {
        val boundary = lastBoundary(now).toInstant().toEpochMilli()
        return when {
            lastEndMs == 0L -> Plan.Init(boundary)
            lastEndMs >= boundary -> Plan.AlreadySent
            else -> Plan.Send(lastEndMs, boundary)
        }
    }

    fun format(
        rows: List<Row>, startMs: Long, endMs: Long, test: Boolean,
        gaps: List<Pair<Long, Long>> = emptyList(), revokedAt: Long = 0L,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val range = DateTimeFormatter.ofPattern("M/d HH:mm")
        val hm = DateTimeFormatter.ofPattern("M/d HH:mm")
        fun t(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
        val head = StringBuilder()
        head.append(if (test) "【テスト】" else "").append("DNS アクセスサマリ\n")
        head.append("期間: ${range.format(t(startMs))} 〜 ${range.format(t(endMs))}\n")
        if (revokedAt > 0) head.append("⚠ VPN が他のアプリ等に解除され停止中です(${hm.format(t(revokedAt))}〜)。アプリを開いて再開してください\n")
        if (gaps.isNotEmpty()) {
            val mins = gaps.sumOf { (it.second - it.first) / 60_000 }
            head.append("⚠ 記録が途切れた可能性: ${gaps.size} 回 / 計 ${mins} 分\n")
            gaps.take(5).forEach { head.append("  ${hm.format(t(it.first))} 〜 ${hm.format(t(it.second))}\n") }
            if (gaps.size > 5) head.append("  …他 ${gaps.size - 5} 回\n")
        }
        if (rows.isEmpty()) return head.append("対象ドメインへのアクセスはありませんでした").toString()
        head.append("合計 ${rows.sumOf { it.count }} 回 / ${rows.size} ドメイン\n")

        val body = StringBuilder()
        var shown = 0
        for (r in rows) {
            val line = "${r.count.toString().padStart(4)}回  ${r.fqdn}  (最終 ${hm.format(t(r.last))})\n"
            // 先頭 + コードブロック記号 + 「他 N 件」の余白を残して収める
            if (head.length + body.length + line.length + 40 > DISCORD_LIMIT) break
            body.append(line)
            shown++
        }
        val out = StringBuilder(head).append("```\n").append(body).append("```")
        if (shown < rows.size) out.append("\n…他 ${rows.size - shown} ドメイン")
        return out.toString()
    }
}
