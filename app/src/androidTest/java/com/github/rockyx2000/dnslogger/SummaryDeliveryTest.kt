package com.github.rockyx2000.dnslogger

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** SummaryReceiver.run → Summary → DiscordNotifier の経路を、端末内の偽 Webhook で確かめる。 */
class SummaryDeliveryTest {
    private lateinit var hook: FakeWebhook
    private val boundary get() = Summary.lastBoundary(ZonedDateTime.now()).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    @Before fun setUp() {
        resetAppState()
        hook = FakeWebhook()
        Settings.save(ctx, "example.com", hook.url)
    }

    @After fun tearDown() { hook.close(); resetAppState() }

    private fun log(vararg entries: Pair<Long, String>) {
        val f = File(ctx.filesDir, AccessLog.FILE_NAME)
        f.writeText(entries.joinToString("") { (ms, name) ->
            val t = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            "$t\t$name\tA\n"
        })
    }

    private fun alarmRegistered() = PendingIntent.getBroadcast(
        ctx, 100, Intent(ctx, SummaryReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    ) != null

    // ---------- 手動テスト送信 ----------

    @Test fun テスト送信は直近24時間を集計して送り_成功を状態に残す() {
        // 「今」ちょうどの記録は、終了を含まない範囲から外れうる(同じミリ秒だと)。確実に範囲内に入る時刻で書く
        log((System.currentTimeMillis() - 60_000L) to "example.com")
        SummaryReceiver.run(ctx, true)
        val body = hook.next()!!
        assertTrue(body, body.startsWith("【テスト】"))
        assertTrue(body, body.contains("example.com"))
        assertTrue(Settings.status(ctx), Settings.status(ctx).contains("送信成功(テスト)"))
        assertEquals("テストでは送信済みの印を進めない", 0L, Settings.lastEnd(ctx))
    }

    @Test fun Webhook未設定ならテスト送信は何も送らず_状態にその旨を残す() {
        Settings.save(ctx, "example.com", "")
        SummaryReceiver.run(ctx, true)
        assertNull(hook.next(500))
        assertEquals("Webhook URL が未設定です", Settings.status(ctx))
    }

    // ---------- 毎日の送信 ----------

    @Test fun 前回の終端から直近の23時までを送り_終端を進める() {
        val last = boundary - 24 * hour
        Settings.setLastEnd(ctx, last)
        log((boundary - hour) to "example.com", (boundary + hour) to "after.example.com", (last - hour) to "before.example.com")

        SummaryReceiver.run(ctx, false)

        val body = hook.next()!!
        assertTrue(body, body.contains("example.com"))
        assertTrue("期間外(後)は含めない: $body", !body.contains("after.example.com"))
        assertTrue("期間外(前)は含めない: $body", !body.contains("before.example.com"))
        assertEquals(boundary, Settings.lastEnd(ctx))
        assertTrue("次の 23:00 のアラームが登録される", alarmRegistered())
    }

    @Test fun 何日も送れていなくても_前回の終端から1通にまとめる() {
        val last = boundary - 4 * 24 * hour
        Settings.setLastEnd(ctx, last)
        log((boundary - 3 * 24 * hour) to "old.example.com", (boundary - hour) to "recent.example.com")

        SummaryReceiver.run(ctx, false)

        val body = hook.next()!!
        assertTrue(body, body.contains("old.example.com") && body.contains("recent.example.com"))
        assertNull("1 通だけ", hook.next(300))
        assertEquals(boundary, Settings.lastEnd(ctx))
    }

    @Test fun 送信に失敗したら終端を進めず_再試行を予約する() {
        hook.status = 500
        val last = boundary - 24 * hour
        Settings.setLastEnd(ctx, last)
        log((boundary - hour) to "example.com")

        SummaryReceiver.run(ctx, false)

        assertNotNull(hook.next())
        assertEquals("終端は進めない(次回に同じ範囲を送り直す)", last, Settings.lastEnd(ctx))
        assertTrue(Settings.status(ctx), Settings.status(ctx).contains("送信失敗: HTTP 500"))
        assertTrue("再試行のアラームが登録される", alarmRegistered())
    }

    @Test fun 失敗の後で成功すれば_失敗した分も含めて送られる() {
        val last = boundary - 24 * hour
        Settings.setLastEnd(ctx, last)
        log((boundary - hour) to "example.com")
        hook.status = 500
        SummaryReceiver.run(ctx, false)
        hook.next()

        hook.status = 204
        SummaryReceiver.run(ctx, false)
        val body = hook.next()!!
        assertTrue(body, body.contains("example.com"))
        assertEquals(boundary, Settings.lastEnd(ctx))
    }

    @Test fun 送信済みなら何も送らない() {
        Settings.setLastEnd(ctx, boundary)
        SummaryReceiver.run(ctx, false)
        assertNull(hook.next(500))
        assertEquals(boundary, Settings.lastEnd(ctx))
    }

    @Test fun 初回は起点を記録するだけで送らない() {
        SummaryReceiver.run(ctx, false)
        assertNull(hook.next(500))
        assertEquals(boundary, Settings.lastEnd(ctx))
        assertTrue(alarmRegistered())
    }

    @Test fun 記録が途切れていたら本文で警告する() {
        val last = boundary - 24 * hour
        Settings.setLastEnd(ctx, last)
        ctx.getSharedPreferences("health", Context.MODE_PRIVATE).edit()
            .putString("gaps", "${boundary - 3 * hour},${boundary - 2 * hour}").commit()

        SummaryReceiver.run(ctx, false)

        val body = hook.next()!!
        assertTrue(body, body.contains("記録が途切れた可能性: 1 回 / 計 60 分"))
    }

    @Test fun 解除されて停止中なら本文で警告する() {
        Settings.setLastEnd(ctx, boundary - 24 * hour)
        Health.onRevoked(ctx)
        SummaryReceiver.run(ctx, false)
        assertTrue(hook.next()!!.contains("VPN が他のアプリ等に解除され停止中"))
    }

    @Test fun Webhook未設定でも次のアラームは登録する() {
        Settings.save(ctx, "example.com", "")
        Settings.setLastEnd(ctx, boundary - 24 * hour)
        SummaryReceiver.run(ctx, false)
        assertTrue(alarmRegistered())
        assertEquals("送っていないので終端は進めない", boundary - 24 * hour, Settings.lastEnd(ctx))
    }
}
