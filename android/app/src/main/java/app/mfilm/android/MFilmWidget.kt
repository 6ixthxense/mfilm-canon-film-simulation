package app.mfilm.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews

class MFilmWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            manager.updateAppWidget(id, buildViews(context))
        }
    }

    companion object {
        private const val PREF_NAME = "MainActivity"
        private const val K_LOADED = "widget_loaded"
        private const val K_FILM = "widget_film"
        private const val K_ISO = "widget_iso"
        private const val K_SHOT = "widget_shot"
        private const val K_EXP = "widget_exp"
        private const val K_SINGLE = "widget_single"

        fun updateAll(context: Context, ui: Ui) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(K_LOADED, ui.loaded)
                .putString(K_FILM, ui.film)
                .putInt(K_ISO, ui.iso)
                .putInt(K_SHOT, ui.shot)
                .putInt(K_EXP, ui.exposures)
                .putBoolean(K_SINGLE, ui.singleShot)
                .apply()

            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, MFilmWidget::class.java))
            if (ids.isEmpty()) return
            val rv = buildViews(context)
            for (id in ids) mgr.updateAppWidget(id, rv)
        }

        private fun buildViews(context: Context): RemoteViews {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val loaded = prefs.getBoolean(K_LOADED, false)
            val film = prefs.getString(K_FILM, "") ?: ""
            val iso = prefs.getInt(K_ISO, 0)
            val shot = prefs.getInt(K_SHOT, 0)
            val exp = prefs.getInt(K_EXP, 0)
            val single = prefs.getBoolean(K_SINGLE, false)

            val rv = RemoteViews(context.packageName, R.layout.widget_mfilm)
            if (loaded && film.isNotBlank()) {
                rv.setTextViewText(R.id.widget_film, film.uppercase())
                rv.setTextViewText(R.id.widget_iso, if (single) "ISO $iso · SINGLE" else "ISO $iso")
                val counter = when {
                    single -> "%02d SHOT".format(shot)
                    shot >= exp && exp > 0 -> "END · %02d/%02d".format(shot, exp)
                    else -> "%02d / %02d".format(shot, exp)
                }
                rv.setTextViewText(R.id.widget_counter, counter)
            } else {
                rv.setTextViewText(R.id.widget_film, "NO FILM LOADED")
                rv.setTextViewText(R.id.widget_iso, "TAP TO OPEN SHELF")
                rv.setTextViewText(R.id.widget_counter, "-- / --")
            }

            val canisterFile = Card(context).widgetCanisterFile
            if (loaded && canisterFile.exists()) {
                val bmp = BitmapFactory.decodeFile(canisterFile.absolutePath)
                if (bmp != null) {
                    rv.setViewVisibility(R.id.widget_canister, View.VISIBLE)
                    rv.setImageViewBitmap(R.id.widget_canister, bmp)
                } else {
                    rv.setViewVisibility(R.id.widget_canister, View.GONE)
                }
            } else {
                rv.setViewVisibility(R.id.widget_canister, View.GONE)
            }

            val launch = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_root, pi)
            return rv
        }
    }
}
