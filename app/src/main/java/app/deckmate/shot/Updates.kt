package app.deckmate.shot

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Проверка новой версии в релизах GitHub: приложение само подсказывает, что есть обновление, и даёт ссылку на файл. */
object Updates {
    private const val API = "https://api.github.com/repos/gimnaziya3/deckmate-shot/releases/latest"
    const val DOWNLOAD = "https://github.com/gimnaziya3/deckmate-shot/releases/latest/download/deckmate-shot.apk"

    fun newer(latest: String, current: String): Boolean {
        val a = latest.trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun current(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** Номер новой версии или null, если обновления нет или нет сети. */
    fun check(ctx: Context): String? = try {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) null
            else JSONObject(c.inputStream.readBytes().toString(Charsets.UTF_8)).optString("tag_name").trimStart('v')
                .takeIf { it.isNotBlank() && newer(it, current(ctx)) }
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}
