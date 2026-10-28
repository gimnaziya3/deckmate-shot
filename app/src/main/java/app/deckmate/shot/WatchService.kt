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
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.service.quicksettings.TileService
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Следит за новыми скриншотами в галерее (их делает сам игрок обычным способом) и сразу отправляет на сервер.
 * Захват экрана не используется: нет окна разрешения и нет системного скрытия содержимого игры.
 */
class WatchService : Service() {
    private val thread = HandlerThread("deckmate-watch").apply { start() }
    private val handler = Handler(thread.looper)
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var lastId = -1L
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(check)
            handler.postDelayed(check, 800)
        }
    }
    private val check = Runnable { io.execute { newest()?.let { send(it) } } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == A_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification(getString(R.string.w_ready))
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
        if (!running) {
            running = true
            lastId = newestId()
            contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        }
        return START_STICKY
    }

    private fun recent(): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        try {
            val since = (System.currentTimeMillis() / 1000 - 25).toString()
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
                "(${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?) AND " +
                    "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.IS_PENDING} = 0",
                arrayOf("%screenshot%", "%screenshots%", since),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 3) out.add(c.getLong(0) to c.getString(1))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun newestId(): Long = try {
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null,
            "${MediaStore.Images.Media._ID} DESC"
        )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun newest(): Long? {
        val id = recent().maxOfOrNull { it.first } ?: return null
        if (id <= lastId) return null
        lastId = id
        return id
    }

    private fun send(id: Long) {
        val key = SecureStore(this).key
        if (key.isNullOrBlank()) {
            report("no_key")
            return
        }
        val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
        val jpeg = try {
            contentResolver.openInputStream(uri)?.use { s ->
                val bmp = BitmapFactory.decodeStream(s) ?: return@use null
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                bmp.recycle()
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
        if (jpeg == null || jpeg.isEmpty()) {
            report("capture")
            return
        }
        report("sending")
        report(Pipeline.deliver(this, key, jpeg))
    }

    private fun report(code: String) {
        SecureStore(this).status = code
        val text = CaptureService.statusText(this, code)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
        ui.post { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() }
        TileService.requestListeningState(this, ComponentName(this, ShotTile::class.java))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.w_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 2, Intent(this, WatchService::class.java).setAction(A_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.n_settings), open).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.n_stop), stop).build())
            .build()
    }

    override fun onDestroy() {
        running = false
        try { contentResolver.unregisterContentObserver(observer) } catch (e: Exception) { }
        thread.quitSafely()
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        const val A_STOP = "app.deckmate.shot.WATCH_STOP"
        private const val CHANNEL = "watch"
        private const val NOTIF_ID = 2

        @Volatile
        var running = false

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, WatchService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, WatchService::class.java).setAction(A_STOP))
        }
    }
}
