package com.example.parkingwidget

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class BleMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "parking_monitor"
        private const val NOTIF_ID = 2001
        private const val COOLDOWN_MS = 30_000L     // 같은 층 재감지 무시 시간
        // 최근 N초 내 본 앵커만 유효. 1층 keybox처럼 약하고 드물게 잡히는 앵커가
        // 만료돼 판정 불가가 되지 않도록 20→30초로 완화.
        private const val ANCHOR_FRESH_MS = 30_000L

        // ── 자가치유(안정성) 파라미터 ──
        // 안드로이드/삼성은 오래 도는 BLE 스캔 콜백을 프로세스는 살려둔 채 조용히 무력화한다
        // (화면꺼짐 opportunistic 강등, 절전 앱 동결 등). 강제종료 없이도 스스로 회복하도록:
        //  ① 주기적으로 스캔을 stop→start 해서 강등된 스캐너를 되살린다.
        private const val RESCAN_INTERVAL_MS = 20 * 60_000L    // 20분마다 스캔 재시작
        //  ② onScanFailed 시 백오프를 두고 재시작(5회/30초 프레임워크 제한 회피).
        private const val FAIL_BACKOFF_BASE_MS = 5_000L
        private const val FAIL_BACKOFF_MAX_MS = 120_000L
        //  ③ 앵커가 오래 안 보이면 알림에 "재탐색 중"을 표시(조용한 고장을 사용자에게 노출).
        private const val ANCHOR_STALE_NOTIFY_MS = 15 * 60_000L
        //  ④ Doze 대응 워치독: Handler 타이머는 uptimeMillis 기반이라 깊은 잠에서 멈춘다.
        //     AlarmManager(setAndAllowWhileIdle)는 Doze에서도 발화하고 죽은 프로세스도 깨운다.
        private const val WATCHDOG_INTERVAL_MS = 15 * 60_000L
        private const val WATCHDOG_REQ_CODE = 3001
        //  ⑤ 화면 켜짐 버스트 스캔: 백그라운드 필터 스캔이 못 잡아도, 사용자가 폰을 꺼내
        //     위젯을 보는 순간 무필터·LOW_LATENCY로 짧게 스캔(수동 스캔과 동일 조건 = 검증됨).
        private const val BURST_DURATION_MS = 25_000L
        private const val BURST_MIN_INTERVAL_MS = 60_000L   // 화면 껐다켰다 반복 시 과도 스캔 방지
        // 진단 로그 과다 방지: 앵커 수신 기록은 소스별 1분에 1회만
        private const val ANCHOR_LOG_INTERVAL_MS = 60_000L

        /** 다음 워치독 알람 예약 (기존 예약은 같은 PendingIntent로 대체됨) */
        fun scheduleWatchdog(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + WATCHDOG_INTERVAL_MS,
                watchdogIntent(context)
            )
        }

        /** 명시적으로 감지를 끌 때만 호출 (위젯 제거 등). 예기치 못한 사망 시엔 알람이 살아있어야 부활한다. */
        fun cancelWatchdog(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(watchdogIntent(context))
        }

        private fun watchdogIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, WATCHDOG_REQ_CODE,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

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

    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false
    @Volatile private var lastAnchorSeen = 0L           // 마지막으로 앵커를 본 시각(elapsedRealtime)
    private var failBackoffMs = FAIL_BACKOFF_BASE_MS    // onScanFailed 재시작 백오프(가변)

    private var lastAnchorLogAt = 0L

    /** 필터 스캔/버스트 스캔 공용 앵커 처리. source는 진단 로그용("백그라운드"/"버스트"). */
    private fun onAnchorSeen(source: String, mac: String, rssi: Int) {
        val now = SystemClock.elapsedRealtime()
        lastAnchorSeen = now
        failBackoffMs = FAIL_BACKOFF_BASE_MS              // 정상 수신 → 백오프 리셋
        recentAnchors[mac] = rssi to now
        // 어느 경로가 앵커를 잡는지 파악하기 위한 레이트리밋 로그
        if (now - lastAnchorLogAt > ANCHOR_LOG_INTERVAL_MS) {
            lastAnchorLogAt = now
            MonitorLog.log(this, "[$source] 앵커 수신 ${mac.takeLast(8)} rssi=$rssi")
        }
        evaluateFloor()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address.uppercase()
            if (ParkingAnchors.anchorFloor(mac) == 0) return  // 앵커 아니면 무시
            onAnchorSeen("백그라운드", mac, result.rssi)
        }

        // 스캔이 조용히 죽는 주요 경로. 여기서 잡아 백오프 후 재시작한다.
        // (없으면 서비스는 스캔이 죽은 줄도 모르고, 강제종료 전까지 위젯이 멈춘다.)
        override fun onScanFailed(errorCode: Int) {
            MonitorLog.log(this@BleMonitorService, "onScanFailed errorCode=$errorCode → ${failBackoffMs / 1000}s 후 재시작")
            scanning = false
            handler.removeCallbacks(failRestartRunnable)
            handler.postDelayed(failRestartRunnable, failBackoffMs)
            failBackoffMs = (failBackoffMs * 2).coerceAtMost(FAIL_BACKOFF_MAX_MS)
        }
    }

    // 백오프 재시작: 스캔을 완전히 내렸다가 다시 올린다.
    private val failRestartRunnable = Runnable { restartScan() }

    // 20분 주기 자가치유(폰이 깨어있을 때용): 강등/동결된 스캐너를 stop→start로 되살리고,
    // 앵커가 오래 안 잡히면 알림에 상태를 노출한 뒤 다음 주기를 예약한다.
    // 주의: Handler 타이머는 Doze에서 멈추므로, 잠든 폰은 AlarmManager 워치독이 커버한다.
    private val selfHealRunnable = object : Runnable {
        override fun run() {
            MonitorLog.log(this@BleMonitorService, "자가치유 틱 → 스캔 재시작")
            restartScan()
            val staleFor = SystemClock.elapsedRealtime() - lastAnchorSeen
            if (lastAnchorSeen != 0L && staleFor > ANCHOR_STALE_NOTIFY_MS) {
                notify("앵커 미감지 — 재탐색 중")
            }
            handler.postDelayed(this, RESCAN_INTERVAL_MS)
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
                // 스캐너가 자동 무효화됨 → 플래그를 내려 STATE_ON 때 재시작이 막히지 않게 한다.
                BluetoothAdapter.STATE_OFF -> { scanning = false; burstScanning = false }
            }
        }
    }

    // ── 화면 켜짐 버스트 스캔 ──
    // 사용자가 폰을 꺼내 위젯을 확인하는 순간 = 수동 스캔이 항상 성공하던 조건(화면 온).
    // 무필터 스캔은 화면 꺼짐 상태에선 OS가 차단하지만 이 순간엔 허용된다.
    private var burstScanning = false
    private var lastBurstAt = 0L

    private val burstCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address.uppercase()
            if (ParkingAnchors.anchorFloor(mac) == 0) return  // 소프트웨어 필터
            onAnchorSeen("버스트", mac, result.rssi)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) startBurstScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBurstScan() {
        val now = SystemClock.elapsedRealtime()
        if (burstScanning || now - lastBurstAt < BURST_MIN_INTERVAL_MS) return
        val btAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = btAdapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, burstCallback)  // 무필터 = 수동 스캔과 동일
            burstScanning = true
            lastBurstAt = now
            handler.removeCallbacks(stopBurstRunnable)
            handler.postDelayed(stopBurstRunnable, BURST_DURATION_MS)
        } catch (e: Exception) {
            MonitorLog.log(this, "버스트 스캔 시작 실패: ${e.message}")
        }
    }

    private val stopBurstRunnable = Runnable { stopBurstScan() }

    @SuppressLint("MissingPermission")
    private fun stopBurstScan() {
        if (!burstScanning) return
        burstScanning = false
        try {
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.bluetoothLeScanner?.stopScan(burstCallback)
        } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        MonitorLog.log(this, "onCreate (프로세스 새로 시작)")
        createChannel()
        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MonitorLog.log(this, "onStartCommand (sticky재시작=${intent == null})")
        startForeground(NOTIF_ID, buildNotification("감지 대기 중"))
        isRunning = true
        ParkingWidgetProvider.refreshAll(this)  // 위젯을 활성(컬러) 상태로 전환
        // 무조건 stop→start. startBleScan()만 부르면 scanning 플래그 때문에
        // 좀비 스캔(등록은 돼있으나 콜백이 죽은 상태)을 그대로 두고 no-op 된다.
        restartScan()
        // 주기적 자가치유 예약(중복 예약 방지 위해 기존 예약 제거 후 등록)
        handler.removeCallbacks(selfHealRunnable)
        handler.postDelayed(selfHealRunnable, RESCAN_INTERVAL_MS)
        // Doze 대응 워치독 체인: 매 시작마다 다음 알람을 예약 (같은 PI라 중복 없음)
        scheduleWatchdog(this)
        return START_STICKY  // 시스템이 종료해도 자동 재시작
    }

    // 사용자가 최근 앱 목록에서 앱을 밀어 없앨 때. 삼성은 이때 서비스를 죽이는 경우가
    // 있으므로 로그만 남긴다 — 부활은 워치독 알람이 담당.
    override fun onTaskRemoved(rootIntent: Intent?) {
        MonitorLog.log(this, "onTaskRemoved (앱 스와이프 제거)")
        super.onTaskRemoved(rootIntent)
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val btAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (btAdapter == null || !btAdapter.isEnabled) return
        if (scanning) return  // 이미 스캔 중이면 중복 시작 방지

        // 앵커 신호가 매우 약하고(-90대) 1층은 앵커가 사실상 1개(keybox, ~-91dBm)뿐이라
        // BALANCED(듀티사이클)로는 자주 놓쳐 1층 미감지가 발생했다.
        // → 포그라운드 디버거처럼 LOW_LATENCY(연속) + 공격적 매칭으로 포착률을 올린다.
        // (포그라운드 서비스 + 배터리 최적화 예외 상태이므로 상시 스캔 허용)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)     // 약한 신호도 즉시 보고
                    setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                }
            }
            .build()

        // 앵커만 하드웨어 필터 (배터리 절약 + 노이즈 제거 + 화면꺼짐 스캔 허용 조건).
        // 주의: setDeviceAddress(mac)는 주소 타입 PUBLIC 가정 → 랜덤 주소로 광고하는
        // 앵커는 영원히 안 걸림 (2026-07-10 실측: 스캔은 돌았는데 24시간 수신 0건).
        // 타입 지정 버전(2-인자)은 SystemApi라 못 씀 → 주소 타입과 무관한 "이름 필터"를
        // 병행 등록해 광고를 통과시키고, 콜백에서 MAC으로 재검증한다.
        val filters = try {
            ParkingAnchors.ALL_ANCHORS.map { mac ->
                ScanFilter.Builder().setDeviceAddress(mac).build()
            } + ParkingAnchors.ANCHOR_NAMES.map { name ->
                ScanFilter.Builder().setDeviceName(name).build()
            }
        } catch (e: Exception) { null }  // 일부 기기 ScanFilter 예외 방어

        val scanner = btAdapter.bluetoothLeScanner ?: return
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            MonitorLog.log(this, "스캔 시작됨")
        } catch (e: Exception) {
            scanning = false  // startScan 예외 방어 (일부 기기)
            MonitorLog.log(this, "스캔 시작 실패: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!scanning) return
        try {
            val btAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            btAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        scanning = false
    }

    /** 강등/동결/실패한 스캐너를 stop→start로 되살린다. */
    private fun restartScan() {
        stopBleScan()
        startBleScan()
    }

    private fun saveAndUpdateWidget(floor: Int) {
        MonitorLog.log(this, "층 판정 → 지하 ${floor}층 (위젯 갱신)")
        getSharedPreferences("ParkingWidgetPrefs", MODE_PRIVATE)
            .edit().putInt("SelectedFloor", floor).apply()

        val awm = AppWidgetManager.getInstance(this)
        val ids = awm.getAppWidgetIds(ComponentName(this, ParkingWidgetProvider::class.java))
        ids.forEach { ParkingWidgetProvider.updateAppWidget(this, awm, it) }

        // 알림에 감지 시각을 남겨 "서비스가 마지막으로 살아있던 때"를 한눈에 확인 가능하게.
        val ts = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
            .format(System.currentTimeMillis())
        notify("지하 ${floor}층 감지됨 ($ts)")
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
        MonitorLog.log(this, "onDestroy (서비스 종료)")
        isRunning = false
        handler.removeCallbacks(selfHealRunnable)
        handler.removeCallbacks(failRestartRunnable)
        handler.removeCallbacks(stopBurstRunnable)
        stopBleScan()
        stopBurstScan()
        try { unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        // 워치독 알람은 여기서 취소하지 않는다 — 시스템/삼성이 서비스를 죽인 경우
        // 알람이 살아있어야 다음 발화 때 부활한다. 명시적 중지(위젯 제거)는
        // ParkingWidgetProvider.stopMonitor가 cancelWatchdog을 호출한다.
        ParkingWidgetProvider.refreshAll(this)  // 위젯을 비활성(회색) 상태로 전환
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
