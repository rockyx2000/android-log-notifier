package com.example.dnslogger

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as Settings2
import android.widget.Toast
import android.content.Intent
import android.net.VpnService
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

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) startVpn()
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
        // 除外されていなければ、起動のたびに 1 回だけ標準ダイアログで案内する(通知許可のダイアログとは重ねない)
        if (!askedBattery && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        ) {
            askedBattery = true
            startActivity(Intent(Settings2.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        // 「開始」のまま OS に落とされていたら、アプリを開いた時点で再開する(VPN の許可が生きている場合のみ)
        if (DnsVpnService.isEnabled(this) && !DnsVpnService.running && VpnService.prepare(this) == null) startVpn()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    companion object {
        private const val REQ_VPN = 1
    }
}
