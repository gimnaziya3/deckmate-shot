package app.deckmate.shot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** «Проверить настройку»: ключ, связь с сервером, разрешения, фоновая служба. Возвращает строки для показа простым языком. */
object SetupCheck {
    fun photosGranted(ctx: Context): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    fun notificationsGranted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Блокирующий вызов (сеть): запускать не в основном потоке. */
    fun run(ctx: Context): List<String> {
        val store = SecureStore(ctx)
        val out = ArrayList<String>()
        fun line(ok: Boolean, res: Int) = out.add((if (ok) "✓ " else "✗ ") + ctx.getString(res))
        val key = store.key
        if (key.isNullOrBlank()) {
            line(false, R.string.chk_key_no)
        } else {
            when (Uploader(Uploader.SHOT_URL, attempts = 1).send(key, ByteArray(10))) {
                is SendResult.Empty, is SendResult.Ok, is SendResult.TooFast -> line(true, R.string.chk_key_ok)
                is SendResult.BadKey -> line(false, R.string.chk_key_bad)
                is SendResult.NotYet -> line(false, R.string.chk_soon)
                is SendResult.Network -> line(false, R.string.chk_net)
                else -> line(false, R.string.chk_server)
            }
        }
        line(notificationsGranted(ctx), if (notificationsGranted(ctx)) R.string.chk_notif_ok else R.string.chk_notif_no)
        line(photosGranted(ctx), if (photosGranted(ctx)) R.string.chk_photos_ok else R.string.chk_photos_no)
        line(WatchService.running, if (WatchService.running) R.string.chk_watch_ok else R.string.chk_watch_no)
        if (store.overlayOn) {
            val ok = OverlayController.canDraw(ctx)
            line(ok, if (ok) R.string.chk_overlay_ok else R.string.chk_overlay_no)
        }
        return out
    }
}
