package com.github.rockyx2000.dnslogger

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import java.io.File

/**
 * 保存先の使い分け。
 *
 *  - **端末保護ストレージ(DE)**: 端末を起動した直後、ロックを解除する前から読み書きできる。
 *    ロック解除前に VPN を再開するために必要な、最小限の状態だけを置く。
 *    動作状態(state)、生存確認(health)、記録対象のドメイン(filter)。
 *  - **認証情報保護ストレージ(CE)**: ロックを解除するまで読めない。Webhook URL・送信状態・
 *    アクセスログは、こちらに置く(ロック解除前に端末へ触れた人から守る)。
 *
 * ロック中に起きた記録は、DE の一時バッファに書き、解除後に CE のログへ移す(→ docs/adr/0011)。
 */
object Storage {
    /** ロック解除前から使える保存領域のコンテキスト。 */
    fun de(ctx: Context): Context = ctx.createDeviceProtectedStorageContext()

    fun unlocked(ctx: Context): Boolean = ctx.getSystemService(UserManager::class.java).isUserUnlocked

    /** ロック解除前から要る状態の SharedPreferences(state / health / filter)。 */
    fun prefs(ctx: Context, name: String): SharedPreferences =
        de(ctx).getSharedPreferences(name, Context.MODE_PRIVATE)

    /** ロック中の記録を溜めるバッファ(DE)。 */
    fun pendingFile(ctx: Context): File = File(de(ctx).filesDir, "dns_access.pending.log")

    /**
     * ロック解除後に呼ぶ。従来(CE)に置いていた状態を DE へ移し、ロック中のバッファをログへ取り込む。
     * 何度呼んでも安全(移す対象が無ければ何もしない)。
     */
    @Synchronized
    fun onUnlocked(ctx: Context) {
        if (!unlocked(ctx)) return
        for (name in listOf("state", "health")) migratePrefs(ctx, name)
        Settings.migrateDomains(ctx)
        AccessLog(ctx).drainPending()
    }

    /**
     * 更新前に CE へ置いていた [name] を DE へ移す。DE に無い項目だけを CE の値で補い(DE の新しい値を壊さない)、
     * CE 側は消す。DE 側にファイルが先にできていても、取りこぼさない。
     */
    private fun migratePrefs(ctx: Context, name: String) {
        if (!File(ctx.dataDir, "shared_prefs/$name.xml").exists()) return
        val old = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all
        val dst = prefs(ctx, name)
        val e = dst.edit()
        for ((k, v) in old) {
            if (dst.contains(k)) continue
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is String -> e.putString(k, v)
            }
        }
        // 更新前の利用者: 開始状態だったかどうかを、「開いたら自動で開始するか」に引き継ぐ
        if (name == "state" && !dst.contains("auto_start")) {
            e.putBoolean("auto_start", (old["enabled"] as? Boolean) ?: false)
        }
        e.commit()
        ctx.deleteSharedPreferences(name)
    }
}
