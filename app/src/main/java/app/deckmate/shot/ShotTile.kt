package app.deckmate.shot

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class ShotTile : TileService() {
    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        // Активность закрывает шторку, затем сама запускает скриншот.
        val i = Intent(this, TriggerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun refresh() {
        val t = qsTile ?: return
        val st = SecureStore(this).status
        t.label = getString(R.string.tile_label)
        t.state = if (CaptureService.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.subtitle = if (CaptureService.active || st != "idle") CaptureService.statusText(this, st) else getString(R.string.s_idle)
        t.updateTile()
    }
}
