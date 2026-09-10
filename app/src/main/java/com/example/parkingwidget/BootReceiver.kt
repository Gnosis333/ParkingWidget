package com.example.parkingwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 부팅·앱 업데이트 후 감지 서비스 복구.
 *
 * MY_PACKAGE_REPLACED도 함께 받는 이유: 앱을 새로 설치하면 프로세스가 죽고
 * 워치독 알람도 함께 지워져서, 사용자가 앱을 직접 열어 버튼을 누르기 전까지
 * 자동감지가 조용히 꺼져 있었다(위젯은 회색으로만 표시). 업데이트 직후 스스로 복구한다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                try {
                    ParkingWidgetProvider.startMonitor(context)
                } catch (e: Exception) {
                    MonitorLog.log(context, "${intent.action} 후 서비스 시작 실패: ${e.message}")
                }
            }
        }
    }
}
