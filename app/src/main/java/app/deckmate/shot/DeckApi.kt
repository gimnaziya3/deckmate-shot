package app.deckmate.shot

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class Card(val name: String, val elixir: Int?, val evo: Boolean, val hero: Boolean, val icon: String?)
data class Last(val seq: Int, val ok: Boolean, val msg: String, val cards: List<Card>, val avg: Double? = null)

/** Забирает с сервера результат распознавания (/scout/last) и иконки карт. Ключ только в заголовке. */
object DeckApi {
    fun parse(json: String): Last {
        val o = JSONObject(json)
        val arr = o.optJSONArray("cards")
        val cards = (0 until (arr?.length() ?: 0)).map {
            val c = arr!!.getJSONObject(it)
            Card(
                c.optString("name"), if (c.isNull("elixir")) null else c.optInt("elixir"),
                c.optBoolean("evo"), c.optBoolean("hero"), if (c.isNull("icon")) null else c.optString("icon")
            )
        }
        return Last(o.optInt("seq"), o.optBoolean("ok"), o.optString("msg"), cards, if (o.has("avg") && !o.isNull("avg")) o.optDouble("avg") else null)
    }

    fun last(key: String, base: String = Uploader.BASE): Last? = if (!base.startsWith("https://")) null else try {
        val c = URL("$base/scout/last").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("X-Key", key)
        try {
            if (c.responseCode == 200) parse(c.inputStream.readBytes().toString(Charsets.UTF_8)) else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    fun icon(path: String, base: String = Uploader.BASE): ByteArray? = if (!base.startsWith("https://")) null else try {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            if (c.responseCode == 200) c.inputStream.readBytes() else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}
