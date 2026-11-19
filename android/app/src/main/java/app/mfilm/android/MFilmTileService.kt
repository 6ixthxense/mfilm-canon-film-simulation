package app.mfilm.android

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class MFilmTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val prefs = getSharedPreferences("MainActivity", MODE_PRIVATE)
        val loaded = prefs.getBoolean("widget_loaded", false)
        val film = prefs.getString("widget_film", "") ?: ""
        val shot = prefs.getInt("widget_shot", 0)
        val exp = prefs.getInt("widget_exp", 0)
        tile.label = "M-Film"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (loaded && film.isNotBlank()) "$film · $shot/$exp" else "Shoot Film"
        }
        tile.state = if (loaded) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
