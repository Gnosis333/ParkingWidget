package com.example.parkingwidget

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class BleMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "parking_monitor"
        private const val NOTIF_ID = 2001
        private const val COOLDOWN_MS = 30_000L     // 같은 층 재감지 무시 시간
        private const val ANCHOR_FRESH_MS = 20_000L // 최근 N초 내 본 앵커만 유효

        /**
         * 백그라운드 감지 서비스 가동 여부.
         * 프로세스가 살아있고 서비스가 도는 동안만 true.
         * 재부팅/강제종료로 프로세스가 죽으면 새 프로세스에서 기본값 false → 위젯이 회색으로 표시됨.
         */
        @Volatile
        var isRunning = false
            private set
    }

    private var lastFloor = 0
    private var lastUpdateTime = 0L

    // 최근 본 앵커: MAC -> (rssi, elapsedRealtime)
    private val recentAnchors = HashMap<String, Pair<Int, Long>>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address.uppercase()
            if (ParkingAnchors.anchorFloor(mac) == 0) return  // 앵커 아니면 무시
            recentAnchors[mac] = result.rssi to SystemClock.elapsedRealtime()
            evaluateFloor()
        }
    }

    /** 최근 본 앵커들 중 더 강한 층으로 판정해 위젯 갱신 */
    private fun evaluateFloor() {
        val now = SystemClock.elapsedRealtime()
        recentAnchors.entries.removeAll { now - it.value.second > ANCHOR_FRESH_MS }

        val f1Best = recentAnchors.filterKeys { ParkingAnchors.anchorFloor(it) == 1 }
            .values.maxOfOrNull { it.first }
        val f2Best = recentAnchors.filterKeys { ParkingAnchors.anchorFloor(it) == 2 }
            .values.maxOfOrNull { it.first }

        val floor = when {
            f1Best == null && f2Best == null -> return
            f2Best == null || (f1Best != null && f1Best >= f2Best) -> 1
            else -> 2
        }
        if (floor == lastFloor && now - lastUpdateTime < COOLDOWN_MS) return
        lastFloor = floor
        lastUpdateTime = now
        saveAndUpdateWidget(floor)
    }

    // BT 켜짐/꺼짐 감지 → 스캔 재시작
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> startBleScan()
                BluetoothAdapter.STATE_OFF -> { /* 스캐너가 자동 무효화됨 */ }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("감지 대기 중"))
        isRunning = true
        ParkingWidgetProvider.refreshAll(this)  // 위젯을 활성(컬러) 상태로 전환
        startBleScan()
        return START_STICKY  // 시스템이 종료해도 자동 재시작
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val btAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (btAdapter == null || !btAdapter.isEnabled) return

        // 앵커 신호가 약해(-90대) LOW_POWER로는 잘 놓침 → BALANCED로 검출률 ↑
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        // 앵커 MAC만 하드웨어 필터 (배터리 절약 + 노이즈 제거)
        val filters = try {
            ParkingAnchors.ALL_ANCHORS.map {
                ScanFilter.Builder().setDeviceAddress(it).build()
            }
        } catch (e: Exception) { null }  // 일부 기기 ScanFilter 예외 방어

        btAdapter.bluetoothLeScanner?.startScan(filters, settings, scanCallback)
    }

    private fun saveAndUpdateWidget(floor: Int) {
        getSharedPreferences("ParkingWidgetPrefs", MODE_PRIVATE)
            .edit().putInt("SelectedFloor", floor).apply()

        val awm = AppWidgetManager.getInstance(this)
        val ids = awm.getAppWidgetIds(ComponentName(this, ParkingWidgetProvider::class.java))
        ids.forEach { ParkingWidgetProvider.updateAppWidget(this, awm, it) }

        val label = if (floor == 1) "지하 1층 감지됨" else "지하 2층 감지됨"
        notify(label)
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "주차 자동감지", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("주차 자동감지")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
        ParkingWidgetProvider.refreshAll(this)  // 위젯을 비활성(회색) 상태로 전환
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
