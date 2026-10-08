package com.github.rockyx2000.dnslogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * DNS だけを横取りするローカル VPN。
 *
 * 仮想 DNS サーバ(10.0.0.1)だけを TUN にルーティングするので、DNS 以外の通信は
 * VPN を経由せず通常どおり流れる。受けた DNS クエリの FQDN をログに残し、
 * 実際の上流 DNS に転送して応答を TUN へ書き戻す。
 */
class DnsVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var beater: Thread? = null
    private var pool: ExecutorService? = null
    private lateinit var log: AccessLog
    @Volatile private var upstream: InetAddress = InetAddress.getByName(FALLBACK_DNS)
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        log = AccessLog(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        if (tun == null) start()
        return START_STICKY
    }

    private fun start() {
        startInForeground()
        Health.onStart(this)   // 前回から空いていれば途切れとして記録
        setEnabled(this, true)
        setAutoStart(this, true)
        // 23:00 のアラームを登録し、送信漏れがあれば補完する
        SummaryScheduler.scheduleNext(this)
        Thread { SummaryReceiver.run(applicationContext, false) }.start()
        // VPN 確立前に、現在のネットワークの DNS サーバを控えておく
        upstream = findUpstreamDns()

        tun = Builder()
            .setSession("DNS Logger")
            .addAddress(CLIENT_ADDR, 32)
            .addDnsServer(VIRTUAL_DNS)
            .addRoute(VIRTUAL_DNS, 32)
            .setBlocking(true)
            .establish() ?: run { stopSelf(); return }

        running = true
        pool = Executors.newFixedThreadPool(8)
        val fd = tun!!
        reader = Thread({ readLoop(fd) }, "tun-reader").also { it.start() }
        watchNetworks()
        beater = Thread({
            try {
                while (running) { Health.beat(applicationContext); Thread.sleep(Health.BEAT_INTERVAL_MS) }
            } catch (_: InterruptedException) {
            }
        }, "health-beat").also { it.start() }
        Log.i(TAG, "started, upstream=${upstream.hostAddress}")
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "DNS ログ記録", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("DNS ログを記録中")
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
    }

    private fun stop(revoked: Boolean = false) {
        unwatchNetworks()
        beater?.interrupt()
        if (revoked) Health.onRevoked(this) else Health.onUserStop(this)
        setEnabled(this, false)   // ユーザー操作による停止なので、再起動後は自動再開しない
        setAutoStart(this, false) // アプリを開いても、自動では始めない(「開始」で戻る)
        running = false
        reader?.interrupt()
        pool?.shutdownNow()
        tun?.close()
        tun = null
        stopSelf()
    }

    override fun onDestroy() {
        unwatchNetworks()
        beater?.interrupt()
        running = false
        pool?.shutdownNow()
        tun?.close()
        super.onDestroy()
    }

    override fun onRevoke() {
        stop(revoked = true)
    }

    @Volatile private var filterRaw: String? = null
    @Volatile private var filter = DomainFilter("")

    /** 設定画面で編集されたら次のクエリから反映する。 */
    private fun currentFilter(): DomainFilter {
        val raw = Settings.domains(this)
        if (raw != filterRaw) { filter = DomainFilter(raw); filterRaw = raw }
        return filter
    }

    /** VPN 以外の(=実際の)ネットワークの IPv4 DNS。VPN 確立後は activeNetwork が VPN になりうるので除外する。 */
    private fun findUpstreamDns(): InetAddress {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = listOfNotNull(cm.activeNetwork) + cm.allNetworks
        for (net in candidates) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val dns = cm.getLinkProperties(net)?.dnsServers?.firstOrNull { it.address.size == 4 }
            if (dns != null) return dns
        }
        return InetAddress.getByName(FALLBACK_DNS)
    }

    /** Wi-Fi ↔ モバイル切替などで DNS サーバが変わったら上流を更新する。 */
    private fun watchNetworks() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            private fun refresh() {
                val next = findUpstreamDns()
                if (next != upstream) {
                    upstream = next
                    Log.i(TAG, "upstream changed: ${next.hostAddress}")
                }
            }
            override fun onAvailable(network: Network) = refresh()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = refresh()
            override fun onLost(network: Network) = refresh()
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(req, cb)
        netCallback = cb
    }

    private fun unwatchNetworks() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n <= 0) continue
                val q = UdpDns.parse(buf, n) ?: continue
                val up = upstream
                pool?.execute { handleQuery(q, up, output) }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "read loop ended: $e")
        }
    }

    private fun handleQuery(q: UdpDns.Query, upstream: InetAddress, tunOut: FileOutputStream) {
        DnsPacket.parseQuestion(q.payload, 0, q.payload.size)?.let {
            // 指定ドメイン以外は転送だけして記録しない
            if (currentFilter().matches(it.name)) log.append(it.name, DnsPacket.typeName(it.type))
        }
        try {
            DatagramSocket().use { sock ->
                protect(sock)                       // VPN を迂回して実ネットワークへ出す
                sock.soTimeout = 5000
                sock.send(DatagramPacket(q.payload, q.payload.size, upstream, 53))
                val resp = ByteArray(4096)
                val rp = DatagramPacket(resp, resp.size)
                sock.receive(rp)
                val packet = UdpDns.buildResponse(q, resp, rp.length)
                synchronized(tunOut) { tunOut.write(packet) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "forward failed: $e")
        }
    }

    companion object {
        const val ACTION_STOP = "com.github.rockyx2000.dnslogger.STOP"
        private const val TAG = "DnsVpnService"
        private const val CLIENT_ADDR = "10.0.0.2"
        private const val VIRTUAL_DNS = "10.0.0.1"
        private const val FALLBACK_DNS = "8.8.8.8"
        private const val CHANNEL_ID = "dns_logger"
        private const val NOTIF_ID = 1
        private const val PREFS = "state"

        // 状態はロック解除前の再開にも使うので、DE に置く(→ Storage)
        private fun state(ctx: Context) = Storage.prefs(ctx, PREFS)

        fun isEnabled(ctx: Context) = state(ctx).getBoolean("enabled", false)

        fun setEnabled(ctx: Context, v: Boolean) = state(ctx).edit().putBoolean("enabled", v).apply()

        /**
         * アプリを開いたとき、自動で開始してよいか。初回は true。ユーザーが「停止」を押した、VPN の許可を断った、
         * 他の VPN に奪われた場合は false(他の VPN を黙って奪い返さない)。「開始」で true に戻る。
         */
        fun autoStart(ctx: Context) = state(ctx).getBoolean("auto_start", true)

        fun setAutoStart(ctx: Context, v: Boolean) = state(ctx).edit().putBoolean("auto_start", v).apply()

        @Volatile
        var running = false
            private set
    }
}
