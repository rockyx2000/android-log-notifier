package com.github.rockyx2000.dnslogger

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DiscordNotifier {
    /** Webhook に本文を POST する。成功(2xx)なら null、失敗なら理由を返す。 */
    fun post(webhookUrl: String, content: String): String? {
        return try {
            val conn = URL(webhookUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // 既定の Java の User-Agent は Discord 側(Cloudflare)に弾かれることがある
            conn.setRequestProperty("User-Agent", "DnsLogger/1.0")
            conn.outputStream.use { it.write(JSONObject().put("content", content).toString().toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) null else "HTTP $code"
        } catch (e: Exception) {
            e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }
}
