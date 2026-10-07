package com.example.dnslogger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.ZonedDateTime

/** 毎日 23:00 のサマリ送信アラーム。再起動で消えるので BootReceiver からも登録し直す。 */
object SummaryScheduler {
    private const val RETRY_MS = 15 * 60 * 1000L

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 100, Intent(ctx, SummaryReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun scheduleNext(ctx: Context) =
        scheduleAt(ctx, Summary.nextBoundary(ZonedDateTime.now()).toInstant().toEpochMilli())

    fun scheduleRetry(ctx: Context) = scheduleAt(ctx, System.currentTimeMillis() + RETRY_MS)

    fun canScheduleExact(ctx: Context): Boolean =
        ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun scheduleAt(ctx: Context, atMs: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending(ctx))
        } else {
            // 「正確なアラーム」未許可: 数分〜数十分ずれることがある
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending(ctx))
        }
    }
}
