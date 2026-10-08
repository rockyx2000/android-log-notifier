package com.github.rockyx2000.dnslogger

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

/** テスト前に、アプリの保存状態(設定・状態・生存確認・ログ)をまっさらにする。 */
fun resetAppState() {
    ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    for (name in listOf("state", "health", "filter")) Storage.prefs(ctx, name).edit().clear().commit()   // DE
    Storage.pendingFile(ctx).delete()
    ctx.filesDir.listFiles()?.filter { it.name.startsWith(AccessLog.FILE_NAME) }?.forEach { it.delete() }
}

/**
 * 端末内で動く偽の Discord Webhook。受けた JSON の `content` を [bodies] に積み、[status] を返す。
 * 本物の Discord には送らずに、SummaryReceiver から DiscordNotifier までの経路を確かめるために使う。
 */
class FakeWebhook(var status: Int = 204) : Closeable {
    private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
    val bodies = LinkedBlockingQueue<String>()
    val url get() = "http://127.0.0.1:${server.localPort}/"

    init {
        thread(isDaemon = true, name = "fake-webhook") {
            try {
                while (true) server.accept().use { handle(it) }
            } catch (_: Exception) {
                // close() で accept が中断される
            }
        }
    }

    private fun handle(s: java.net.Socket) {
        val input = s.getInputStream()
        val head = ByteArrayOutputStream()
        // ヘッダ(空行まで)を読む
        while (!head.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return
            head.write(b)
        }
        val len = Regex("(?i)content-length:\\s*(\\d+)").find(head.toString(Charsets.ISO_8859_1))?.groupValues?.get(1)?.toInt() ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
        bodies.add(org.json.JSONObject(String(body, Charsets.UTF_8)).getString("content"))
        s.getOutputStream().write("HTTP/1.1 $status X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    /** 送られてきた本文を最大 [ms] ミリ秒待って取り出す。来なければ null。 */
    fun next(ms: Long = 3000): String? = bodies.poll(ms, TimeUnit.MILLISECONDS)

    override fun close() = server.close()
}
