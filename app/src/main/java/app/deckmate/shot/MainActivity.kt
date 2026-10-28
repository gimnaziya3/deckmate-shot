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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Первый запуск: 5 коротких шагов. Потом главный экран с настройками. Интерфейс собран кодом, без лишних библиотек. */
class MainActivity : Activity() {
    private lateinit var store: SecureStore
    private var keyField: EditText? = null
    private var info: TextView? = null
    private var overlaySwitch: Switch? = null
    private var permInfo: TextView? = null
    private var watchSwitch: Switch? = null
    private var watchInfo: TextView? = null
    private var syncing = false
    private var step = 0

    private val blue = Color.rgb(47, 111, 235)
    private val dp by lazy { resources.displayMetrics.density }

    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)
        if (store.onboarded) showHome() else showStep()
    }

    override fun onResume() {
        super.onResume()
        if (store.onboarded || step > 0) sync()
    }

    // ---------- первый запуск ----------

    private fun showStep() {
        val page = pageView()
        page.addView(label(getString(R.string.ob_progress, step + 1, STEPS), 12f, Color.GRAY))
        val c = card(page, intArrayOf(R.string.ob1_title, R.string.ob2_title, R.string.ob3_title, R.string.ob4_title, R.string.ob5_title)[step])
        c.addView(label(getString(intArrayOf(R.string.ob1_text, R.string.ob2_text, R.string.ob3_text, R.string.ob4_text, R.string.ob5_text)[step]), 15f))
        when (step) {
            1 -> keyBlock(c)
            2 -> permissionsBlock(c)
            3 -> overlayBlock(c, withSliders = false)
            4 -> {
                info = label("", 14f, blue, bold = true)
                c.addView(info)
                c.addView(button(R.string.chk_run, true) { runCheck() }, full())
            }
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (step > 0) nav.addView(button(R.string.ob_back, false) { step--; showStep() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(
            button(if (step == STEPS - 1) R.string.ob_done else R.string.ob_next, true) {
                if (step == STEPS - 1) {
                    store.onboarded = true
                    step = 0
                    showHome()
                } else {
                    step++
                    showStep()
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        page.addView(nav, full().apply { topMargin = px(14) })
        setContentView(ScrollView(this).apply { addView(page) })
        sync()
    }

    // ---------- главный экран ----------

    private fun showHome() {
        val page = pageView()
        page.addView(label(getString(R.string.app_name), 24f, bold = true))

        val upd = card(page, R.string.h_update)
        upd.visibility = View.GONE
        val updText = label("", 15f, blue, bold = true)
        upd.addView(updText)
        upd.addView(button(R.string.upd_button, true) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updates.DOWNLOAD)))
        }, full())
        Thread {
            val v = Updates.check(this)
            if (v != null) runOnUiThread {
                updText.text = getString(R.string.upd_text, v)
                upd.visibility = View.VISIBLE
            }
        }.start()

        val guide = card(page, R.string.h_guide)
        guide.addView(label(getString(R.string.guide_text), 14f))
        guide.addView(button(R.string.ob_again, false) { step = 0; store.onboarded = false; showStep() }, full())

        val keyCard = card(page, R.string.h_key)
        keyBlock(keyCard)
        keyCard.addView(button(R.string.chk_run, false) { runCheck() }, full())

        val watch = card(page, R.string.h_watch)
        permissionsBlock(watch)

        val shot = card(page, R.string.h_shot)
        shot.addView(label(getString(R.string.shot_info), 14f))
        shot.addView(button(R.string.start_capture, true) {
            startActivity(Intent(this, TriggerActivity::class.java).putExtra(TriggerActivity.EXTRA_SILENT, true))
        }, full())
        shot.addView(button(R.string.stop_capture, false) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.A_STOP))
        }, full())

        val ov = card(page, R.string.h_overlay)
        overlayBlock(ov, withSliders = true)

        if (resources.getBoolean(R.bool.fast)) {
            val fast = card(page, R.string.a11y_open)
            fast.addView(label(getString(R.string.a11y_info), 14f))
            fast.addView(button(R.string.a11y_open, false) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, full())
        }

        val safe = card(page, R.string.h_safe)
        safe.addView(label(getString(R.string.safe_text), 14f))
        safe.addView(button(R.string.privacy_link, false) { open("/legal/privacy") }, full())
        safe.addView(button(R.string.terms_link, false) { open("/legal/terms") }, full())
        page.addView(label(getString(R.string.about_text, Updates.current(this)), 12f, Color.GRAY))
        setContentView(ScrollView(this).apply { addView(page) })
        sync()
    }

    // ---------- общие блоки ----------

    private fun keyBlock(c: LinearLayout) {
        c.addView(label(getString(R.string.key_label), 14f))
        keyField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setHint(R.string.key_hint)
            setSingleLine()
            if (!store.key.isNullOrBlank()) setText(MASK)
        }
        c.addView(keyField, full())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(R.string.save, true) { save() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button(R.string.check, false) { checkKey() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(row, full())
        info = label("", 14f, blue, bold = true)
        c.addView(info)
    }

    private fun permissionsBlock(c: LinearLayout) {
        c.addView(label(getString(R.string.watch_info), 14f))
        watchSwitch = Switch(this).apply {
            setText(R.string.watch_enable)
            textSize = 16f
            setOnCheckedChangeListener { _, on ->
                if (syncing) return@setOnCheckedChangeListener
                store.watchOn = on
                if (on) {
                    if (!SetupCheck.photosGranted(this@MainActivity)) askPhotos()
                    WatchService.start(this@MainActivity)
                } else {
                    WatchService.stop(this@MainActivity)
                }
            }
        }
        c.addView(watchSwitch, full())
        watchInfo = label("", 13f, Color.rgb(160, 50, 50))
        c.addView(watchInfo)
        c.addView(button(R.string.allow_notifications, false) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }, full())
        c.addView(button(R.string.watch_perm, false) { askPhotos() }, full())
        c.addView(label(getString(R.string.tile_hint), 13f, Color.GRAY))
    }

    private fun overlayBlock(c: LinearLayout, withSliders: Boolean) {
        overlaySwitch = Switch(this).apply {
            setText(R.string.ov_enable)
            textSize = 16f
            setOnCheckedChangeListener { _, on ->
                if (syncing) return@setOnCheckedChangeListener
                store.overlayOn = on
                if (on && !OverlayController.canDraw(this@MainActivity)) grantOverlay()
            }
        }
        c.addView(overlaySwitch, full())
        c.addView(label(getString(R.string.ov_info), 14f))
        permInfo = label("", 13f, Color.GRAY)
        c.addView(permInfo)
        c.addView(button(R.string.ov_grant, false) { grantOverlay() }, full())
        c.addView(button(R.string.ov_place, true) {
            if (OverlayController.canDraw(this)) OverlayController.edit(this) else grantOverlay()
        }, full())
        if (withSliders) {
            c.addView(button(R.string.ov_preview, false) {
                if (OverlayController.canDraw(this)) OverlayController.preview(this) else grantOverlay()
            }, full())
            slider(c, R.string.ov_alpha, 20, 100, { store.alpha }, { store.alpha = it })
            slider(c, R.string.ov_size, 40, 130, { store.size }, { store.size = it })
        }
        c.addView(label(getString(R.string.ov_warn), 12f, Color.GRAY))
        c.addView(button(R.string.terms_link, false) { open("/legal/terms") }, full())
    }

    private fun sync() {
        syncing = true
        watchSwitch?.isChecked = store.watchOn
        overlaySwitch?.isChecked = store.overlayOn && OverlayController.canDraw(this)
        syncing = false
        watchInfo?.text = if (SetupCheck.photosGranted(this)) "" else getString(R.string.watch_perm_no)
        permInfo?.setText(if (OverlayController.canDraw(this)) R.string.ov_perm_ok else R.string.ov_perm_no)
        if (store.watchOn && SetupCheck.photosGranted(this) && !WatchService.running) WatchService.start(this)
    }

    private fun askPhotos() {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        requestPermissions(arrayOf(p), 2)
    }

    private fun grantOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun open(path: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Uploader.BASE + path)))
    }

    private fun runCheck() {
        info?.setText(R.string.s_sending)
        Thread {
            val text = SetupCheck.run(this).joinToString("\n")
            runOnUiThread { info?.text = text }
        }.start()
    }

    // ---------- ключ ----------

    private fun currentKey(): String? {
        val typed = keyField?.text?.toString()?.trim() ?: ""
        return if (typed == MASK) store.key else typed.ifBlank { null }
    }

    private fun save() {
        val k = currentKey()
        if (k == null) {
            info?.setText(R.string.s_no_key)
            return
        }
        store.key = k
        keyField?.setText(MASK)
        info?.setText(R.string.saved)
    }

    private fun checkKey() {
        val k = currentKey()
        if (k == null) {
            info?.setText(R.string.s_no_key)
            return
        }
        info?.setText(R.string.s_sending)
        Thread {
            // Тестовый запрос: слишком короткое тело. Сервер отвечает 400, если ключ верный, и 401, если нет. Колода не запускается.
            val msg = when (Uploader(Uploader.SHOT_URL, attempts = 1).send(k, ByteArray(10))) {
                is SendResult.Empty, is SendResult.Ok, is SendResult.TooFast -> R.string.check_ok
                is SendResult.BadKey -> R.string.s_bad_key
                is SendResult.NotYet -> R.string.chk_soon
                is SendResult.Network -> R.string.s_net
                else -> R.string.s_error
            }
            runOnUiThread { info?.setText(msg) }
        }.start()
    }

    // ---------- элементы ----------

    private fun pageView() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(14), px(14), px(14), px(24))
        setBackgroundColor(Color.rgb(242, 244, 248))
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

    companion object {
        private const val MASK = "••••••••••••"
        private const val STEPS = 5
    }
}
