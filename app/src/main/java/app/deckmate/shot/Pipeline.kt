package app.deckmate.shot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Отправка скриншота и (если включён оверлей) ожидание колоды с сервера и показ её поверх экрана. Возвращает код статуса. */
object Pipeline {
    private const val WAIT_MS = 70_000L

    fun deliver(ctx: Context, key: String, jpeg: ByteArray): String {
        val app = ctx.applicationContext
        val store = SecureStore(app)
        val overlay = store.overlayOn && OverlayController.canDraw(app)
        val base = if (overlay) (DeckApi.last(key)?.seq ?: 0) else 0
        return when (Uploader(Uploader.SHOT_URL).send(key, jpeg, overlay = overlay)) {
            is SendResult.Ok -> {
                if (overlay) Thread { waitDeck(app, key, base) }.start()
                "ok"
            }
            is SendResult.BadKey -> "bad_key"
            is SendResult.TooFast -> "too_fast"
            is SendResult.Network -> "net"
            else -> "error"
        }
    }

    private fun waitDeck(app: Context, key: String, base: Int) {
        val end = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < end) {
            Thread.sleep(1200)
            val r = DeckApi.last(key) ?: continue
            if (r.seq <= base) continue
            if (r.ok && r.cards.isNotEmpty()) {
                OverlayController.show(app, r)
            } else {
                Handler(Looper.getMainLooper()).post { Toast.makeText(app, r.msg.take(120), Toast.LENGTH_LONG).show() }
            }
            return
        }
    }
}
