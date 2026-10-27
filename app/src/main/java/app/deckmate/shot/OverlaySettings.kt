package app.deckmate.shot

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Блок настроек оверлея на главном экране: включить, выдать разрешение, прозрачность, положение, размер, пробная плашка. */
object OverlaySettings {
    fun addTo(act: Activity, col: LinearLayout, store: SecureStore, pad: Int) {
        fun label(res: Int) = TextView(act).apply {
            setText(res)
            setPadding(0, pad / 2, 0, 0)
            col.addView(this)
        }
        fun slider(res: Int, min: Int, get: () -> Int, set: (Int) -> Unit) {
            label(res)
            col.addView(SeekBar(act).apply {
                max = 100 - min
                progress = get() - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) set(p + min)
                    }

                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        OverlayController.preview(act)
                    }
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        label(R.string.ov_title)
        label(R.string.ov_warn)
        col.addView(CheckBox(act).apply {
            setText(R.string.ov_enable)
            isChecked = store.overlayOn
            setOnCheckedChangeListener { _, on ->
                store.overlayOn = on
                if (on && !OverlayController.canDraw(act)) {
                    act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName)))
                }
            }
        })
        slider(R.string.ov_alpha, 20, { store.alpha }, { store.alpha = it })
        slider(R.string.ov_x, 0, { store.posX }, { store.posX = it })
        slider(R.string.ov_y, 0, { store.posY }, { store.posY = it })
        slider(R.string.ov_size, 40, { store.size }, { store.size = it })
        col.addView(Button(act).apply {
            setText(R.string.ov_preview)
            setOnClickListener {
                if (OverlayController.canDraw(act)) OverlayController.preview(act)
                else act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName)))
            }
        })
    }
}
