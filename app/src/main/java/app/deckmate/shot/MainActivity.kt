package app.deckmate.shot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Экран настройки: ключ, быстрый снимок, оверлей с колодой. Интерфейс собран кодом, без лишних библиотек. */
class MainActivity : Activity() {
    private lateinit var keyField: EditText
    private lateinit var info: TextView
    private lateinit var store: SecureStore
    private lateinit var overlaySwitch: Switch
    private lateinit var permInfo: TextView
    private lateinit var watchSwitch: Switch
    private lateinit var watchInfo: TextView
    private var syncing = false

    private val blue = Color.rgb(47, 111, 235)
    private val dp by lazy { resources.displayMetrics.density }

    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(14), px(14), px(24))
            setBackgroundColor(Color.rgb(242, 244, 248))
        }
        page.addView(label(getString(R.string.app_name), 24f, bold = true))
        page.addView(label(getString(R.string.unofficial), 12f, Color.GRAY))

        val keyCard = card(page, R.string.h_key)
        keyCard.addView(label(getString(R.string.key_label), 14f))
        keyField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setHint(R.string.key_hint)
            setSingleLine()
            if (!store.key.isNullOrBlank()) setText(MASK)
        }
        keyCard.addView(keyField, full())
        val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keyRow.addView(button(R.string.save, true) { save() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        keyRow.addView(button(R.string.check, false) { check() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        keyCard.addView(keyRow, full())
        info = label(getString(R.string.s_idle), 14f, blue, bold = true)
        keyCard.addView(info)

        val watch = card(page, R.string.h_watch)
        watch.addView(label(getString(R.string.watch_info), 14f))
        watchSwitch = Switch(this).apply {
            setText(R.string.watch_enable)
            textSize = 16f
            setOnCheckedChangeListener { _, on ->
                if (syncing) return@setOnCheckedChangeListener
                store.watchOn = on
                if (on) {
                    if (!hasPhotos()) askPhotos()
                    WatchService.start(this@MainActivity)
                } else {
                    WatchService.stop(this@MainActivity)
                }
            }
        }
        watch.addView(watchSwitch, full())
        watchInfo = label("", 13f, Color.rgb(160, 50, 50))
        watch.addView(watchInfo)
        watch.addView(button(R.string.allow_notifications, false) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }, full())
        watch.addView(button(R.string.watch_perm, false) { askPhotos() }, full())

        val shot = card(page, R.string.h_shot)
        shot.addView(label(getString(R.string.shot_info), 14f))
        shot.addView(button(R.string.start_capture, true) {
            startActivity(Intent(this, TriggerActivity::class.java).putExtra(TriggerActivity.EXTRA_SILENT, true))
        }, full())
        shot.addView(button(R.string.stop_capture, false) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.A_STOP))
        }, full())

        val ov = card(page, R.string.h_overlay)
        overlaySwitch = Switch(this).apply {
            setText(R.string.ov_enable)
            textSize = 16f
            setOnCheckedChangeListener { _, on ->
                if (syncing) return@setOnCheckedChangeListener
                if (on && !OverlayController.canDraw(this@MainActivity)) {
                    grantOverlay()
                    syncOverlay()
                } else {
                    store.overlayOn = on
                }
            }
        }
        ov.addView(overlaySwitch, full())
        ov.addView(label(getString(R.string.ov_info), 14f))
        ov.addView(label(getString(R.string.ov_area), 13f, Color.rgb(160, 50, 50)))
        permInfo = label("", 13f, Color.GRAY)
        ov.addView(permInfo)
        ov.addView(button(R.string.ov_grant, false) { grantOverlay() }, full())
        ov.addView(button(R.string.ov_place, true) {
            if (OverlayController.canDraw(this)) OverlayController.edit(this) else grantOverlay()
        }, full())
        ov.addView(button(R.string.ov_preview, false) {
            if (OverlayController.canDraw(this)) OverlayController.preview(this) else grantOverlay()
        }, full())
        slider(ov, R.string.ov_alpha, 20, 100, { store.alpha }, { store.alpha = it })
        slider(ov, R.string.ov_size, 40, 130, { store.size }, { store.size = it })
        ov.addView(label(getString(R.string.ov_warn), 12f, Color.rgb(160, 50, 50)))

        if (resources.getBoolean(R.bool.fast)) {
            val fast = card(page, R.string.a11y_open)
            fast.addView(label(getString(R.string.a11y_info), 14f))
            fast.addView(button(R.string.a11y_open, false) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, full())
        }

        page.addView(label(getString(R.string.perm_info), 12f, Color.GRAY))
        setContentView(ScrollView(this).apply { addView(page) })
    }

    override fun onResume() {
        super.onResume()
        syncOverlay()
    }

    private fun hasPhotos(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun askPhotos() {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        requestPermissions(arrayOf(p), 2)
    }

    private fun syncOverlay() {
        syncing = true
        watchSwitch.isChecked = store.watchOn
        syncing = false
        watchInfo.text = if (hasPhotos()) "" else getString(R.string.watch_perm_no)
        if (store.watchOn && hasPhotos() && !WatchService.running) WatchService.start(this)
        val ok = OverlayController.canDraw(this)
        if (!ok && store.overlayOn) store.overlayOn = false
        syncing = true
        overlaySwitch.isChecked = store.overlayOn && ok
        syncing = false
        permInfo.setText(if (ok) R.string.ov_perm_ok else R.string.ov_perm_no)
    }

    private fun grantOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun full() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = px(6) }

    private fun label(text: String, size: Float, color: Int = Color.rgb(30, 34, 44), bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, px(4), 0, px(4))
    }

    private fun card(parent: LinearLayout, title: Int): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(14))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 16 * dp
            }
        }
        c.addView(label(getString(title), 17f, blue, bold = true))
        parent.addView(c, full().apply { topMargin = px(12) })
        return c
    }

    private fun button(res: Int, primary: Boolean, action: () -> Unit) = Button(this).apply {
        setText(res)
        isAllCaps = false
        setTextColor(if (primary) Color.WHITE else blue)
        background = GradientDrawable().apply {
            if (primary) setColor(blue) else {
                setColor(Color.WHITE)
                setStroke(px(1), blue)
            }
            cornerRadius = 12 * dp
        }
        setOnClickListener { action() }
    }

    private fun slider(parent: LinearLayout, res: Int, min: Int, max: Int, get: () -> Int, set: (Int) -> Unit) {
        parent.addView(label(getString(res), 14f))
        parent.addView(SeekBar(this).apply {
            this.max = max - min
            progress = get() - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) set(p + min)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    if (OverlayController.canDraw(this@MainActivity)) OverlayController.preview(this@MainActivity)
                }
            })
        }, full())
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
