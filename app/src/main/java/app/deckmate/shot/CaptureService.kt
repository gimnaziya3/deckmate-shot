package app.deckmate.shot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import android.view.WindowManager
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Держит сеанс захвата экрана (MediaProjection, разрешение выдаёт сам игрок системным окном) и по касанию плитки
 * отправляет кадр на сервер. Кадр хранится только в памяти и на диск не пишется.
 */
class CaptureService : Service() {
    private val thread = HandlerThread("deckmate-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val lock = Any()

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null
    private var width = 0
    private var height = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_START -> {
                startForeground(NOTIF_ID, notification(getString(R.string.n_ready)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (data == null || !setup(code, data)) {
                    stopSelfSafe()
                } else {
                    handler.postDelayed({ shoot() }, SHOT_DELAY_MS + 400)
                }
            }
            A_SHOT -> {
                if (projection == null) {
                    setStatus("need_start")
                    stopSelfSafe()
                } else {
                    handler.postDelayed({ shoot() }, SHOT_DELAY_MS)
                }
            }
            A_STOP -> stopSelfSafe()
        }
        return START_NOT_STICKY
    }

    private fun setup(code: Int, data: Intent): Boolean {
        return try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data) ?: return false
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    handler.post { stopSelfSafe() }
                }
            }, handler)
            val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            width = b.width()
            height = b.height()
            val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            r.setOnImageAvailableListener({ rd ->
                val img = try { rd.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
                synchronized(lock) {
                    latest?.close()
                    latest = img
                }
            }, handler)
            display = p.createVirtualDisplay(
                "deckmate", width, height, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
            )
            projection = p
            reader = r
            active = true
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun shoot() {
        if (!busy.compareAndSet(false, true)) return
        io.execute {
            try {
                val key = SecureStore(this).key
                if (key.isNullOrBlank()) {
                    report("no_key")
                    return@execute
                }
                report("sending")
                val jpeg = frameJpeg()
                if (jpeg == null) {
                    report("capture")
                    return@execute
                }
                val res = Uploader(Uploader.SHOT_URL).send(key, jpeg)
                report(
                    when (res) {
                        is SendResult.Ok -> "ok"
                        is SendResult.BadKey -> "bad_key"
                        is SendResult.TooFast -> "too_fast"
                        is SendResult.Network -> "net"
                        else -> "error"
                    }
                )
            } finally {
                busy.set(false)
            }
        }
    }

    /** Свежий кадр экрана в JPEG; если кадра ещё нет, ждём до 2 секунд. */
    private fun frameJpeg(): ByteArray? {
        var tries = 0
        var img: Image? = null
        while (tries < 20 && img == null) {
            synchronized(lock) { img = latest }
            if (img == null) Thread.sleep(100)
            tries++
        }
        val image = img ?: return null
        synchronized(lock) {
            return try {
                val plane = image.planes[0]
                val rowPad = plane.rowStride - plane.pixelStride * image.width
                val full = Bitmap.createBitmap(image.width + rowPad / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                plane.buffer.rewind()
                full.copyPixelsFromBuffer(plane.buffer)
                val crop = Bitmap.createBitmap(full, 0, 0, image.width, image.height)
                val out = ByteArrayOutputStream()
                crop.compress(Bitmap.CompressFormat.JPEG, 90, out)
                full.recycle()
                crop.recycle()
                out.toByteArray()
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun report(code: String) {
        setStatus(code)
        val text = statusText(this, code)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
        ui.post { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.n_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(A_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.n_stop), stop).build())
            .build()
    }

    private fun setStatus(code: String) {
        SecureStore(this).status = code
        TileService.requestListeningState(this, ComponentName(this, ShotTile::class.java))
    }

    private fun stopSelfSafe() {
        active = false
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        try { display?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        display = null
        reader = null
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        TileService.requestListeningState(this, ComponentName(this, ShotTile::class.java))
    }

    override fun onDestroy() {
        active = false
        thread.quitSafely()
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        const val A_START = "app.deckmate.shot.START"
        const val A_SHOT = "app.deckmate.shot.SHOT"
        const val A_STOP = "app.deckmate.shot.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1
        private const val SHOT_DELAY_MS = 500L

        @Volatile
        var active = false

        fun statusText(ctx: Context, code: String): String = ctx.getString(
            when (code) {
                "sending" -> R.string.s_sending
                "ok" -> R.string.s_ok
                "bad_key" -> R.string.s_bad_key
                "no_key" -> R.string.s_no_key
                "net" -> R.string.s_net
                "too_fast" -> R.string.s_too_fast
                "denied" -> R.string.s_denied
                "capture" -> R.string.s_capture
                "need_start" -> R.string.s_need_start
                "error" -> R.string.s_error
                else -> R.string.s_idle
            }
        )
    }
}
