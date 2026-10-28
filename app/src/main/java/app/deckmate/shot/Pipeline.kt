package app.deckmate.shot

import android.content.Context

/**
 * Отправка скриншота и (если включён оверлей) ожидание колоды с сервера и показ её поверх экрана.
 * Если колода не найдена, снимок отправляется ещё раз (ИИ может прочитать иначе), затем на экране появляется причина.
 */
object Pipeline {
    private const val WAIT_MS = 70_000L
    private const val RETRY_PAUSE_MS = 9_000L  // сервер принимает снимки не чаще раза в 8 секунд

    fun deliver(ctx: Context, key: String, jpeg: ByteArray): String {
        val app = ctx.applicationContext
        val overlay = SecureStore(app).overlayOn && OverlayController.canDraw(app)
        val base = if (overlay) (DeckApi.last(key)?.seq ?: 0) else 0
        return when (Uploader(Uploader.SHOT_URL).send(key, jpeg, overlay = overlay)) {
            is SendResult.Ok -> {
                if (overlay) Thread { flow(app, key, jpeg, base) }.start()
                "ok"
            }
            is SendResult.BadKey -> "bad_key"
            is SendResult.TooFast -> "too_fast"
            is SendResult.Network -> "net"
            else -> "error"
        }
    }

    private fun flow(app: Context, key: String, jpeg: ByteArray, base: Int) {
        val first = waitResult(key, base)
        if (first == null) {
            OverlayController.message(app, app.getString(R.string.ov_noresp))
            return
        }
        if (first.ok && first.cards.isNotEmpty()) {
            OverlayController.show(app, first)
            return
        }
        OverlayController.message(app, app.getString(R.string.ov_retry), 10_000)
        Thread.sleep(RETRY_PAUSE_MS)
        if (Uploader(Uploader.SHOT_URL).send(key, jpeg, overlay = true) !is SendResult.Ok) {
            OverlayController.message(app, app.getString(R.string.ov_fail))
            return
        }
        val second = waitResult(key, first.seq)
        when {
            second == null -> OverlayController.message(app, app.getString(R.string.ov_noresp))
            second.ok && second.cards.isNotEmpty() -> OverlayController.show(app, second)
            else -> OverlayController.message(app, app.getString(R.string.ov_fail) + "\n" + second.msg.take(100))
        }
    }

    private fun waitResult(key: String, base: Int): Last? {
        val end = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < end) {
            Thread.sleep(1200)
            val r = DeckApi.last(key) ?: continue
            if (r.seq > base) return r
        }
        return null
    }
}
