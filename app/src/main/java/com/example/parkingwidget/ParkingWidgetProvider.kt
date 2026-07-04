package com.example.parkingwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews

class ParkingWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val PREFS_NAME = "ParkingWidgetPrefs"
        private const val PREF_KEY_FLOOR = "SelectedFloor"
        private const val ACTION_SELECT_F1 = "com.example.parkingwidget.ACTION_SELECT_F1"
        private const val ACTION_SELECT_F2 = "com.example.parkingwidget.ACTION_SELECT_F2"

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val selectedFloor = prefs.getInt(PREF_KEY_FLOOR, 0)

            val views = RemoteViews(context.packageName, R.layout.widget_parking)
            views.setOnClickPendingIntent(R.id.layout_f1, getPendingIntent(context, ACTION_SELECT_F1, appWidgetId))
            views.setOnClickPendingIntent(R.id.layout_f2, getPendingIntent(context, ACTION_SELECT_F2, appWidgetId))

            when (selectedFloor) {
                1 -> {
                    views.setInt(R.id.layout_f1, "setBackgroundResource", R.drawable.bg_selected)
                    views.setTextColor(R.id.tv_f1, Color.BLACK)
                    views.setInt(R.id.layout_f2, "setBackgroundResource", R.drawable.bg_unselected)
                    views.setTextColor(R.id.tv_f2, Color.WHITE)
                }
                2 -> {
                    views.setInt(R.id.layout_f1, "setBackgroundResource", R.drawable.bg_unselected)
                    views.setTextColor(R.id.tv_f1, Color.WHITE)
                    views.setInt(R.id.layout_f2, "setBackgroundResource", R.drawable.bg_selected)
                    views.setTextColor(R.id.tv_f2, Color.BLACK)
                }
                else -> {
                    views.setInt(R.id.layout_f1, "setBackgroundResource", R.drawable.bg_unselected)
                    views.setTextColor(R.id.tv_f1, Color.WHITE)
                    views.setInt(R.id.layout_f2, "setBackgroundResource", R.drawable.bg_unselected)
                    views.setTextColor(R.id.tv_f2, Color.WHITE)
                }
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun startMonitor(context: Context) {
            val intent = Intent(context, BleMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopMonitor(context: Context) {
            context.stopService(Intent(context, BleMonitorService::class.java))
        }

        private fun getPendingIntent(context: Context, action: String, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, ParkingWidgetProvider::class.java).apply {
                this.action = action
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            return PendingIntent.getBroadcast(
                context, appWidgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        startMonitor(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        stopMonitor(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_SELECT_F1, ACTION_SELECT_F2 -> {
                val floor = if (intent.action == ACTION_SELECT_F1) 1 else 2
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putInt(PREF_KEY_FLOOR, floor).apply()

                val awm = AppWidgetManager.getInstance(context)
                val ids = awm.getAppWidgetIds(ComponentName(context, ParkingWidgetProvider::class.java))
                ids.forEach { updateAppWidget(context, awm, it) }
            }
        }
    }
}
