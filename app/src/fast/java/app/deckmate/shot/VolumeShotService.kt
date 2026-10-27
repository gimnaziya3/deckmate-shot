package app.deckmate.shot

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Быстрый режим: обе кнопки громкости вместе = скриншот (системный takeScreenshot, без окон и шторки) и отправка.
 * Служба не читает содержимое экрана: события и окна не запрашиваются, используется только перехват двух клавиш громкости.
 */
class VolumeShotService : AccessibilityService() {
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var upDown = false
    private var downDown = false
    private var fired = false
    private val consumed = mutableSetOf<Int>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val isUp = code == KeyEvent.KEYCODE_VOLUME_UP
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (isUp) upDown = true else downDown = true
            if (upDown && downDown) {
                consumed.add(KeyEvent.KEYCODE_VOLUME_UP)
                consumed.add(KeyEvent.KEYCODE_VOLUME_DOWN)
                if (!fired) {
                    fired = true
                    shoot()
                }
                return true
            }
            return false
        }
        if (isUp) upDown = false else downDown = false
        if (!upDown && !downDown) fired = false
        return consumed.remove(code)
    }

    private fun shoot() {
        if (!busy.compareAndSet(false, true)) return
        val key = SecureStore(this).key
        if (key.isNullOrBlank()) {
            finish("no_key")
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, io, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val jpeg = try {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    val out = ByteArrayOutputStream()
                    bmp?.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    bmp?.recycle()
                    out.toByteArray()
                } catch (e: Exception) {
                    null
                }
                if (jpeg == null || jpeg.isEmpty()) {
                    finish("capture")
                    return
                }
                finish("sending", false)
                val res = Uploader(Uploader.SHOT_URL).send(key, jpeg)
                finish(
                    when (res) {
                        is SendResult.Ok -> "ok"
                        is SendResult.BadKey -> "bad_key"
                        is SendResult.TooFast -> "too_fast"
                        is SendResult.Network -> "net"
                        else -> "error"
                    }
                )
            }

            override fun onFailure(errorCode: Int) {
                finish(if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) "too_fast" else "capture")
            }
        })
    }

    private fun finish(code: String, done: Boolean = true) {
        SecureStore(this).status = code
        val text = CaptureService.statusText(this, code)
        ui.post { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() }
        if (done) busy.set(false)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
