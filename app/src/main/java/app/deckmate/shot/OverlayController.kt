package app.deckmate.shot

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Полупрозрачная плашка с колодой соперника поверх экрана. В бою не принимает касания (они проходят в игру).
 * Положение, размер и прозрачность задаются в настройках; режим настройки позволяет перетащить плашку пальцем.
 */
object OverlayController {
    private val ui = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val icons = HashMap<String, ByteArray>()
    private const val HIDE_MS = 150_000L
    private const val MSG_MS = 12_000L

    fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun show(ctx: Context, deck: Last, hideMs: Long = HIDE_MS) {
        val app = ctx.applicationContext
        val loaded = deck.cards.map { c -> c.icon?.let { p -> icons.getOrPut(p) { DeckApi.icon(p) ?: ByteArray(0) } } }
        ui.post { showRow(app, deck.cards, loaded, hideMs, false) }
    }

    /** Короткое сообщение на месте плашки (колода не найдена, нет ответа и т. п.). */
    fun message(ctx: Context, text: String, ms: Long = MSG_MS) {
        val app = ctx.applicationContext
        ui.post {
            hide(app)
            if (!canDraw(app)) return@post
            val d = app.resources.displayMetrics.density
            val tv = TextView(app).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
                maxWidth = (app.resources.displayMetrics.widthPixels * 0.9).toInt()
                background = GradientDrawable().apply {
                    setColor(Color.argb(215, 120, 30, 40))
                    cornerRadius = 10 * d
                }
                alpha = SecureStore(app).alpha / 100f
            }
            attach(app, tv, ms, touchable = false)
        }
    }

    /** Колода как в игре: два ряда по 4 карты; эволюции и герои в цветной рамке с пометкой. */
    private fun buildRow(app: Context, cards: List<Card>, imgs: List<ByteArray?>): LinearLayout {
        val store = SecureStore(app)
        val d = app.resources.displayMetrics.density
        val k = store.size / 100f
        val cw = (50 * d * k).toInt()
        val ch = (60 * d * k).toInt()
        val gap = (3 * d).toInt()
        val grid = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gap, gap, gap, gap)
            background = GradientDrawable().apply {
                setColor(Color.argb(200, 12, 16, 28))
                cornerRadius = 10 * d
            }
        }
        val costs = cards.mapNotNull { it.elixir }
        if (costs.isNotEmpty()) {
            val avg = Math.round(costs.sum() * 10.0 / costs.size) / 10.0
            grid.addView(TextView(app).apply {
                text = app.getString(R.string.ov_avg, avg.toString())
                setTextColor(Color.WHITE)
                textSize = 12f * k
                setPadding((4 * d).toInt(), 0, 0, (2 * d).toInt())
            })
        }
        for (r in 0 until 2) {
            val line = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until 4) {
                val i = r * 4 + col
                val c = cards.getOrNull(i) ?: continue
                val special = c.hero || c.evo
                val frameColor = if (c.hero) Color.rgb(255, 197, 51) else Color.rgb(170, 90, 255)
                val cell = FrameLayout(app).apply {
                    if (special) background = GradientDrawable().apply {
                        setStroke((2 * d).toInt(), frameColor)
                        cornerRadius = 6 * d
                    }
                    setPadding((1 * d).toInt(), (1 * d).toInt(), (1 * d).toInt(), (1 * d).toInt())
                }
                val img = ImageView(app).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                imgs.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { img.setImageBitmap(BitmapFactory.decodeByteArray(it, 0, it.size)) }
                cell.addView(img, FrameLayout.LayoutParams(cw, ch))
                if (c.elixir != null) {
                    cell.addView(TextView(app).apply {
                        text = c.elixir.toString()
                        setTextColor(Color.WHITE)
                        textSize = 10f * k
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.rgb(190, 60, 220))
                        }
                    }, FrameLayout.LayoutParams((16 * d * k).toInt(), (16 * d * k).toInt(), Gravity.TOP or Gravity.START))
                }
                if (special) {
                    cell.addView(TextView(app).apply {
                        text = if (c.hero) "H" else "E"
                        setTextColor(if (c.hero) Color.rgb(40, 30, 0) else Color.WHITE)
                        textSize = 9f * k
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(frameColor)
                        }
                    }, FrameLayout.LayoutParams((14 * d * k).toInt(), (14 * d * k).toInt(), Gravity.BOTTOM or Gravity.END))
                }
                line.addView(cell, LinearLayout.LayoutParams(cw + (2 * d).toInt() + (2 * d).toInt(), ch + (4 * d).toInt()).apply {
                    setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
                })
            }
            grid.addView(line)
        }
        grid.alpha = store.alpha / 100f
        return grid
    }

    private fun showRow(app: Context, cards: List<Card>, imgs: List<ByteArray?>, hideMs: Long, touchable: Boolean) {
        hide(app)
        if (!canDraw(app)) return
        attach(app, buildRow(app, cards, imgs), hideMs, touchable)
    }

    private fun attach(app: Context, v: View, hideMs: Long, touchable: Boolean) {
        val store = SecureStore(app)
        val wm = app.getSystemService(WindowManager::class.java)
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val b = wm.currentWindowMetrics.bounds
        p.x = ((b.width() - v.measuredWidth) * store.posX / 100f).toInt().coerceAtLeast(0)
        val top = b.height() / 2  // верхняя половина закрыта: там ник, клан, уровень короля и лига соперника
        p.y = (top + (b.height() - top - v.measuredHeight) * store.posY / 100f).toInt().coerceAtLeast(top)
        try {
            wm.addView(v, p)
            view = v
            if (hideMs > 0) ui.postDelayed({ hide(app) }, hideMs)
        } catch (e: Exception) {
            view = null
        }
    }

    fun hide(ctx: Context) {
        ui.removeCallbacksAndMessages(null)
        view?.let {
            try {
                ctx.applicationContext.getSystemService(WindowManager::class.java).removeView(it)
            } catch (e: Exception) {
            }
        }
        view = null
    }

    private fun sampleCards() = (1..8).map { Card("", it % 6 + 1, it == 1, it == 2, null) }

    /** Пробная плашка на 8 секунд. */
    fun preview(ctx: Context) {
        val app = ctx.applicationContext
        val cards = sampleCards()
        ui.post { showRow(app, cards, cards.map { null }, 8000, false) }
    }

    /** Режим настройки: плашку можно перетащить, кнопки меняют размер, «Готово» сохраняет положение. */
    fun edit(ctx: Context) {
        val app = ctx.applicationContext
        ui.post {
            hide(app)
            if (!canDraw(app)) return@post
            val store = SecureStore(app)
            val d = app.resources.displayMetrics.density
            val cards = sampleCards()
            val box = LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.argb(120, 47, 111, 235))
                    cornerRadius = 12 * d
                }
            }
            box.addView(buildRow(app, cards, cards.map { null }))
            box.addView(TextView(app).apply {
                setText(R.string.ov_drag)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            })
            val btns = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
            fun btn(label: String, action: () -> Unit) = Button(app).apply {
                text = label
                setOnClickListener { action() }
                btns.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            btn("−") { store.size = (store.size - 10).coerceAtLeast(40); edit(app) }
            btn("+") { store.size = (store.size + 10).coerceAtMost(130); edit(app) }
            btn(app.getString(R.string.ov_done)) { hide(app) }
            box.addView(btns)
            attach(app, box, 0, touchable = true)
            val wm = app.getSystemService(WindowManager::class.java)
            val p = box.layoutParams as WindowManager.LayoutParams
            var sx = 0f
            var sy = 0f
            var ox = 0
            var oy = 0
            box.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val b = wm.currentWindowMetrics.bounds
                        p.x = (ox + (e.rawX - sx)).toInt().coerceIn(0, (b.width() - box.width).coerceAtLeast(0))
                        val top = b.height() / 2
                        p.y = (oy + (e.rawY - sy)).toInt().coerceIn(top, (b.height() - box.height).coerceAtLeast(top))
                        wm.updateViewLayout(box, p)
                    }
                    MotionEvent.ACTION_UP -> {
                        val b = wm.currentWindowMetrics.bounds
                        val fx = (b.width() - box.width).coerceAtLeast(1)
                        val top = b.height() / 2
                        val fy = (b.height() - top - box.height).coerceAtLeast(1)
                        store.posX = (p.x * 100 / fx).coerceIn(0, 100)
                        store.posY = ((p.y - top) * 100 / fy).coerceIn(0, 100)
                    }
                }
                true
            }
        }
    }
}
