package com.github.rockyx2000.dnslogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SummaryTest {
    private val jst = ZoneId.of("Asia/Tokyo")
    private fun at(s: String, zone: ZoneId = jst) = ZonedDateTime.parse(s).withZoneSameInstant(zone)
    private fun ms(s: String) = ZonedDateTime.parse(s).toInstant().toEpochMilli()

    // ---------- 23:00 の区切り ----------

    @Test fun `区切りは直近の 23時(それより前の時刻なら前日)`() {
        assertEquals(at("2026-10-06T23:00:00+09:00"), Summary.lastBoundary(at("2026-10-07T14:00:00+09:00")))
        assertEquals(at("2026-10-06T23:00:00+09:00"), Summary.lastBoundary(at("2026-10-07T00:00:00+09:00")))
        assertEquals(at("2026-10-06T23:00:00+09:00"), Summary.lastBoundary(at("2026-10-07T22:59:59+09:00")))
    }

    @Test fun `ちょうど 23時とそれ以降は当日の 23時`() {
        assertEquals(at("2026-10-07T23:00:00+09:00"), Summary.lastBoundary(at("2026-10-07T23:00:00+09:00")))
        assertEquals(at("2026-10-07T23:00:00+09:00"), Summary.lastBoundary(at("2026-10-07T23:59:59+09:00")))
    }

    @Test fun `次の区切りは 24 時間後ではなく翌日の 23時(夏時間の日でも)`() {
        val ny = ZoneId.of("America/New_York")                 // 2026-03-08 に夏時間へ切り替わる(その日は 23 時間)
        val now = at("2026-03-08T12:00:00-04:00", ny)
        assertEquals(23, Summary.lastBoundary(now).hour)
        assertEquals(at("2026-03-07T23:00:00-05:00", ny), Summary.lastBoundary(now))
        assertEquals(at("2026-03-08T23:00:00-04:00", ny), Summary.nextBoundary(now))
    }

    // ---------- 何を送るかの判断 ----------

    private val now = at("2026-10-07T23:00:05+09:00")           // アラームが少し遅れて発火した状況
    private val boundary = ms("2026-10-07T23:00:00+09:00")

    @Test fun `初回(前回の終端なし)は起点を記録するだけで送らない`() {
        val plan = Summary.plan(now, 0L) as Summary.Plan.Init
        assertEquals(boundary, plan.boundaryMs)
    }

    @Test fun `直近の 23時の分を送信済みなら何もしない`() {
        assertTrue(Summary.plan(now, boundary) === Summary.Plan.AlreadySent)
        assertTrue(Summary.plan(now, boundary + 1) === Summary.Plan.AlreadySent)
    }

    @Test fun `前回の終端から直近の 23時までを送る`() {
        val last = ms("2026-10-06T23:00:00+09:00")
        val plan = Summary.plan(now, last) as Summary.Plan.Send
        assertEquals(last, plan.startMs)
        assertEquals(boundary, plan.endMs)
    }

    @Test fun `送れていない日が複数あっても、前回の終端から 1 通にまとめて取りこぼさない`() {
        val last = ms("2026-10-03T23:00:00+09:00")
        val plan = Summary.plan(now, last) as Summary.Plan.Send
        assertEquals(last, plan.startMs)
        assertEquals(boundary, plan.endMs)
    }

    @Test fun `23時の前に起動した場合は、前日の区切りまでが対象`() {
        val early = at("2026-10-07T14:00:00+09:00")
        val plan = Summary.plan(early, ms("2026-10-05T23:00:00+09:00")) as Summary.Plan.Send
        assertEquals(ms("2026-10-06T23:00:00+09:00"), plan.endMs)
    }

    // ---------- 集計 ----------

    private fun line(ts: String, fqdn: String, type: String = "A") = "$ts\t$fqdn\t$type"
    private val start = ms("2026-10-06T23:00:00+09:00")
    private val end = ms("2026-10-07T23:00:00+09:00")

    @Test fun `期間は開始を含み終了を含まない`() {
        val rows = Summary.aggregate(sequenceOf(
            line("2026-10-06T22:59:59+09:00", "before.com"),
            line("2026-10-06T23:00:00+09:00", "start.com"),
            line("2026-10-07T22:59:59+09:00", "inside.com"),
            line("2026-10-07T23:00:00+09:00", "end.com"),
        ), start, end)
        assertEquals(listOf("inside.com", "start.com"), rows.map { it.fqdn })
    }

    @Test fun `回数の多い順、同数なら名前順で、最終時刻は最も新しいもの`() {
        val rows = Summary.aggregate(sequenceOf(
            line("2026-10-07T01:00:00+09:00", "b.com"),
            line("2026-10-07T09:00:00+09:00", "a.com"),
            line("2026-10-07T03:00:00+09:00", "a.com"),
            line("2026-10-07T02:00:00+09:00", "c.com"),
            line("2026-10-07T05:00:00+09:00", "a.com"),
        ), start, end)
        assertEquals(listOf("a.com", "b.com", "c.com"), rows.map { it.fqdn })
        assertEquals(3, rows[0].count)
        assertEquals(ms("2026-10-07T09:00:00+09:00"), rows[0].last)
    }

    @Test fun `壊れた行は読み飛ばす`() {
        val rows = Summary.aggregate(sequenceOf(
            "",
            "ゴミ",
            "not-a-date\tx.com\tA",
            "2026-10-07T01:00:00+09:00",                       // 列が足りない
            line("2026-10-07T01:00:00+09:00", "ok.com"),
        ), start, end)
        assertEquals(listOf("ok.com"), rows.map { it.fqdn })
    }

    @Test fun `ミリ秒付きや別のタイムゾーンの時刻も読める`() {
        val rows = Summary.aggregate(sequenceOf(
            line("2026-10-07T14:01:58.484273+09:00", "ms.com"),
            line("2026-10-07T05:01:58Z", "utc.com"),          // JST の 14:01:58
        ), start, end)
        assertEquals(setOf("ms.com", "utc.com"), rows.map { it.fqdn }.toSet())
    }

    @Test fun `ログが空なら空の集計`() {
        assertTrue(Summary.aggregate(emptySequence(), start, end).isEmpty())
    }

    // ---------- 本文 ----------

    private fun rows(n: Int) = (1..n).map { Summary.Row("domain-number-$it.example.com", n - it + 1, end - 1000L * it) }

    @Test fun `アクセスがない場合の本文`() {
        val text = Summary.format(emptyList(), start, end, test = false, zone = jst)
        assertTrue(text.contains("期間: 10/6 23:00 〜 10/7 23:00"))
        assertTrue(text.contains("対象ドメインへのアクセスはありませんでした"))
        assertFalse(text.contains("【テスト】"))
    }

    @Test fun `テスト送信には印が付く`() {
        assertTrue(Summary.format(emptyList(), start, end, test = true, zone = jst).startsWith("【テスト】"))
    }

    @Test fun `合計とドメイン数と各行を載せる`() {
        val text = Summary.format(listOf(Summary.Row("a.com", 3, end - 60_000), Summary.Row("b.com", 1, end - 120_000)),
            start, end, test = false, zone = jst)
        assertTrue(text.contains("合計 4 回 / 2 ドメイン"))
        assertTrue(text.contains("3回  a.com  (最終 10/7 22:59)"))
        assertTrue(text.contains("1回  b.com  (最終 10/7 22:58)"))
    }

    @Test fun `Discord の上限 2000 文字に収め、省略した件数を示す`() {
        val text = Summary.format(rows(500), start, end, test = false, zone = jst)
        assertTrue("長さ=${text.length}", text.length <= 2000)
        assertTrue(text.contains("…他 "))
        assertTrue(text.contains("domain-number-1.example.com"))          // 上位は残る
    }

    @Test fun `途切れと停止の警告が付いても 2000 文字に収まる`() {
        val gaps = (1..8).map { (start + it * 3_600_000L) to (start + it * 3_600_000L + 600_000L) }
        val text = Summary.format(rows(500), start, end, test = false, gaps = gaps, revokedAt = end - 10_000, zone = jst)
        assertTrue("長さ=${text.length}", text.length <= 2000)
        assertTrue(text.contains("記録が途切れた可能性: 8 回 / 計 80 分"))
        assertTrue(text.contains("…他 3 回"))                                // 先頭 5 件だけ載せる
        assertTrue(text.contains("VPN が他のアプリ等に解除され停止中"))
    }

    @Test fun `途切れがなければ警告は出さない`() {
        val text = Summary.format(rows(3), start, end, test = false, zone = jst)
        assertFalse(text.contains("⚠"))
    }
}
