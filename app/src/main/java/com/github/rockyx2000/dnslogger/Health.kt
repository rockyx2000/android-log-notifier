package com.github.rockyx2000.dnslogger

import android.content.Context

/**
 * 記録の健全性の管理。サービスが一定間隔で生存時刻を書き、OS に落とされて再開した時に
 * 「最後の生存時刻〜再開」を途切れ(gap)として残す。サマリで報告するために使う。
 */
object Health {
    const val BEAT_INTERVAL_MS = 60_000L
    private const val GAP_MS = 3 * 60_000L          // これ以上空いたら途切れとみなす
    private const val KEEP_MS = 14 * 24 * 3600_000L
    private const val MAX_GAPS = 50

    private fun p(ctx: Context) = Storage.prefs(ctx, "health")   // ロック解除前から書く(DE)

    fun beat(ctx: Context) = p(ctx).edit().putLong("last_beat", System.currentTimeMillis()).apply()

    /** サービス開始時。前回の生存時刻から空いていたら途切れとして記録する。 */
    @Synchronized
    fun onStart(ctx: Context) {
        val now = System.currentTimeMillis()
        val last = p(ctx).getLong("last_beat", 0L)
        if (last > 0 && now - last > GAP_MS) addGap(ctx, last, now)
        p(ctx).edit().putLong("revoked_at", 0L).apply()
        beat(ctx)
    }

    /** ユーザー操作による停止。以後の空白は途切れではない。 */
    fun onUserStop(ctx: Context) = p(ctx).edit().putLong("last_beat", 0L).apply()

    /** 他の VPN アプリ等に奪われて OS から解除された。ユーザーが再開するまで停止中として報告する。 */
    fun onRevoked(ctx: Context) =
        p(ctx).edit().putLong("last_beat", 0L).putLong("revoked_at", System.currentTimeMillis()).apply()

    fun revokedAt(ctx: Context): Long = p(ctx).getLong("revoked_at", 0L)

    private fun addGap(ctx: Context, from: Long, to: Long) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val all = (parse(ctx) + (from to to)).filter { it.second > cutoff }.takeLast(MAX_GAPS)
        p(ctx).edit().putString("gaps", all.joinToString(";") { "${it.first},${it.second}" }).apply()
    }

    private fun parse(ctx: Context): List<Pair<Long, Long>> =
        (p(ctx).getString("gaps", "") ?: "").split(';').mapNotNull {
            val c = it.split(',')
            if (c.size == 2) (c[0].toLongOrNull() ?: return@mapNotNull null) to (c[1].toLongOrNull() ?: return@mapNotNull null) else null
        }

    /** [startMs, endMs) と重なる途切れ(範囲内に切り詰め)。サービスが今まさに落ちている分も含む。 */
    fun gaps(ctx: Context, startMs: Long, endMs: Long): List<Pair<Long, Long>> {
        val list = parse(ctx).toMutableList()
        val last = p(ctx).getLong("last_beat", 0L)
        val now = System.currentTimeMillis()
        if (DnsVpnService.isEnabled(ctx) && !DnsVpnService.running && last > 0 && now - last > GAP_MS) list += last to now
        return clip(list, startMs, endMs)
    }

    /** [startMs, endMs) と重なる区間だけを、範囲内に切り詰めて返す。 */
    internal fun clip(gaps: List<Pair<Long, Long>>, startMs: Long, endMs: Long): List<Pair<Long, Long>> =
        gaps.mapNotNull {
            val a = maxOf(it.first, startMs)
            val b = minOf(it.second, endMs)
            if (b > a) a to b else null
        }
}
