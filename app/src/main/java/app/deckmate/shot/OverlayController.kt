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
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Полупрозрачная плашка с колодой соперника поверх экрана. Не принимает касания (нажатия проходят в игру), положение, размер и прозрачность задаются в настройках. */
object OverlayController {
    private val ui = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val icons = HashMap<String, ByteArray>()
    private const val HIDE_MS = 150_000L

    fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun show(ctx: Context, deck: Last, hideMs: Long = HIDE_MS) {
        val app = ctx.applicationContext
        val loaded = deck.cards.map { c -> c.icon?.let { p -> icons.getOrPut(p) { DeckApi.icon(p) ?: ByteArray(0) } } }
        ui.post { render(app, deck.cards, loaded, hideMs) }
    }

    private fun render(app: Context, cards: List<Card>, imgs: List<ByteArray?>, hideMs: Long) {
        hide(app)
        if (!canDraw(app)) return
        val store = SecureStore(app)
        val d = app.resources.displayMetrics.density
        val k = store.size / 100f
        val cw = (46 * d * k).toInt()
        val ch = (56 * d * k).toInt()
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(200, 12, 16, 28))
                cornerRadius = 10 * d
            }
        }
        cards.forEachIndexed { i, c ->
            val cell = FrameLayout(app)
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
                        setColor(if (c.hero) Color.rgb(255, 197, 51) else if (c.evo) Color.rgb(170, 90, 255) else Color.rgb(190, 60, 220))
                    }
                }, FrameLayout.LayoutParams((16 * d * k).toInt(), (16 * d * k).toInt(), Gravity.TOP or Gravity.START))
            }
            row.addView(cell, LinearLayout.LayoutParams(cw, ch))
        }
        row.alpha = store.alpha / 100f
        val wm = app.getSystemService(WindowManager::class.java)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val b = wm.currentWindowMetrics.bounds
        p.x = ((b.width() - row.measuredWidth) * store.posX / 100f).toInt().coerceAtLeast(0)
        p.y = ((b.height() - row.measuredHeight) * store.posY / 100f).toInt().coerceAtLeast(0)
        try {
            wm.addView(row, p)
            view = row
            ui.postDelayed({ hide(app) }, hideMs)
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

    /** Пробная плашка для настройки положения: 8 пустых карт на 8 секунд. */
    fun preview(ctx: Context) {
        val cards = (1..8).map { Card("", it % 6 + 1, it == 1, it == 2, null) }
        ui.post { render(ctx.applicationContext, cards, cards.map { null }, 8000) }
    }
}
