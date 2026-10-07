package com.github.rockyx2000.dnslogger

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AccessLogTest {
    @Before @After fun reset() = resetAppState()

    private fun lines(f: File) = if (f.exists()) f.readLines() else emptyList()

    @Test fun 追記するとタブ区切りで時刻_FQDN_種別が残る() {
        val log = AccessLog(ctx)
        log.append("example.com", "A")
        val cols = lines(log.file).single().split('\t')
        assertEquals(listOf("example.com", "A"), cols.drop(1))
        // 時刻は ISO 8601(オフセット付き)で、Summary が読める形である
        assertTrue(runCatching { java.time.OffsetDateTime.parse(cols[0]) }.isSuccess)
    }

    @Test fun 同じFQDNは60秒以内なら1回とみなす() {
        val log = AccessLog(ctx)
        log.append("example.com", "A")
        log.append("example.com", "AAAA")      // A / AAAA / HTTPS の連続クエリ
        log.append("example.com", "HTTPS")
        assertEquals(1, lines(log.file).size)
    }

    @Test fun 別のFQDNは別々に記録する() {
        val log = AccessLog(ctx)
        log.append("a.example.com", "A")
        log.append("b.example.com", "A")
        assertEquals(listOf("a.example.com", "b.example.com"), lines(log.file).map { it.split('\t')[1] })
    }

    @Test fun ログが5MBを超えたら退避して新しいファイルに書く() {
        val log = AccessLog(ctx)
        log.file.writeText("x".repeat(5 * 1024 * 1024 + 10))
        log.append("example.com", "A")
        val old = File(log.file.parentFile, "${AccessLog.FILE_NAME}.1")
        assertTrue("退避ファイルがある", old.exists())
        assertTrue(old.length() > 5L * 1024 * 1024)
        assertEquals("新しいファイルには 1 行だけ", 1, lines(log.file).size)
    }

    @Test fun 退避は1世代だけで古いものは上書きする() {
        val log = AccessLog(ctx)
        val old = File(log.file.parentFile, "${AccessLog.FILE_NAME}.1")
        old.writeText("古い世代\n")
        log.file.writeText("y".repeat(5 * 1024 * 1024 + 10))
        log.append("example.com", "A")
        assertFalse(old.readText().startsWith("古い世代"))
    }

    @Test fun tailは末尾の指定行数を返し_ファイルがなければ空() {
        val log = AccessLog(ctx)
        assertEquals("", log.tail(10))
        log.file.writeText((1..5).joinToString("\n") { "line$it" } + "\n")
        assertEquals("line4\nline5", log.tail(2))
    }

    @Test fun 現在と退避の両方のファイルを集計する() {
        val log = AccessLog(ctx)
        val t = java.time.ZonedDateTime.now().minusHours(1).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        File(log.file.parentFile, "${AccessLog.FILE_NAME}.1").writeText("$t\told.example.com\tA\n")
        log.file.writeText("$t\tnew.example.com\tA\n")
        val rows = Summary.aggregate(ctx, 0L, Long.MAX_VALUE)
        assertEquals(setOf("old.example.com", "new.example.com"), rows.map { it.fqdn }.toSet())
    }
}
