package com.github.rockyx2000.dnslogger

import android.content.Context

/**
 * 設定と状態の保存(SharedPreferences)。
 * 記録対象のドメインだけは、ロック解除前の VPN にも要るので DE に置く。他は CE(→ [Storage])。
 */
object Settings {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun filter(ctx: Context) = Storage.prefs(ctx, "filter")

    fun domains(ctx: Context): String = filter(ctx).getString("domains", "") ?: ""

    /** 更新前(CE の settings に domains があった)の値を、DE へ引き継ぐ。ロック解除後に呼ぶ。 */
    fun migrateDomains(ctx: Context) {
        if (filter(ctx).contains("domains")) return
        val old = p(ctx).getString("domains", null) ?: return
        filter(ctx).edit().putString("domains", old).apply()
        p(ctx).edit().remove("domains").apply()
    }
    fun webhook(ctx: Context): String = p(ctx).getString("webhook", "") ?: ""
    /** 直近で送信済みの集計期間の終端(epoch ms)。未送信なら 0。 */
    fun lastEnd(ctx: Context): Long = p(ctx).getLong("last_end", 0L)
    fun status(ctx: Context): String = p(ctx).getString("status", "") ?: ""

    fun save(ctx: Context, domains: String, webhook: String) {
        filter(ctx).edit().putString("domains", domains).apply()
        p(ctx).edit().putString("webhook", webhook.trim()).apply()
    }

    fun setLastEnd(ctx: Context, v: Long) = p(ctx).edit().putLong("last_end", v).apply()
    fun setStatus(ctx: Context, v: String) = p(ctx).edit().putString("status", v).apply()
}
