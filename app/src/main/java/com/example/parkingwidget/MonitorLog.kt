package com.example.parkingwidget

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 백그라운드 서비스 진단용 파일 로그 (monitor_log.txt).
 *
 * 배경: scan_log.txt는 MainActivity 수동 스캔만 기록해서, 서비스가 "언제 어떻게"
 * 죽었는지는 알 수 없었다. 서비스 생명주기·스캔 시작/실패·워치독 발화·층 판정을
 * 타임스탬프와 함께 남겨 다음 장애 때 정확히 진단한다.
 *
 * 회수: adb pull /sdcard/Android/data/com.example.parkingwidget/files/monitor_log.txt
 */
object MonitorLog {

    private const val FILE_NAME = "monitor_log.txt"
    private const val MAX_BYTES = 256L * 1024      // 초과 시 뒤쪽 절반만 남기고 절단

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)

    @Synchronized
    fun log(context: Context, msg: String) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, FILE_NAME)
            if (file.length() > MAX_BYTES) {
                val tail = file.readText().takeLast((MAX_BYTES / 2).toInt())
                file.writeText("(절단됨)\n$tail")
            }
            file.appendText("${fmt.format(System.currentTimeMillis())} $msg\n")
        } catch (_: Exception) {
            // 로깅 실패가 서비스를 죽이면 안 됨
        }
    }
}
