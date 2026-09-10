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
import android.bluetooth.BluetoothDevice
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
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class BleMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "parking_monitor"
        private const val NOTIF_ID = 2001
        // 최근 N초 내 본 앵커만 유효. 1층 keybox처럼 약하고 드물게 잡히는 앵커가
        // 만료돼 판정 불가가 되지 않도록 20→30초로 완화.
        private const val ANCHOR_FRESH_MS = 30_000L

        // ── 층 판정 안정화(히스테리시스) 파라미터 ──
        // 배경(2026-07-21 로그): B1 keybox(DD:57…1B:64)가 B2 주차자리까지 -79~-92로 강하게
        // 새어들어와, "최근 창에서 더 강한 층" 단순 비교가 초 단위로 B1↔B2를 수십 번 뒤집었다
        // (아침엔 결국 B1로 오판정). RSSI만으로는 두 층을 못 가르므로:
        //  ① 앵커 "개수" 우선 — B2는 LC241 3대, B1은 keybox 1대뿐이라, 서로 다른 앵커가
        //     더 많이 잡힌 층을 택하면 단일 leak에 휘둘리지 않는다.
        //  ② 개수가 같을 때만 RSSI 우열로 판정하되, 근소차는 tie로 처리(마진 필요).
        //  ③ 후보 층이 SWITCH_SUSTAIN_MS 동안 지속돼야 실제 전환(플리커·단발 leak 방어).
        private const val RSSI_TIEBREAK_DB = 5             // 개수 동수 시 RSSI 우열 인정 최소차(dB)
        private const val SWITCH_SUSTAIN_MS = 10_000L      // 후보 층이 이만큼 유지돼야 전환
        // 판정 근거 진단 로그(층별 개수·RSSI) 과다 방지: 같은 상태 유지 로그는 1분에 1회.
        private const val DECISION_LOG_INTERVAL_MS = 60_000L

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
        //  ⑥ 주차 이벤트 집중 스캔: 차량 오디오 BT가 끊기는 순간 = 시동 끔 = 주차 완료.
        //     이때 화면이 꺼져 있으면 버스트 트리거(화면 켜짐)가 안 걸려 관측 기회가 0이다.
        //     (2026-09-10: 20:35:47 주차 → 앵커 0건 → 위젯이 7시간 전 값을 그대로 표시)
        //     주차 순간 90초 무필터 스캔 + 부분 웨이크락으로 확실한 관측 창을 만든다.
        private const val PARKING_BURST_MS = 90_000L
        /** onStartCommand로 전달되는 주차 이벤트 (서비스가 죽어 있었을 때 CarReceiver가 사용) */
        const val EXTRA_PARKING_EVENT = "parking_event"
        const val EXTRA_PARKING_LABEL = "parking_label"
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

    // 최근 본 앵커: MAC -> (평활 rssi, elapsedRealtime)
    private val recentAnchors = HashMap<String, Pair<Int, Long>>()

    // 히스테리시스: 현재 층과 다른 후보가 언제부터 우세했는지 추적(지속 시간 확인용)
    private var challengerFloor = 0
    private var challengerSince = 0L
    private var lastDecisionLogAt = 0L

    // 주차 이벤트 직후 "아직 이번 주차 건의 층을 모르는" 상태.
    // true인 동안 첫 근거가 잡히면 히스테리시스 없이 즉시 확정한다.
    private var parkingPending = false

    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false
    private var lastScanStartAt = 0L                    // 직전 필터 스캔 시작 시각(중복 재시작 억제)
    @Volatile private var lastAnchorSeen = 0L           // 마지막으로 앵커를 본 시각(elapsedRealtime)
    private var failBackoffMs = FAIL_BACKOFF_BASE_MS    // onScanFailed 재시작 백오프(가변)

    private var lastAnchorLogAt = 0L

    /** 필터 스캔/버스트 스캔 공용 앵커 처리. source는 진단 로그용("백그라운드"/"버스트"). */
    private fun onAnchorSeen(source: String, mac: String, rssi: Int) {
        if (!ParkingAnchors.passes(mac, rssi)) return  // 반대층 누설(약신호) 차단
        val now = SystemClock.elapsedRealtime()
        lastAnchorSeen = now
        failBackoffMs = FAIL_BACKOFF_BASE_MS              // 정상 수신 → 백오프 리셋
        // RSSI 노이즈로 층이 튀지 않게 EWMA 평활(직전 값과 반반). 만료됐으면 새 값으로 시작.
        val prev = recentAnchors[mac]
        val smoothed = if (prev != null && now - prev.second <= ANCHOR_FRESH_MS)
            (prev.first + rssi) / 2 else rssi
        recentAnchors[mac] = smoothed to now
        // 어느 경로가 앵커를 잡는지 파악하기 위한 레이트리밋 로그
        if (now - lastAnchorLogAt > ANCHOR_LOG_INTERVAL_MS) {
            lastAnchorLogAt = now
            MonitorLog.log(this, "[$source] 앵커 수신 ${mac.takeLast(8)} rssi=$rssi")
        }
        evaluateFloor()
    }

    private var lastUnknownLogAt = 0L

    /**
     * 앵커 이름인데 MAC이 미등록인 광고 감지 — 앵커 기기가 정전/재부팅으로 MAC이
     * 바뀌면 이름 필터는 통과하지만 콜백이 조용히 버려서 로그에 아무것도 안 남는
     * 사각지대가 생긴다. 여기서 새 MAC을 기록해두면 앵커 목록 갱신이 즉시 가능.
     */
    private fun checkRenamedAnchor(source: String, result: ScanResult) {
        val name = result.scanRecord?.deviceName ?: return
        if (name !in ParkingAnchors.ANCHOR_NAMES) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastUnknownLogAt < ANCHOR_LOG_INTERVAL_MS) return
        lastUnknownLogAt = now
        MonitorLog.log(this,
            "[$source] 앵커 이름인데 미등록 MAC — $name ${result.device.address} rssi=${result.rssi} (앵커 MAC 변경 의심)")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address.uppercase()
            if (ParkingAnchors.anchorFloor(mac) == 0) {
                checkRenamedAnchor("백그라운드", result)
                return
            }
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

    /**
     * 최근 본 앵커로 층을 판정해 위젯 갱신.
     *
     * 판정: ① 서로 다른 앵커 "개수"가 더 많은 층(단일 leak에 안 휘둘림) →
     *       ② 동수면 최강 RSSI 우열(근소차는 tie) →
     *       ③ 그래도 tie면 현재 층 유지.
     * 전환은 후보 층이 SWITCH_SUSTAIN_MS 지속돼야 확정(플리커·단발 leak 방어).
     */
    private fun evaluateFloor() {
        val now = SystemClock.elapsedRealtime()
        recentAnchors.entries.removeAll { now - it.value.second > ANCHOR_FRESH_MS }

        val f1 = recentAnchors.filterKeys { ParkingAnchors.anchorFloor(it) == 1 }.values
        val f2 = recentAnchors.filterKeys { ParkingAnchors.anchorFloor(it) == 2 }.values
        val f1Count = f1.size
        val f2Count = f2.size
        if (f1Count == 0 && f2Count == 0) return  // 판단 근거 없음 → 위젯 유지(수동 override 보존)

        // 위젯에 저장된 층이 서비스 메모리와 다르면 = 사용자가 위젯을 수동 탭한 것.
        // 그 값을 채택해, 자동 판정이 사용자의 최신 선택을 기준으로 이어가게 한다.
        // (안 하면 수동 B1 후 다음날 B2에서 "이미 2층"으로 오인해 위젯을 안 고침)
        val persisted = ParkingState.floor(this).takeIf { it != 0 } ?: lastFloor
        if (persisted != lastFloor) {
            lastFloor = persisted
            challengerFloor = 0
        }

        val f1Best = f1.maxOfOrNull { it.first }
        val f2Best = f2.maxOfOrNull { it.first }

        val candidate = when {
            f1Count > f2Count -> 1
            f2Count > f1Count -> 2
            else -> {  // 개수 동수(양쪽 모두 >0) → RSSI 우열, 근소차·동률은 현재 층 유지
                val b1 = f1Best ?: Int.MIN_VALUE
                val b2 = f2Best ?: Int.MIN_VALUE
                when {
                    b1 - b2 >= RSSI_TIEBREAK_DB -> 1
                    b2 - b1 >= RSSI_TIEBREAK_DB -> 2
                    lastFloor != 0 -> lastFloor
                    else -> if (b1 >= b2) 1 else 2
                }
            }
        }

        val basis = "B1:${f1Count}개/best=${f1Best ?: "-"} B2:${f2Count}개/best=${f2Best ?: "-"}"

        // 주차 직후 첫 근거: 히스테리시스 없이 즉시 확정한다.
        // 직전 층은 "다른 주차 건"의 값이므로 지킬 이유가 없고(전환에 10초를 쓸 이유도 없다),
        // 같은 층으로 재확정되더라도 "이번 주차 건으로 확인됨" 시각을 남겨야 한다.
        if (parkingPending) {
            parkingPending = false
            challengerFloor = 0
            commitFloor(candidate, now, "$basis 주차직후")
            return
        }

        // 최초 확정(아직 층 없음): 즉시 반영해 위젯을 빠르게 채운다.
        if (lastFloor == 0) {
            challengerFloor = 0
            commitFloor(candidate, now, basis)
            return
        }

        // 후보가 현재 층과 같으면 유지, 도전 리셋.
        if (candidate == lastFloor) {
            challengerFloor = 0
            logDecisionThrottled(now, "층 유지 지하${lastFloor}층 ($basis)")
            return
        }

        // 후보가 다르면 지속 시간을 확인해 전환 여부 결정(플리커 억제).
        if (challengerFloor != candidate) {
            challengerFloor = candidate
            challengerSince = now
        }
        if (now - challengerSince >= SWITCH_SUSTAIN_MS) {
            challengerFloor = 0
            commitFloor(candidate, now, basis)
        } else {
            logDecisionThrottled(now,
                "층 유지 지하${lastFloor}층 — 후보 지하${candidate}층 지속대기 ($basis)")
        }
    }

    private fun commitFloor(floor: Int, now: Long, basis: String) {
        lastFloor = floor
        lastUpdateTime = now
        MonitorLog.log(this, "층 판정 → 지하 ${floor}층 [$basis]")
        saveAndUpdateWidget(floor)
    }

    /** 유지/대기 상태 진단 로그 — 근소차·leak 상황을 나중에 분석할 수 있게 1분 1회만 남긴다. */
    private fun logDecisionThrottled(now: Long, msg: String) {
        if (now - lastDecisionLogAt < DECISION_LOG_INTERVAL_MS) return
        lastDecisionLogAt = now
        MonitorLog.log(this, msg)
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

    // ── 주차/출차 이벤트 (차량 오디오 BT 링크) ──
    // 차량 핸즈프리가 끊기는 순간 = 시동 끔 = 주차 완료. 로그상 이 시각 5~15초 뒤에
    // 앵커가 잡히는 게 정상이며(09-07/08/09), 여기서 관측을 못 하면 그날은 판정 자체가
    // 일어나지 않는다. 이 순간을 트리거로 삼아 강제 재스캔 + 집중 버스트를 건다.
    private val carReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = CarAudio.deviceOf(intent) ?: return
            if (!CarAudio.isCarAudio(device)) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> onParked(CarAudio.label(device))
                BluetoothDevice.ACTION_ACL_CONNECTED -> onDriveStarted(CarAudio.label(device))
            }
        }
    }

    /** 주차 완료 순간: 이번 주차 건을 "미확정"으로 초기화하고 집중 스캔을 건다. */
    private fun onParked(label: String) {
        MonitorLog.log(this, "주차 감지 (차량 BT 해제: $label) → 집중 스캔 ${PARKING_BURST_MS / 1000}초")
        ParkingState.markParked(this)
        // 직전 층은 다른 주차 건의 값이다. 근거를 비우고 첫 수신에 즉시 확정하도록 전환.
        parkingPending = true
        challengerFloor = 0
        recentAnchors.clear()
        // 강등·좀비 상태일 수 있는 필터 스캔부터 되살린다. 단 방금(onStartCommand 경로) 시작했으면
        // 건너뛴다 — 프레임워크가 30초에 5회 넘는 스캔 시작을 막기 때문에 낭비할 여유가 없다.
        if (SystemClock.elapsedRealtime() - lastScanStartAt > 3_000L) restartScan()
        startBurstScan("주차 감지", PARKING_BURST_MS, force = true, keepAwake = true)
        // 유예 시간이 지나도 확정이 없으면 위젯을 "미확인"으로 다시 그린다.
        handler.removeCallbacks(unconfirmedRunnable)
        handler.postDelayed(unconfirmedRunnable, ParkingState.UNCONFIRMED_GRACE_MS + 1_000L)
    }

    /** 출차: 운행 중엔 층 값이 의미 없으므로 미확인 표시를 내린다. */
    private fun onDriveStarted(label: String) {
        MonitorLog.log(this, "출차 감지 (차량 BT 연결: $label)")
        parkingPending = false
        handler.removeCallbacks(unconfirmedRunnable)
        ParkingState.clearParked(this)
        ParkingWidgetProvider.refreshAll(this)
    }

    private val unconfirmedRunnable = Runnable {
        if (ParkingState.isUnconfirmed(this)) {
            MonitorLog.log(this,
                "주차 후 ${ParkingState.UNCONFIRMED_GRACE_MS / 60_000}분간 앵커 미수신 → 위젯 '미확인' 표시")
            notify("주차층 미확인 — 위젯에서 직접 선택하세요")
        }
        ParkingWidgetProvider.refreshAll(this)
    }

    // ── 화면 켜짐 버스트 스캔 ──
    // 사용자가 폰을 꺼내 위젯을 확인하는 순간 = 수동 스캔이 항상 성공하던 조건(화면 온).
    // 무필터 스캔은 화면 꺼짐 상태에선 OS가 차단하지만 이 순간엔 허용된다.
    private var burstScanning = false
    private var lastBurstAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    // 버스트 세션 진단: 뭔가 수신은 됐는지(기기 종수), 그중 앵커는 몇 건인지
    private val burstSeenMacs = HashSet<String>()
    private var burstAnchorHits = 0

    private val burstCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address.uppercase()
            burstSeenMacs.add(mac)
            if (ParkingAnchors.anchorFloor(mac) == 0) {
                checkRenamedAnchor("버스트", result)
                return  // 소프트웨어 필터
            }
            burstAnchorHits++
            onAnchorSeen("버스트", mac, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            MonitorLog.log(this@BleMonitorService, "버스트 onScanFailed errorCode=$errorCode")
            burstScanning = false
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) startBurstScan("화면 켜짐")
        }
    }

    /**
     * 무필터 집중 스캔.
     *
     * @param force 최소 간격·진행 중 여부를 무시(주차 이벤트처럼 놓치면 안 되는 순간).
     *              이미 돌고 있으면 종료 시각만 연장한다.
     * @param keepAwake 화면이 꺼진 상태에서도 결과를 받도록 부분 웨이크락 유지.
     *              (주차 순간엔 폰이 주머니 속·화면 꺼짐이라 CPU가 다시 잠들 수 있다)
     */
    @SuppressLint("MissingPermission")
    private fun startBurstScan(
        reason: String,
        durationMs: Long = BURST_DURATION_MS,
        force: Boolean = false,
        keepAwake: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        if (!force && (burstScanning || now - lastBurstAt < BURST_MIN_INTERVAL_MS)) return
        if (burstScanning) {   // force + 이미 진행 중 → 시간만 연장
            if (keepAwake) acquireBurstWakeLock(durationMs)
            handler.removeCallbacks(stopBurstRunnable)
            handler.postDelayed(stopBurstRunnable, durationMs)
            MonitorLog.log(this, "버스트 스캔 연장 ($reason)")
            return
        }
        // 웨이크락은 스캔이 실제로 뜬 뒤에만 잡는다 (BT 꺼짐 등으로 못 뜨면 잡을 이유가 없다).
        val btAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = btAdapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, burstCallback)  // 무필터 = 수동 스캔과 동일
            if (keepAwake) acquireBurstWakeLock(durationMs)
            burstScanning = true
            lastBurstAt = now
            burstSeenMacs.clear()
            burstAnchorHits = 0
            MonitorLog.log(this, "버스트 스캔 시작 ($reason)")
            handler.removeCallbacks(stopBurstRunnable)
            handler.postDelayed(stopBurstRunnable, durationMs)
        } catch (e: Exception) {
            MonitorLog.log(this, "버스트 스캔 시작 실패: ${e.message}")
            releaseBurstWakeLock()
        }
    }

    /** 주차 순간용 부분 웨이크락 — 타임아웃을 걸어 어떤 경로로 새도 배터리를 못 먹게 한다. */
    private fun acquireBurstWakeLock(durationMs: Long) {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "parkingwidget:burst")
                .also { it.setReferenceCounted(false); wakeLock = it }
            wl.acquire(durationMs + 5_000L)
        } catch (_: Exception) {}
    }

    private fun releaseBurstWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
    }

    private val stopBurstRunnable = Runnable { stopBurstScan() }

    @SuppressLint("MissingPermission")
    private fun stopBurstScan() {
        releaseBurstWakeLock()
        if (!burstScanning) return
        burstScanning = false
        try {
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.bluetoothLeScanner?.stopScan(burstCallback)
        } catch (_: Exception) {}
        // 기기 0종 = 스캔이 결과를 아예 못 받음(좀비/차단), 다수 종 + 앵커 0건 = 앵커 부재/변경
        MonitorLog.log(this, "버스트 스캔 종료 (기기 ${burstSeenMacs.size}종, 앵커 ${burstAnchorHits}건)")
    }

    override fun onCreate() {
        super.onCreate()
        MonitorLog.log(this, "onCreate (프로세스 새로 시작)")
        createChannel()
        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        registerReceiver(carReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        })
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
        // 서비스가 죽어 있는 사이 주차가 일어난 경우(CarReceiver가 기동시킨 경로) 이어받기
        if (intent?.getBooleanExtra(EXTRA_PARKING_EVENT, false) == true) {
            onParked(intent.getStringExtra(EXTRA_PARKING_LABEL) ?: "차량")
        }
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
            lastScanStartAt = SystemClock.elapsedRealtime()
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
        // 판정 로그는 commitFloor에서 근거(개수·RSSI)와 함께 남긴다.
        // 확정 시각도 함께 기록된다 → 이번 주차 건이 확인됐다는 뜻이라 위젯의 '미확인' 표시가 풀린다.
        ParkingState.setFloor(this, floor)

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
        handler.removeCallbacks(unconfirmedRunnable)
        releaseBurstWakeLock()
        try { unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(carReceiver) } catch (_: Exception) {}
        // 워치독 알람은 여기서 취소하지 않는다 — 시스템/삼성이 서비스를 죽인 경우
        // 알람이 살아있어야 다음 발화 때 부활한다. 명시적 중지(위젯 제거)는
        // ParkingWidgetProvider.stopMonitor가 cancelWatchdog을 호출한다.
        ParkingWidgetProvider.refreshAll(this)  // 위젯을 비활성(회색) 상태로 전환
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
