package app.deckmate.shot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Экран настройки: ключ из /scoutkey, проверка, короткая инструкция. Интерфейс собран кодом, без лишних библиотек. */
class MainActivity : Activity() {
    private lateinit var keyField: EditText
    private lateinit var info: TextView
    private lateinit var store: SecureStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(res: Int, size: Float = 15f) = TextView(this).apply {
            setText(res)
            textSize = size
            setPadding(0, pad / 2, 0, pad / 2)
            col.addView(this)
        }
        fun button(res: Int, action: () -> Unit) = Button(this).apply {
            setText(res)
            setOnClickListener { action() }
            col.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        text(R.string.app_name, 22f)
        text(R.string.unofficial, 13f)
        text(R.string.key_label)
        keyField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setHint(R.string.key_hint)
            setSingleLine()
            if (!store.key.isNullOrBlank()) setText(MASK)
        }
        col.addView(keyField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        button(R.string.save) { save() }
        button(R.string.check) { check() }
        info = text(R.string.s_idle)
        text(R.string.how_to)
<<<<<<< HEAD
        if (resources.getBoolean(R.bool.fast)) {
            text(R.string.a11y_info)
            button(R.string.a11y_open) { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
=======
        OverlaySettings.addTo(this, col, store, pad)
        text(R.string.a11y_info)
        button(R.string.a11y_open) { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
>>>>>>> overlay
        button(R.string.allow_notifications) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        button(R.string.stop_capture) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.A_STOP))
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    /** Возвращает введённый ключ, либо уже сохранённый, если поле не меняли. */
    private fun currentKey(): String? {
        val typed = keyField.text.toString().trim()
        return if (typed == MASK) store.key else typed.ifBlank { null }
    }

    private fun save() {
        val k = currentKey()
        if (k == null) {
            info.setText(R.string.s_no_key)
            return
        }
        store.key = k
        keyField.setText(MASK)
        info.setText(R.string.saved)
    }

    private fun check() {
        val k = currentKey()
        if (k == null) {
            info.setText(R.string.s_no_key)
            return
        }
        info.setText(R.string.s_sending)
        Thread {
            // Тестовый запрос: слишком короткое тело. Сервер отвечает 400, если ключ верный, и 401, если нет. Колода не запускается.
            val res = Uploader(Uploader.SHOT_URL, attempts = 1).send(k, ByteArray(10))
            val msg = when (res) {
                is SendResult.Empty, is SendResult.Ok, is SendResult.TooFast -> R.string.check_ok
                is SendResult.BadKey -> R.string.s_bad_key
                is SendResult.Network -> R.string.s_net
                else -> R.string.s_error
            }
            runOnUiThread { info.setText(msg) }
        }.start()
    }

    companion object {
        private const val MASK = "••••••••••••"
    }
}
