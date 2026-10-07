package com.example.dnslogger

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
                val q = parseUdpDns(buf, n) ?: continue
                val up = upstream
                pool?.execute { handleQuery(q, up, output) }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "read loop ended: $e")
        }
    }

    /** IPv4 + UDP + DNS(宛先 10.0.0.1:53)なら切り出して返す。それ以外は null(破棄)。 */
    private fun parseUdpDns(buf: ByteArray, n: Int): DnsQuery? {
        if (n < 28) return null
        if ((buf[0].toInt() shr 4) != 4) return null          // IPv4 のみ
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (buf[9].toInt() != 17) return null                 // UDP のみ
        if (n < ihl + 8) return null
        val dstPort = u16(buf, ihl + 2)
        if (dstPort != 53) return null
        val payloadOff = ihl + 8
        val payloadLen = n - payloadOff
        return DnsQuery(
            srcIp = buf.copyOfRange(12, 16),
            dstIp = buf.copyOfRange(16, 20),
            srcPort = u16(buf, ihl),
            payload = buf.copyOfRange(payloadOff, payloadOff + payloadLen),
        )
    }

    private fun handleQuery(q: DnsQuery, upstream: InetAddress, tunOut: FileOutputStream) {
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
                val packet = buildResponse(q, resp, rp.length)
                synchronized(tunOut) { tunOut.write(packet) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "forward failed: $e")
        }
    }

    /** 応答 DNS を IPv4/UDP ヘッダで包み直す(送信元と宛先は入れ替え)。 */
    private fun buildResponse(q: DnsQuery, dns: ByteArray, dnsLen: Int): ByteArray {
        val total = 20 + 8 + dnsLen
        val p = ByteArray(total)
        p[0] = 0x45.toByte()
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[8] = 64                                     // TTL
        p[9] = 17                                     // UDP
        q.dstIp.copyInto(p, 12)                       // src = 元の宛先(10.0.0.1)
        q.srcIp.copyInto(p, 16)                       // dst = 元の送信元
        val sum = checksum(p, 0, 20)
        p[10] = (sum shr 8).toByte(); p[11] = sum.toByte()
        p[20] = 0; p[21] = 53                         // src port
        p[22] = (q.srcPort shr 8).toByte(); p[23] = q.srcPort.toByte()
        val udpLen = 8 + dnsLen
        p[24] = (udpLen shr 8).toByte(); p[25] = udpLen.toByte()
        // UDP チェックサムは IPv4 では 0(未使用)でよい
        dns.copyInto(p, 28, 0, dnsLen)
        return p
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        while (i < off + len) {
            sum += u16(b, i)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private class DnsQuery(val srcIp: ByteArray, val dstIp: ByteArray, val srcPort: Int, val payload: ByteArray)

    companion object {
        const val ACTION_STOP = "com.example.dnslogger.STOP"
        private const val TAG = "DnsVpnService"
        private const val CLIENT_ADDR = "10.0.0.2"
        private const val VIRTUAL_DNS = "10.0.0.1"
        private const val FALLBACK_DNS = "8.8.8.8"
        private const val CHANNEL_ID = "dns_logger"
        private const val NOTIF_ID = 1
        private const val PREFS = "state"

        fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

        fun setEnabled(ctx: Context, v: Boolean) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", v).apply()

        @Volatile
        var running = false
            private set
    }
}
