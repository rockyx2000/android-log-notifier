package com.github.rockyx2000.dnslogger

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as Settings2
import android.widget.Toast
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var toggle: Button
    private lateinit var tail: TextView
    private lateinit var log: AccessLog
    private val handler = Handler(Looper.getMainLooper())
    private var askedBattery = false
    private var awaitingNotif = false     // 通知の許可ダイアログが出ている間
    private var askingVpn = false         // VPN の許可ダイアログが出ている間

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // targetSdk 35+ はエッジ・トゥ・エッジが強制されるので、システムバー分の余白を付ける
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        log = AccessLog(this)
        toggle = findViewById(R.id.toggle)
        tail = findViewById(R.id.tail)
        findViewById<TextView>(R.id.path).text = log.file.absolutePath

        Storage.onUnlocked(this)   // 更新前の状態を DE へ移す(アプリはロック解除後にしか開けない)
        if (!notificationsGranted()) {
            awaitingNotif = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        val domains = findViewById<EditText>(R.id.domains).apply { setText(Settings.domains(this@MainActivity)) }
        val webhook = findViewById<EditText>(R.id.webhook).apply { setText(Settings.webhook(this@MainActivity)) }
        findViewById<Button>(R.id.save).setOnClickListener {
            val url = webhook.text.toString().trim()
            if (url.isNotEmpty() && !url.startsWith("https://") && !BuildConfig.DEBUG) {
                Toast.makeText(this, "Webhook URL は https:// で始めてください", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Settings.save(this, domains.text.toString(), url)
            SummaryScheduler.scheduleNext(this)
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
            if (!SummaryScheduler.canScheduleExact(this)) {
                // 未許可だと 23:00 ちょうどに送れない場合があるので、設定画面へ誘導する
                startActivity(Intent(Settings2.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        findViewById<Button>(R.id.test).setOnClickListener {
            Settings.save(this, domains.text.toString(), webhook.text.toString())
            sendBroadcast(Intent(this, SummaryReceiver::class.java).putExtra(SummaryReceiver.EXTRA_TEST, true))
            Toast.makeText(this, "テスト送信しました(結果は下のステータス欄)", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.battery).setOnClickListener {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "既に除外されています", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(
                    Intent(Settings2.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        }

        findViewById<View>(R.id.alarm).setOnClickListener {
            startActivity(Intent(Settings2.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        findViewById<View>(R.id.appBattery).setOnClickListener {
            startActivity(Intent(Settings2.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        toggle.setOnClickListener {
            if (DnsVpnService.running) {
                startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
            } else {
                // 初回は OS の VPN 接続確認ダイアログが出る
                val intent = VpnService.prepare(this)
                if (intent != null) startActivityForResult(intent, REQ_VPN) else startVpn()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_VPN) return
        askingVpn = false
        if (resultCode == RESULT_OK) startVpn() else DnsVpnService.setAutoStart(this, false)   // 断ったら、次からは自動で聞かない
    }

    private fun startVpn() {
        startForegroundService(Intent(this, DnsVpnService::class.java))
    }

    private fun render() {
        val on = DnsVpnService.running
        val ink = getColor(R.color.ink); val bg = getColor(R.color.bg)
        toggle.text = if (on) "停止" else "開始"
        toggle.setBackgroundResource(if (on) R.drawable.btn_outline else R.drawable.btn_solid)
        toggle.setTextColor(if (on) ink else bg)
        findViewById<TextView>(R.id.statusTitle).apply {
            text = if (on) "● 記録中" else "○ 停止中"
            setTextColor(getColor(if (on) R.color.ok else R.color.sub))
        }
        tail.text = formatLog(log.tail(200)).ifEmpty { "(まだ記録がありません)" }
        val pm = getSystemService(PowerManager::class.java)
        state(R.id.batteryState, pm.isIgnoringBatteryOptimizations(packageName))
        state(R.id.alarmState, SummaryScheduler.canScheduleExact(this))
        findViewById<TextView>(R.id.status).text = "サマリ: " + Settings.status(this).ifEmpty { "未送信" }
    }

    /** 通知の許可は Android 13 以降の概念。12 では常に許可済みとして扱う。 */
    private fun notificationsGranted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun state(id: Int, ok: Boolean) = findViewById<TextView>(id).apply {
        text = if (ok) "OK" else "未設定 →"
        setTextColor(getColor(if (ok) R.color.ok else R.color.ng))
    }

    /** 「2026-10-07T14:01:58.48+09:00⇥example.com⇥A」→「10-07 14:01:58  A  example.com」 */
    private fun formatLog(raw: String): String = raw.lineSequence().mapNotNull { line ->
        val c = line.split('\t')
        if (c.size < 3 || c[0].length < 19) null else "${c[0].substring(5, 10)} ${c[0].substring(11, 19)}  ${c[2].padEnd(5)}${c[1]}"
    }.joinToString("\n")

    override fun onResume() {
        super.onResume()
        startNext()
        handler.post(refresh)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        awaitingNotif = false      // 許可でも拒否でも先へ進む(通知が無くても、記録は動く)
        startNext()
    }

    /**
     * 開いたときの自動開始。ダイアログが重ならないよう、1 つずつ順に進める:
     * 通知の許可 → バッテリー最適化の除外 → VPN の許可と開始。ダイアログを閉じるたびに、ここへ戻る。
     */
    private fun startNext() {
        if (awaitingNotif) return
        val pm = getSystemService(PowerManager::class.java)
        if (!askedBattery && !pm.isIgnoringBatteryOptimizations(packageName)) {
            askedBattery = true
            startActivity(Intent(Settings2.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            return
        }
        if (DnsVpnService.running || askingVpn || !DnsVpnService.autoStart(this)) return
        val consent = VpnService.prepare(this)
        if (consent == null) startVpn() else { askingVpn = true; startActivityForResult(consent, REQ_VPN) }
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    companion object {
        private const val REQ_VPN = 1
    }
}
