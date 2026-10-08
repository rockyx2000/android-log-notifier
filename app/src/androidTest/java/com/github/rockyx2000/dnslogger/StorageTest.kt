package com.github.rockyx2000.dnslogger

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** ロック解除前の回復のための保存先(DE)と、更新前の状態の引き継ぎ。 */
class StorageTest {
    @Before @After fun reset() {
        resetAppState()
        File(ctx.dataDir, "shared_prefs/state.xml").delete()
        File(ctx.dataDir, "shared_prefs/health.xml").delete()
    }

    @Test fun 記録対象のドメインは端末保護ストレージに保存される() {
        Settings.save(ctx, "example.com", "https://example.invalid/hook")
        assertEquals("example.com", Storage.prefs(ctx, "filter").getString("domains", null))
        assertEquals("example.com", Settings.domains(ctx))
        // Webhook は DE に置かない(ロック解除前に読めてはいけない秘密)
        assertFalse(Storage.prefs(ctx, "filter").all.values.any { it.toString().contains("example.invalid") })
    }

    @Test fun 更新前にCEへ置いたドメインを引き継ぎ_CE側は消す() {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("domains", "old.example.com").commit()
        Settings.migrateDomains(ctx)
        assertEquals("old.example.com", Settings.domains(ctx))
        assertFalse(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).contains("domains"))
    }

    @Test fun すでにDEにあれば_CEの古い値で上書きしない() {
        Settings.save(ctx, "new.example.com", "")
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("domains", "old.example.com").commit()
        Settings.migrateDomains(ctx)
        assertEquals("new.example.com", Settings.domains(ctx))
    }

    @Test fun 更新前のstateとhealthをDEへ移す() {
        ctx.getSharedPreferences("state", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
        ctx.getSharedPreferences("health", Context.MODE_PRIVATE).edit().putLong("last_beat", 123L).commit()
        Storage.onUnlocked(ctx)
        assertTrue(DnsVpnService.isEnabled(ctx))
        assertEquals(123L, Storage.prefs(ctx, "health").getLong("last_beat", 0L))
        assertFalse("移した後、CE のファイルは残らない", File(ctx.dataDir, "shared_prefs/state.xml").exists())
    }

    @Test fun 更新前に開始状態だった人は_開いたら自動で開始し_停止していた人は自動で開始しない() {
        ctx.getSharedPreferences("state", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
        Storage.onUnlocked(ctx)
        assertTrue(DnsVpnService.autoStart(ctx))

        resetAppState(); File(ctx.dataDir, "shared_prefs/state.xml").delete()
        ctx.getSharedPreferences("state", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).commit()
        Storage.onUnlocked(ctx)
        assertFalse(DnsVpnService.autoStart(ctx))
    }

    @Test fun 新規インストールは自動で開始する() {
        assertTrue(DnsVpnService.autoStart(ctx))
    }

    @Test fun 自動開始のフラグを切り替えられる() {
        DnsVpnService.setAutoStart(ctx, false)
        assertFalse(DnsVpnService.autoStart(ctx))
        DnsVpnService.setAutoStart(ctx, true)
        assertTrue(DnsVpnService.autoStart(ctx))
    }

    @Test fun ロック中のバッファはログの先頭側へ取り込まれ_バッファは消える() {
        val log = AccessLog(ctx)
        Storage.pendingFile(ctx).writeText("2026-10-07T01:00:00+09:00\tpending.example.com\tA\n")
        log.append("now.example.com", "A")
        val names = log.file.readLines().map { it.split('\t')[1] }
        assertEquals(listOf("pending.example.com", "now.example.com"), names)
        assertFalse(Storage.pendingFile(ctx).exists())
    }

    @Test fun drainPendingだけでも取り込める() {
        val log = AccessLog(ctx)
        Storage.pendingFile(ctx).writeText("2026-10-07T01:00:00+09:00\tpending.example.com\tA\n")
        log.drainPending()
        assertEquals(1, log.file.readLines().size)
        assertFalse(Storage.pendingFile(ctx).exists())
    }

    @Test fun この端末はロック解除済み() {
        assertTrue(Storage.unlocked(ctx))
    }
}
