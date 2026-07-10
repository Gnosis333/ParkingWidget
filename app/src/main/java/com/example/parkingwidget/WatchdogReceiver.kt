package com.example.parkingwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager 워치독 수신자.
 *
 * 배경: Handler.postDelayed는 uptimeMillis 기반이라 Doze(깊은 잠)에서 시계가 멈춰
 * 서비스 내부 자가치유 타이머가 사실상 돌지 않았다. AlarmManager의
 * setAndAllowWhileIdle은 Doze에서도 발화하고, 프로세스가 죽어 있어도 알람이
 * 프로세스를 깨워 서비스를 되살린다 (배터리 최적화 예외 앱이라 백그라운드 FGS 시작 허용).
 *
 * 발화 시 서비스를 (재)시작만 한다 — onStartCommand가 스캔 강제 재시작과
 * 다음 워치독 예약까지 책임진다 (자가 연장 체인).
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MonitorLog.log(context, "워치독 발화 (isRunning=${BleMonitorService.isRunning})")
        try {
            ParkingWidgetProvider.startMonitor(context)
        } catch (e: Exception) {
            // 백그라운드 FGS 시작이 거부되면(배터리 예외 미적용 등) 다음 알람으로 재시도
            MonitorLog.log(context, "워치독 서비스 시작 실패: ${e.message}")
            BleMonitorService.scheduleWatchdog(context)
        }
    }
}
