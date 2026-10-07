package com.github.rockyx2000.dnslogger

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HealthTest {
    private val min = 60_000L
    private fun prefs() = ctx.getSharedPreferences("health", Context.MODE_PRIVATE)
    private fun setBeat(ms: Long) = prefs().edit().putLong("last_beat", ms).commit()
    private fun now() = System.currentTimeMillis()

    @Before @After fun reset() = resetAppState()

    @Test fun 直前の生存確認から3分以内なら途切れとしない() {
        setBeat(now() - 2 * min)
        Health.onStart(ctx)
        assertTrue(Health.gaps(ctx, 0L, Long.MAX_VALUE).isEmpty())
    }

    @Test fun 生存確認が3分を超えて空いていたら途切れとして記録する() {
        val last = now() - 30 * min
        setBeat(last)
        Health.onStart(ctx)
        val gaps = Health.gaps(ctx, 0L, Long.MAX_VALUE)
        assertEquals(1, gaps.size)
        assertEquals(last, gaps[0].first)
        assertTrue(gaps[0].second - gaps[0].first in 29 * min..31 * min)
    }

    @Test fun 初回起動や_ユーザー停止の後は途切れとしない() {
        Health.onStart(ctx)                        // 生存確認がまだない
        assertTrue(Health.gaps(ctx, 0L, Long.MAX_VALUE).isEmpty())

        setBeat(now() - 60 * min)
        Health.onUserStop(ctx)                     // 意図した停止
        Health.onStart(ctx)
        assertTrue(Health.gaps(ctx, 0L, Long.MAX_VALUE).isEmpty())
    }

    @Test fun 解除されたら記録し_再開で消える() {
        Health.onRevoked(ctx)
        assertTrue(Health.revokedAt(ctx) > 0)
        Health.onStart(ctx)
        assertEquals(0L, Health.revokedAt(ctx))
    }

    @Test fun 解除の後の空白は途切れとして数えない() {
        setBeat(now() - 60 * min)
        Health.onRevoked(ctx)                      // 生存確認を消す(停止中として別に報告する)
        Health.onStart(ctx)
        assertTrue(Health.gaps(ctx, 0L, Long.MAX_VALUE).isEmpty())
    }

    @Test fun 開始状態なのにサービスが動いていなければ_今まさに止まっている分も報告する() {
        DnsVpnService.setEnabled(ctx, true)        // このプロセスでは、サービスは動いていない
        val last = now() - 20 * min
        setBeat(last)
        val gaps = Health.gaps(ctx, 0L, Long.MAX_VALUE)
        assertEquals(1, gaps.size)
        assertEquals(last, gaps[0].first)
    }

    @Test fun 開始状態でなければ_止まっていても報告しない() {
        DnsVpnService.setEnabled(ctx, false)
        setBeat(now() - 20 * min)
        assertTrue(Health.gaps(ctx, 0L, Long.MAX_VALUE).isEmpty())
    }

    @Test fun 範囲で切り詰めて返す() {
        val last = now() - 60 * min
        setBeat(last)
        Health.onStart(ctx)                        // [last, 今] を記録
        val from = last + 10 * min
        val to = last + 20 * min
        assertEquals(listOf(from to to), Health.gaps(ctx, from, to))
    }

    @Test fun 途切れは14日より古いものを捨て_件数は50件まで() {
        val day = 24 * 60 * min
        prefs().edit().putString("gaps", "${now() - 20 * day},${now() - 19 * day}").commit()
        setBeat(now() - 30 * min)
        Health.onStart(ctx)
        val all = Health.gaps(ctx, 0L, Long.MAX_VALUE)
        assertEquals("古い 1 件は捨てられ、新しい 1 件だけ残る", 1, all.size)

        val many = (1..60).joinToString(";") { i -> "${now() - i * 60 * min},${now() - i * 60 * min + 10 * min}" }
        prefs().edit().putString("gaps", many).commit()
        setBeat(now() - 30 * min)
        Health.onStart(ctx)
        assertTrue(Health.gaps(ctx, 0L, Long.MAX_VALUE).size <= 50)
    }
}
