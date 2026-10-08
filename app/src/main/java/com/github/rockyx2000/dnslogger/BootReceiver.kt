package com.github.rockyx2000.dnslogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlin.concurrent.thread

/**
 * 再起動後・アプリ更新後、ユーザーが「開始」状態のまま終了していたら VPN を再開する。
 *
 * LOCKED_BOOT_COMPLETED(起動直後、ロック解除前)でも再開する。そのため、このクラスと [DnsVpnService] は
 * directBootAware で、ロック解除前は DE の状態だけを使う。BOOT_COMPLETED(解除後)で、状態の移行と、
 * ロック中の記録の取り込み、送信漏れの補完を行う。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {}
            else -> return
        }
        val unlocked = Storage.unlocked(context)
        if (unlocked) Storage.onUnlocked(context)
        SummaryScheduler.scheduleNext(context)   // 再起動でアラームは消えるので登録し直す
        resumeVpn(context)
        if (unlocked) {
            // ロック中は送れなかった分や、止まっていた間の送信漏れを補う
            val app = context.applicationContext
            val pending = goAsync()
            thread { try { SummaryReceiver.run(app, false) } finally { pending.finish() } }
        }
    }

    private fun resumeVpn(context: Context) {
        if (!DnsVpnService.isEnabled(context)) return
        // VPN の許可が取り消されていたら(prepare が Intent を返す)、画面なしでは再開できない
        if (VpnService.prepare(context) != null) return
        context.startForegroundService(Intent(context, DnsVpnService::class.java))
    }
}
