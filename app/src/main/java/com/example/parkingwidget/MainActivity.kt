package com.example.parkingwidget

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 디버그 콘솔용 액티비티.
 * "스캔" 버튼을 누르면 주변 BLE 기기를 능동 스캔하여
 *  - 1층/2층 타깃 비콘 감지 여부 + RSSI 를 상단에 표시
 *  - 주변 모든 기기(MAC/이름/RSSI/제조사데이터)를 RSSI 순으로 표시
 *  - 동일 내용을 로그 파일로 저장(외출 후 회수용)
 */
class MainActivity : Activity() {

    companion object {
        private const val SCAN_DURATION_MS = 12_000L
        private const val REQ_CODE = 100
        private const val REQ_CODE_BG_LOCATION = 101

        private const val APPLE_COMPANY_ID = 0x004C
    }

    private lateinit var scanF1Button: Button
    private lateinit var scanF2Button: Button
    private lateinit var monitorButton: Button
    private lateinit var statusText: TextView
    private lateinit var targetText: TextView
    private lateinit var resultsText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false

    // 이번 스캔을 어느 층에서 눌렀는지 (사용자 지정 정답)
    private var scanFloor = 0
    private val scanFloorLabel: String get() = if (scanFloor == 1) "1층" else "2층"

    // MAC -> 최신 스캔 정보
    private val found = LinkedHashMap<String, DeviceInfo>()

    private data class DeviceInfo(
        val mac: String,
        var name: String?,
        var rssi: Int,
        var connectable: Boolean,
        var manufacturer: String?,
        var serviceUuids: String?,
        var ibeaconUuid: String?,   // iBeacon UUID (소문자), 없으면 null
        var ibeaconMajor: Int,
        var ibeaconMinor: Int,
        var hits: Int,
        var lastSeen: Long
    )

    /** 0x004C Apple iBeacon 페이로드 파싱 → Triple(uuid, major, minor) 또는 null */
    private fun parseIBeacon(appleData: ByteArray?): Triple<String, Int, Int>? {
        if (appleData == null || appleData.size < 23) return null
        if ((appleData[0].toInt() and 0xFF) != 0x02 || (appleData[1].toInt() and 0xFF) != 0x15) return null
        val hex = appleData.joinToString("") { "%02x".format(it) }
        // hex[0..3]=0215, [4..35]=UUID(16B), [36..39]=major, [40..43]=minor, [44..45]=tx
        val u = hex.substring(4, 36)
        val uuid = "${u.substring(0,8)}-${u.substring(8,12)}-${u.substring(12,16)}-${u.substring(16,20)}-${u.substring(20,32)}"
        val major = hex.substring(36, 40).toInt(16)
        val minor = hex.substring(40, 44).toInt(16)
        return Triple(uuid, major, minor)
    }

