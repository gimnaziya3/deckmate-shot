package app.deckmate.shot

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed class SendResult {
    object Ok : SendResult()
    object BadKey : SendResult()
    object TooFast : SendResult()
    object TooLarge : SendResult()
    object Empty : SendResult()
    object Insecure : SendResult()
    data class Server(val code: Int) : SendResult()
    data class Network(val reason: String) : SendResult()
}

/** Отправка скриншота на /scout/shot. Только HTTPS: на адрес без https ключ не отправляется. Повторы только при сбое сети и ответах 5xx; ключ в логи и сообщения не попадает. */
class Uploader(
    private val url: String,
    private val attempts: Int = 3,
    private val timeoutMs: Int = 15000,
    private val pause: (Int) -> Unit = { Thread.sleep(1000L * it) },
    private val requireHttps: Boolean = true,
) {
    fun send(key: String, body: ByteArray, mime: String = "image/jpeg", overlay: Boolean = false): SendResult {
        if (requireHttps && !url.startsWith("https://")) return SendResult.Insecure
        var last: SendResult = SendResult.Network("none")
        for (i in 1..attempts) {
            last = once(key, body, mime, overlay)
            val retry = last is SendResult.Network || (last is SendResult.Server && last.code >= 500)
            if (!retry) return last
            if (i < attempts) pause(i)
        }
        return last
    }

    private fun once(key: String, body: ByteArray, mime: String, overlay: Boolean): SendResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("X-Key", key)
            conn.setRequestProperty("Content-Type", mime)
            if (overlay) conn.setRequestProperty("X-Overlay", "1")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            when (val code = conn.responseCode) {
                200 -> SendResult.Ok
                401 -> SendResult.BadKey
                429 -> SendResult.TooFast
                413 -> SendResult.TooLarge
                400 -> SendResult.Empty
                else -> SendResult.Server(code)
            }
        } catch (e: IOException) {
            SendResult.Network(e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        const val BASE = "https://46-8-228-165.sslip.io"
        const val SHOT_URL = "$BASE/scout/shot"
    }
}
