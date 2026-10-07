package com.example.dnslogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/** アラームで起動し、サマリを集計して Discord に送る。extra "test" が true なら直近 24 時間をテスト送信。 */
class SummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val test = intent.getBooleanExtra(EXTRA_TEST, false)
        val pending = goAsync()
        thread {
            try {
                run(app, test)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TEST = "test"

        /** アラーム・起動時・手動テストの共通入口。 */
        fun run(ctx: Context, test: Boolean) {
            val webhook = Settings.webhook(ctx)
            val now = ZonedDateTime.now()
            val end: Long
            val start: Long
            if (test) {
                end = now.toInstant().toEpochMilli()
                start = now.minusHours(24).toInstant().toEpochMilli()
            } else {
                val boundary = Summary.lastBoundary(now).toInstant().toEpochMilli()
                val lastEnd = Settings.lastEnd(ctx)
                if (lastEnd == 0L) {
                    // 初回: 直近の 23:00 を集計の起点にするだけで、空のサマリは送らない
                    Settings.setLastEnd(ctx, boundary); SummaryScheduler.scheduleNext(ctx); return
                }
                if (lastEnd >= boundary) { SummaryScheduler.scheduleNext(ctx); return }   // 送信済み
                end = boundary
                start = lastEnd   // 送信漏れがあっても前回の終端から集計する(取りこぼさない)
            }
            if (webhook.isBlank()) {
                if (test) Settings.setStatus(ctx, "Webhook URL が未設定です")
                if (!test) SummaryScheduler.scheduleNext(ctx)
                return
            }

            val rows = Summary.aggregate(ctx, start, end)
            val err = DiscordNotifier.post(webhook, Summary.format(rows, start, end, test, Health.gaps(ctx, start, end), Health.revokedAt(ctx)))
            val at = now.format(DateTimeFormatter.ofPattern("M/d HH:mm"))
            if (err == null) {
                Settings.setStatus(ctx, "$at 送信成功" + if (test) "(テスト)" else "")
                if (!test) { Settings.setLastEnd(ctx, end); SummaryScheduler.scheduleNext(ctx) }
            } else {
                Settings.setStatus(ctx, "$at 送信失敗: $err")
                if (!test) SummaryScheduler.scheduleRetry(ctx)   // 15 分後に再試行(lastEnd は進めない)
            }
        }
    }
}
