package app.deckmate.shot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager

/** Невидимая активность: запускается плиткой, при необходимости просит разрешение на захват экрана и даёт команду службе. */
class TriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SecureStore(this)
        if (store.key.isNullOrBlank()) {
            store.status = "no_key"
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else if (CaptureService.active) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.A_SHOT))
            finish()
        } else {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) {
            if (resultCode == RESULT_OK && data != null) {
                startForegroundService(
                    Intent(this, CaptureService::class.java).setAction(CaptureService.A_START)
                        .putExtra(CaptureService.EXTRA_CODE, resultCode)
                        .putExtra(CaptureService.EXTRA_DATA, data)
                )
            } else {
                SecureStore(this).status = "denied"
            }
            finish()
        }
    }

    companion object {
        private const val REQ = 1
    }
}
