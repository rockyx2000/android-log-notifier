package com.github.rockyx2000.dnslogger

import android.app.PendingIntent
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SummarySchedulerTest {
    private fun pending(flags: Int) =
        PendingIntent.getBroadcast(ctx, 100, Intent(ctx, SummaryReceiver::class.java), flags or PendingIntent.FLAG_IMMUTABLE)

    private fun registered() = PendingIntent.getBroadcast(
        ctx, 100, Intent(ctx, SummaryReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    ) != null

    private fun cancel() { pending(PendingIntent.FLAG_UPDATE_CURRENT).cancel() }

    @Before @After fun reset() = cancel()

    @Test fun 次の23時のアラームを登録できる() {
        assertFalse(registered())
        SummaryScheduler.scheduleNext(ctx)
        assertTrue(registered())
    }

    @Test fun 再試行のアラームを登録できる() {
        SummaryScheduler.scheduleRetry(ctx)
        assertTrue(registered())
    }

    @Test fun 二重に登録しても1つだけ_上書きになる() {
        SummaryScheduler.scheduleNext(ctx)
        SummaryScheduler.scheduleNext(ctx)
        assertTrue(registered())
        cancel()
        assertFalse(registered())
    }
}
