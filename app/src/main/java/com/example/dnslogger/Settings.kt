package com.example.dnslogger

import android.content.Context

/** 設定と状態の保存(SharedPreferences)。 */
object Settings {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun domains(ctx: Context): String = p(ctx).getString("domains", "") ?: ""
    fun webhook(ctx: Context): String = p(ctx).getString("webhook", "") ?: ""
    /** 直近で送信済みの集計期間の終端(epoch ms)。未送信なら 0。 */
    fun lastEnd(ctx: Context): Long = p(ctx).getLong("last_end", 0L)
    fun status(ctx: Context): String = p(ctx).getString("status", "") ?: ""

    fun save(ctx: Context, domains: String, webhook: String) =
        p(ctx).edit().putString("domains", domains).putString("webhook", webhook.trim()).apply()

    fun setLastEnd(ctx: Context, v: Long) = p(ctx).edit().putLong("last_end", v).apply()
    fun setStatus(ctx: Context, v: String) = p(ctx).edit().putString("status", v).apply()
}
