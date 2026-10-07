package com.example.dnslogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/** 再起動後・アプリ更新後、ユーザーが「開始」状態のまま終了していたら VPN を再開する。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 再起動後と、アプリ更新後(更新するとサービスもアラームも止まる)に再開する
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        SummaryScheduler.scheduleNext(context)   // 再起動でアラームは消えるので登録し直す
        if (!DnsVpnService.isEnabled(context)) return
        // VPN の許可が取り消されていたら(prepare が Intent を返す)、画面なしでは再開できない
        if (VpnService.prepare(context) != null) return
        context.startForegroundService(Intent(context, DnsVpnService::class.java))
    }
}
