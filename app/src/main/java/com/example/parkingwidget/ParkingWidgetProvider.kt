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

        // 비활성(회색) 상태 텍스트 색상
        private const val DISABLED_TEXT = 0xFF6E6E6E.toInt()      // 미선택 층
        private const val DISABLED_TEXT_SEL = 0xFFBDBDBD.toInt()  // 마지막 선택 층 (살짝 밝게)

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val selectedFloor = prefs.getInt(PREF_KEY_FLOOR, 0)

            val views = RemoteViews(context.packageName, R.layout.widget_parking)

            if (!BleMonitorService.isRunning) {
                // 백그라운드 감지 OFF → 회색으로 "가동 안 됨" 표시.
                // 탭하면 앱을 열어 바로 "백그라운드 켜기" 할 수 있게 유도.
                renderDisabled(context, views, selectedFloor)
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

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

        /** 백그라운드 서비스가 꺼진 상태의 회색 렌더링 + 탭 시 앱 실행 */
        private fun renderDisabled(context: Context, views: RemoteViews, selectedFloor: Int) {
            val open = getOpenAppIntent(context)
            views.setOnClickPendingIntent(R.id.layout_f1, open)
            views.setOnClickPendingIntent(R.id.layout_f2, open)

            views.setInt(R.id.layout_f1, "setBackgroundResource", R.drawable.bg_disabled)
            views.setInt(R.id.layout_f2, "setBackgroundResource", R.drawable.bg_disabled)
            views.setTextColor(R.id.tv_f1, if (selectedFloor == 1) DISABLED_TEXT_SEL else DISABLED_TEXT)
            views.setTextColor(R.id.tv_f2, if (selectedFloor == 2) DISABLED_TEXT_SEL else DISABLED_TEXT)
        }

        /** 모든 위젯 인스턴스를 현재 상태로 다시 그린다 (서비스 on/off 전환 시 호출). */
        fun refreshAll(context: Context) {
            val awm = AppWidgetManager.getInstance(context)
            val ids = awm.getAppWidgetIds(ComponentName(context, ParkingWidgetProvider::class.java))
            ids.forEach { updateAppWidget(context, awm, it) }
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

        private fun getOpenAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context, 1000, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
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
