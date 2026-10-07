package com.github.rockyx2000.dnslogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {
    private val s = 1_000L
    private val e = 2_000L

    @Test fun `範囲内の区間はそのまま`() {
        assertEquals(listOf(1_200L to 1_500L), Health.clip(listOf(1_200L to 1_500L), s, e))
    }

    @Test fun `範囲をまたぐ区間は範囲内に切り詰める`() {
        assertEquals(listOf(s to 1_300L), Health.clip(listOf(500L to 1_300L), s, e))
        assertEquals(listOf(1_700L to e), Health.clip(listOf(1_700L to 2_500L), s, e))
        assertEquals(listOf(s to e), Health.clip(listOf(0L to 3_000L), s, e))
    }

    @Test fun `範囲の外や、端が接するだけの区間は捨てる`() {
        assertTrue(Health.clip(listOf(0L to 500L, 2_500L to 3_000L), s, e).isEmpty())
        assertTrue(Health.clip(listOf(500L to s), s, e).isEmpty())       // 終わりが開始にちょうど接する
        assertTrue(Health.clip(listOf(e to 3_000L), s, e).isEmpty())     // 始まりが終了にちょうど接する
    }

    @Test fun `複数の区間を順序を保って処理する`() {
        val out = Health.clip(listOf(1_100L to 1_200L, 0L to 10L, 1_900L to 2_100L), s, e)
        assertEquals(listOf(1_100L to 1_200L, 1_900L to e), out)
    }
}