    private fun isDoorBeacon(uuid: String?): Boolean =
        uuid?.lowercase() == ParkingAnchors.DOOR_BEACON_UUID

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        renderTargets()
    }

    override fun onResume() {
        super.onResume()
        // 스캔 중이 아니면, 앱 진입/복귀 시마다 실제 상태(서비스 가동·배터리 예외)를 다시 읽어 표시.
        // 배터리 최적화 예외 다이얼로그를 허용하고 돌아온 경우에도 여기서 최신 상태가 반영된다.
        if (!scanning) renderMonitorStatus()
    }

    /**
     * 백그라운드 위치("항상 허용") 부여 여부.
     * BLE 스캔 결과는 위치 정보로 취급되어, 이게 없으면 앱이 화면에 없을 때
     * 스캔 결과가 에러 없이 조용히 차단된다 — 백그라운드 감지의 필수 조건.
     */
    private fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /** 백그라운드 서비스 가동 여부 + 배터리 최적화 예외 + 백그라운드 위치를 상태창에 표시 */
    private fun renderMonitorStatus() {
        val running = BleMonitorService.isRunning
        val exempt = !isBatteryOptimized()
        val bgLoc = hasBackgroundLocation()

        val sb = StringBuilder()
        sb.append(if (running) "✅ 백그라운드 자동감지: 실행 중" else "⛔ 백그라운드 자동감지: 꺼짐 (아래 버튼으로 켜세요)")
        sb.append("\n")
        sb.append(if (exempt) "✅ 배터리 최적화 예외 적용됨"
                  else "⚠ 배터리 최적화 예외 미적용 — 백그라운드가 꺼질 수 있습니다.")
        sb.append("\n")
        sb.append(if (bgLoc) "✅ 위치 항상 허용 적용됨"
                  else "⛔ 위치가 \"항상 허용\"이 아님 — 백그라운드에서 앵커 수신이 차단됩니다!")
        sb.append("\n")
        sb.append(parkingStateLine())
        renderStatus(sb.toString())

        if (::monitorButton.isInitialized) {
            monitorButton.text = if (running) "백그라운드 자동감지 재시작" else "백그라운드 자동감지 켜기"
        }
    }

    /**
     * 주차 이벤트(차량 BT 해제) 대비 층 확정 시각 — 위젯이 왜 그 값을 보여주는지 한 줄로 설명한다.
     * "주차는 감지됐는데 확정이 없다"가 곧 앵커 커버리지 구멍의 신호다.
     */
    private fun parkingStateLine(): String {
        val hhmm = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.KOREA)
        val parked = ParkingState.parkedAt(this)
        val fixed = ParkingState.fixedAt(this)
        val floor = ParkingState.floor(this)
        val floorTxt = if (floor == 0) "없음" else "지하${floor}층"
        return when {
            parked == 0L && fixed == 0L -> "ℹ 표시 층: $floorTxt (주차 이벤트 기록 없음)"
            ParkingState.isUnconfirmed(this) ->
                "⚠ 표시 층: $floorTxt — 미확인! ${hhmm.format(parked)} 주차 감지 후 앵커 미수신 " +
                    "(마지막 확정 ${if (fixed == 0L) "없음" else hhmm.format(fixed)})"
            parked > 0L && fixed >= parked ->
                "✅ 표시 층: $floorTxt — ${hhmm.format(parked)} 주차 건으로 확정 (${hhmm.format(fixed)})"
            parked > 0L ->
                "⏳ 표시 층: $floorTxt — ${hhmm.format(parked)} 주차 감지, 확인 중"
            else -> "ℹ 표시 층: $floorTxt (마지막 확정 ${hhmm.format(fixed)}, 운행 중)"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = "주차 비콘 디버거"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)

        val scanRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rowLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        scanF1Button = Button(this).apply {
            text = "1층 스캔 (12초)"
            setOnClickListener { onScanClicked(1) }
        }
        scanF2Button = Button(this).apply {
            text = "2층 스캔 (12초)"
            setOnClickListener { onScanClicked(2) }
        }
        scanRow.addView(scanF1Button, rowLp)
        scanRow.addView(scanF2Button, rowLp)
        root.addView(scanRow)

        monitorButton = Button(this).apply {
            text = "백그라운드 자동감지 켜기"
            setOnClickListener {
                ParkingWidgetProvider.startMonitor(this@MainActivity)
                if (!hasBackgroundLocation()) {
                    // 최우선: 이게 없으면 백그라운드 스캔 결과가 통째로 차단된다.
                    // Android 11+에선 시스템이 다이얼로그 대신 설정 화면으로 보낸다 →
                    // 사용자가 "항상 허용" 선택 후 복귀하면 onResume이 상태를 갱신.
                    renderStatus("백그라운드 자동감지 서비스를 시작했습니다.\n⛔ 위치 권한을 \"항상 허용\"으로 바꿔주세요 — 안 하면 주머니 속에서 앵커 수신이 차단됩니다.")
                    ActivityCompat.requestPermissions(this@MainActivity,
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_CODE_BG_LOCATION)
                } else if (isBatteryOptimized()) {
                    // 시스템 예외 허용 다이얼로그 표시. 허용 후 앱으로 돌아오면 onResume 이
                    // 실제 상태를 다시 읽어 "✅ 배터리 최적화 예외 적용됨" 을 표시한다.
                    renderStatus("백그라운드 자동감지 서비스를 시작했습니다.\n⚠ 배터리 최적화 예외를 허용해주세요 (백그라운드 생존).")
                    requestBatteryExemption()
                } else {
                    // 이미 예외 상태 → 즉시 최신 상태 표시
                    renderMonitorStatus()
                }
            }
        }
        root.addView(monitorButton)

        statusText = TextView(this).apply {
            setPadding(0, dp(8), 0, dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        root.addView(statusText)

        targetText = TextView(this).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.parseColor("#11000000"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
        }
        root.addView(targetText)

        val sectionLabel = TextView(this).apply {
            text = "── 주변 기기 (RSSI 높은 순) ──"
            setPadding(0, dp(12), 0, dp(4))
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(sectionLabel)

        resultsText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(resultsText)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    // ------------------------------------------------------------- 스캔

    private fun onScanClicked(userFloor: Int) {
        if (scanning) return
        scanFloor = userFloor
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            renderStatus("권한 요청 중… 허용 후 다시 ${scanFloorLabel} 스캔을 눌러주세요.")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_CODE)
            return
        }
        startScan()
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN,
                   Manifest.permission.BLUETOOTH_CONNECT,
                   Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** 배터리 최적화 대상이면 true (= 백그라운드가 죽을 수 있음) */
    private fun isBatteryOptimized(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val pm = getSystemService(PowerManager::class.java) ?: return false
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** 배터리 최적화 예외 요청 시스템 다이얼로그 */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val btAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (btAdapter == null) {
            renderStatus("⚠ 이 기기는 블루투스를 지원하지 않습니다.")
            return
        }
        if (!btAdapter.isEnabled) {
            renderStatus("⚠ 블루투스가 꺼져 있습니다. 켜고 다시 시도하세요.")
            return
        }
        val scanner = btAdapter.bluetoothLeScanner
        if (scanner == null) {
            renderStatus("⚠ BLE 스캐너를 사용할 수 없습니다.")
            return
        }

        found.clear()
        scanning = true
        scanF1Button.isEnabled = false
        scanF2Button.isEnabled = false
        if (scanFloor == 1) scanF1Button.text = "1층 스캔 중…" else scanF2Button.text = "2층 스캔 중…"

        // 디버깅 중에는 가장 공격적인 모드로 최대한 잡아낸다 (필터 없음 = 전체 노출)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)

        renderStatus("[${scanFloorLabel}] 스캔 중… (12초)\n블루투스: ON / 스캐너: OK")

        // 진행 중 실시간 갱신
        val ticker = object : Runnable {
            override fun run() {
                if (!scanning) return
                renderResults()
                renderTargets()
                handler.postDelayed(this, 600)
            }
        }
        handler.post(ticker)

        handler.postDelayed({ stopScan(scanner) }, SCAN_DURATION_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(scanner: android.bluetooth.le.BluetoothLeScanner) {
        if (!scanning) return
        scanning = false
        try { scanner.stopScan(scanCallback) } catch (_: Exception) {}
        scanF1Button.isEnabled = true
        scanF2Button.isEnabled = true
        scanF1Button.text = "1층 스캔 (12초)"
        scanF2Button.text = "2층 스캔 (12초)"

        renderResults()
        renderTargets()
        val path = saveLog()
        renderStatus("[${scanFloorLabel}] 스캔 완료. 기기 ${found.size}개 발견.\n로그 저장됨:\n$path")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address.uppercase()
            val rec = result.scanRecord
            val manufacturer = rec?.manufacturerSpecificData?.let { sparse ->
                if (sparse.size() == 0) null
                else buildString {
                    for (i in 0 until sparse.size()) {
                        if (i > 0) append(", ")
                        append("0x${Integer.toHexString(sparse.keyAt(i))}:")
                        append(sparse.valueAt(i).joinToString("") { "%02X".format(it) })
                    }
                }
            }
            val uuids = rec?.serviceUuids?.joinToString(",") { it.uuid.toString().take(8) }
            val name = result.device.let {
                try { it.name } catch (_: SecurityException) { null }
            } ?: rec?.deviceName

            val ib = parseIBeacon(rec?.getManufacturerSpecificData(APPLE_COMPANY_ID))

            val info = found[mac]
            if (info == null) {
                found[mac] = DeviceInfo(
                    mac = mac, name = name, rssi = result.rssi,
                    connectable = isConnectable(result),
                    manufacturer = manufacturer, serviceUuids = uuids,
                    ibeaconUuid = ib?.first, ibeaconMajor = ib?.second ?: 0, ibeaconMinor = ib?.third ?: 0,
                    hits = 1, lastSeen = System.currentTimeMillis())
            } else {
                info.rssi = result.rssi
                if (name != null) info.name = name
                if (manufacturer != null) info.manufacturer = manufacturer
                if (uuids != null) info.serviceUuids = uuids
                if (ib != null) { info.ibeaconUuid = ib.first; info.ibeaconMajor = ib.second; info.ibeaconMinor = ib.third }
                info.hits++
                info.lastSeen = System.currentTimeMillis()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post { renderStatus("⚠ 스캔 실패 errorCode=$errorCode") }
        }
    }

    private fun isConnectable(result: ScanResult): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.isConnectable else true

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            renderStatus(if (granted) "권한 허용됨. 스캔 버튼을 눌러주세요." else "권한이 거부되었습니다. 설정에서 허용해야 스캔할 수 있습니다.")
        }
        if (requestCode == REQ_CODE_BG_LOCATION && !hasBackgroundLocation()) {
            // 이전 거부 이력 등으로 시스템이 요청을 조용히 무시한 경우 → 앱 설정으로 직접 유도
            renderStatus("설정 → 권한 → 위치에서 \"항상 허용\"을 선택해주세요.")
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------- 렌더링

    private fun renderStatus(msg: String) {
        statusText.text = msg
    }

    /** 해당 층 앵커 중 가장 강한 것 (누설 문턱 통과분만 — 서비스 판정과 동일 기준) */
    private fun bestAnchor(floor: Int): DeviceInfo? =
        found.values.filter {
            ParkingAnchors.anchorFloor(it.mac) == floor && ParkingAnchors.passes(it.mac, it.rssi)
        }.maxByOrNull { it.rssi }

    /** 가장 강한 도어 비콘 (참고용) */
    private fun bestDoorBeacon(): DeviceInfo? =
        found.values.filter { isDoorBeacon(it.ibeaconUuid) }.maxByOrNull { it.rssi }

    private fun renderTargets() {
        val sb = StringBuilder("[ 층 판정 (앵커 기준) ]\n")
        for (floor in listOf(1, 2)) {
            val anchors = found.values.filter { ParkingAnchors.anchorFloor(it.mac) == floor }
                .sortedByDescending { it.rssi }
            if (anchors.isNotEmpty()) {
                val detail = anchors.joinToString(", ") { "${it.mac.takeLast(8)} ${it.rssi}" }
                sb.append("✅ ${floor}층 앵커: $detail\n")
            } else {
                sb.append("❌ ${floor}층 앵커: 없음\n")
            }
        }
        val a1 = bestAnchor(1); val a2 = bestAnchor(2)
        val verdict = when {
            a1 == null && a2 == null -> "→ 판정: ❓ 앵커 없음 (판정 불가)"
            a2 == null || (a1 != null && a1.rssi >= a2.rssi) -> "→ 판정: 🅿 1층"
            else -> "→ 판정: 🅿 2층"
        }
        sb.append(verdict)
        // 도어 비콘은 참고로만
        bestDoorBeacon()?.let { sb.append("\n(참고) 도어비콘 ${it.rssi}dBm MAC ${it.mac}") }
        targetText.text = sb.toString().trimEnd()
    }

    private fun renderResults() {
        if (found.isEmpty()) {
            resultsText.text = if (scanning) "탐지 중…" else "발견된 기기 없음."
            return
        }
        val sorted = found.values.sortedByDescending { it.rssi }
        val sb = StringBuilder()
        for (d in sorted) {
            val fl = ParkingAnchors.anchorFloor(d.mac)
            val tag = when {
                fl != 0 -> "[${fl}층앵커] "
                isDoorBeacon(d.ibeaconUuid) -> "[도어비콘] "
                else -> ""
            }
            sb.append("$tag${d.mac}  ${d.rssi}dBm  x${d.hits}\n")
            sb.append("    이름: ${d.name ?: "-"}  연결가능: ${if (d.connectable) "Y" else "N"}\n")
            if (d.ibeaconUuid != null) sb.append("    iBeacon: ${d.ibeaconUuid}  ${d.ibeaconMajor}/${d.ibeaconMinor}\n")
            if (d.serviceUuids != null) sb.append("    svc: ${d.serviceUuids}\n")
            if (d.manufacturer != null) sb.append("    mfg: ${d.manufacturer}\n")
        }
        resultsText.text = sb.toString().trimEnd()
    }

    // --------------------------------------------------------- 로그 저장

    private fun saveLog(): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(System.currentTimeMillis())
        val sb = StringBuilder()
        sb.append("============ BLE 스캔 로그 [사용자: ${scanFloorLabel}] ============\n")
        sb.append("시각: $ts\n")
        sb.append(">>> 내가 실제로 서 있는 층: ${scanFloorLabel} <<<\n")
        sb.append("기기 모델: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("발견 기기 수: ${found.size}\n\n")

        sb.append("[ 층 판정 (앵커 기준) ]\n")
        for (floor in listOf(1, 2)) {
            val anchors = found.values.filter { ParkingAnchors.anchorFloor(it.mac) == floor }
                .sortedByDescending { it.rssi }
            sb.append(if (anchors.isNotEmpty())
                "  ${floor}층 앵커: " + anchors.joinToString(", ") { "${it.mac}=${it.rssi}" } + "\n"
            else
                "  ${floor}층 앵커: 없음\n")
        }
        val a1 = bestAnchor(1); val a2 = bestAnchor(2)
        val verdict = when {
            a1 == null && a2 == null -> "판정불가(앵커없음)"
            a2 == null || (a1 != null && a1.rssi >= a2.rssi) -> "1층"
            else -> "2층"
        }
        sb.append("  → 판정: $verdict (정답: $scanFloorLabel)\n")
        bestDoorBeacon()?.let { sb.append("  도어비콘: ${it.rssi}dBm MAC=${it.mac} uuid=${it.ibeaconUuid}\n") }

        sb.append("\n[ 전체 기기 (RSSI 순) ]\n")
        for (d in found.values.sortedByDescending { it.rssi }) {
            val fl = ParkingAnchors.anchorFloor(d.mac)
            val tag = when {
                fl != 0 -> "[${fl}층앵커] "
                isDoorBeacon(d.ibeaconUuid) -> "[도어비콘] "
                else -> ""
            }
            sb.append("  $tag${d.mac} rssi=${d.rssi} hits=${d.hits} conn=${d.connectable} ")
            sb.append("name=${d.name ?: "-"} ")
            if (d.ibeaconUuid != null) sb.append("iBeacon=${d.ibeaconUuid}(${d.ibeaconMajor}/${d.ibeaconMinor}) ")
            sb.append("svc=${d.serviceUuids ?: "-"} mfg=${d.manufacturer ?: "-"}\n")
        }
        sb.append("\n")

        val dir = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, "scan_log.txt")
        return try {
            file.appendText(sb.toString())
            // 화면 표시는 짧게, 회수는 adb pull 또는 파일앱
            file.absolutePath
        } catch (e: Exception) {
            "저장 실패: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (scanning) {
            val scanPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
            if (ContextCompat.checkSelfPermission(this, scanPerm) == PackageManager.PERMISSION_GRANTED) {
                try {
                    (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager)
                        .adapter?.bluetoothLeScanner?.stopScan(scanCallback)
                } catch (_: Exception) {}
            }
            scanning = false
        }
    }
}
