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
        // 층 값·확정 시각은 ParkingState가 단일 출처 (직접 SharedPreferences를 만지지 않는다)
        private const val ACTION_SELECT_F1 = "com.example.parkingwidget.ACTION_SELECT_F1"
        private const val ACTION_SELECT_F2 = "com.example.parkingwidget.ACTION_SELECT_F2"

        // 비활성(회색) 상태 텍스트 색상
        private const val DISABLED_TEXT = 0xFF6E6E6E.toInt()      // 미선택 층
        private const val DISABLED_TEXT_SEL = 0xFFBDBDBD.toInt()  // 마지막 선택 층 (살짝 밝게)

        // 미확인(이번 주차 건에서 앵커를 못 잡음) 상태 색상 — 확정 초록과 확실히 구분되는 호박색
        private const val UNCONFIRMED_TEXT = 0xFFFFC107.toInt()

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val selectedFloor = ParkingState.floor(context)

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

            // 주차는 감지됐는데 층 확정이 없었던 경우 = 표시된 값은 지난 주차 건의 잔상.
            // 초록 확정색 대신 호박색 물음표로 "직접 골라라"를 드러낸다.
            if (ParkingState.isUnconfirmed(context)) {
                renderUnconfirmed(views, selectedFloor)
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            views.setTextViewText(R.id.tv_f1, context.getString(R.string.floor_1))
            views.setTextViewText(R.id.tv_f2, context.getString(R.string.floor_2))

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

        /**
         * "이번 주차 층 미확인" 렌더링 — 마지막 값은 물음표를 붙여 흐리게 남기고,
         * 탭(수동 선택)은 그대로 살려둔다. 값이 없는 것과 낡은 것을 눈으로 구분시키는 게 목적.
         */
        private fun renderUnconfirmed(views: RemoteViews, selectedFloor: Int) {
            views.setInt(R.id.layout_f1, "setBackgroundResource",
                if (selectedFloor == 1) R.drawable.bg_unconfirmed else R.drawable.bg_unselected)
            views.setInt(R.id.layout_f2, "setBackgroundResource",
                if (selectedFloor == 2) R.drawable.bg_unconfirmed else R.drawable.bg_unselected)
            views.setTextColor(R.id.tv_f1, if (selectedFloor == 1) UNCONFIRMED_TEXT else Color.WHITE)
            views.setTextColor(R.id.tv_f2, if (selectedFloor == 2) UNCONFIRMED_TEXT else Color.WHITE)
            views.setTextViewText(R.id.tv_f1, if (selectedFloor == 1) "1F?" else "1F")
            views.setTextViewText(R.id.tv_f2, if (selectedFloor == 2) "2F?" else "2F")
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
            // 위젯 호스트는 같은 레이아웃이면 기존 뷰에 reapply만 한다 — 이전에 붙인 "?"가
            // 남지 않도록 텍스트는 매번 명시적으로 되돌린다.
            views.setTextViewText(R.id.tv_f1, context.getString(R.string.floor_1))
            views.setTextViewText(R.id.tv_f2, context.getString(R.string.floor_2))
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

        /** 주차 이벤트를 실어 서비스를 기동 (서비스가 죽어 있던 경우의 백업 경로) */
        fun startMonitorForParking(context: Context, label: String) {
            val intent = Intent(context, BleMonitorService::class.java)
                .putExtra(BleMonitorService.EXTRA_PARKING_EVENT, true)
                .putExtra(BleMonitorService.EXTRA_PARKING_LABEL, label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopMonitor(context: Context) {
            // 명시적 중지이므로 워치독 알람도 함께 끈다 (안 끄면 알람이 서비스를 부활시킴)
            BleMonitorService.cancelWatchdog(context)
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
                // 수동 선택도 "이번 주차 건의 확정"이다 → 미확인 표시가 풀린다.
                ParkingState.setFloor(context, floor)

                val awm = AppWidgetManager.getInstance(context)
                val ids = awm.getAppWidgetIds(ComponentName(context, ParkingWidgetProvider::class.java))
                ids.forEach { updateAppWidget(context, awm, it) }
            }
        }
    }
}
