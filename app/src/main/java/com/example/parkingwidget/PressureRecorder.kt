package com.example.parkingwidget

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Handler
import java.util.Locale

/**
 * 주차 순간의 기압 상승폭 기록 (1단계: 판정에는 안 쓰고 로그만).
 *
 * 배경(2026-11-05): B1은 고정 앵커가 사실상 없다(삼성 기기는 주민 차량, keybox는 간헐).
 * 지하 한 층 ≈ 3m ≈ 0.35~0.4 hPa라서, 같은 운행 안에서 "주차 직전 몇 분 사이에 기압이 얼마나 올랐나"를
 * 보면 BLE와 무관하게 층을 가를 수 있을 것으로 기대한다. 날씨에 따른 절대 기압 변화는 하루 수 hPa라
 * 절대값은 못 쓰고, 몇 분 단위 상대값만 쓴다. 앵커로 확정되는 주차가 많으니 로그가 곧 라벨 데이터가 된다.
 *
 * 센서 사정(SM-S928N 실측): 기압 센서는 non-wakeup뿐이고 FIFO 예약분이 300건(1Hz면 5분)이다.
 * 운행 중 폰이 잠들면 샘플은 FIFO에 쌓이기만 하고 전달되지 않으므로, 주차 순간 flush()로 끌어온 뒤
 * 이벤트 자체의 타임스탬프(elapsedRealtimeNanos 기준)로 시각을 맞춘다.
 */
class PressureRecorder(context: Context, private val handler: Handler) : SensorEventListener2 {

    companion object {
        private const val SAMPLING_US = 1_000_000          // 1Hz면 충분 (층 변화는 수십 초 단위)
        private const val MAX_LATENCY_US = 60_000_000      // 잠든 동안은 FIFO에 모아둔다
        private const val KEEP_MS = 6 * 60_000L            // 버퍼 보관 범위
        private const val FLUSH_TIMEOUT_MS = 3_000L        // flush 완료 콜백이 안 오면 이만큼 뒤 그냥 계산

        private const val BIN_MS = 5_000L                  // 노이즈 완화용 5초 평균 구간
        private const val LOOKBACK_MS = 180_000L           // "거리 높이"를 찾는 범위 (램프 진입 전)

        /**
         * 주차 시각 기준 요약 한 줄. 샘플은 (elapsedRealtime ms, hPa).
         *
         * 0번 구간(주차 직전 5초) 평균을 주차 기압으로, 최근 3분 구간 평균의 최저값을 "가장 높았던 지점"
         * (= 램프 진입 전 거리)으로 보고 그 차이를 낸다. 진입로 경사 등으로 기준이 애매할 수 있어
         * 60·120초 전 대비 값도 같이 남긴다 — 어느 지표가 층을 가장 잘 가르는지는 데이터로 고른다.
         */
        fun summarize(samples: List<Pair<Long, Float>>, parkAt: Long): String {
            val bins = samples
                .filter { it.first <= parkAt && parkAt - it.first < LOOKBACK_MS }
                .groupBy { ((parkAt - it.first) / BIN_MS).toInt() }
                .mapValues { (_, v) -> v.map { it.second.toDouble() }.average() }
            if (bins.isEmpty()) return "기압 기록 — 샘플 없음 (운행 중 기록 안 됨)"

            val parkBin = (0..2).firstOrNull { it in bins } ?: return "기압 기록 — 주차 직전 15초 샘플 없음 (${bins.size}구간)"
            val pPark = bins.getValue(parkBin)
            val minEntry = bins.minByOrNull { it.value }!!
            fun lag(sec: Int) = bins[(sec * 1000 / BIN_MS).toInt()]?.let { fmt(pPark - it) } ?: "-"

            val n = samples.count { it.first <= parkAt && parkAt - it.first < LOOKBACK_MS }
            return "기압 기록 — 주차 %.2fhPa, 3분내 최저 %.2f(%d초 전) → Δ%s hPa (60초 전 대비 %s, 120초 전 대비 %s), 샘플 %d개"
                .format(Locale.US, pPark, minEntry.value, minEntry.key * BIN_MS / 1000,
                    fmt(pPark - minEntry.value), lag(60), lag(120), n)
        }

        private fun fmt(d: Double) = String.format(Locale.US, "%+.2f", d)
    }

    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val samples = ArrayDeque<Pair<Long, Float>>()
    private var active = false
    private var pending: (() -> Unit)? = null

    val available: Boolean get() = sensor != null

    /** 운행 시작(차량 BT 연결) 때 호출. 이전 운행의 샘플은 버린다. */
    fun start() {
        val s = sensor ?: return
        if (active) stop()
        samples.clear()
        active = sm?.registerListener(this, s, SAMPLING_US, MAX_LATENCY_US, handler) == true
    }

    fun stop() {
        if (active) sm?.unregisterListener(this)
        active = false
        pending = null
        handler.removeCallbacks(timeoutRunnable)
    }

    /**
     * 주차 순간 호출. FIFO를 비워 잠든 동안의 샘플까지 받은 뒤 요약을 넘기고 기록을 멈춘다.
     * 운행 중 기록이 없었으면(서비스가 주차 때 새로 떴다든가) 그 사실을 넘긴다.
     */
    fun finishAt(parkAt: Long, onSummary: (String) -> Unit) {
        if (!active) { onSummary(summarize(emptyList(), parkAt)); return }
        pending = {
            val line = summarize(samples.toList(), parkAt)
            stop()
            onSummary(line)
        }
        if (sm?.flush(this) != true) complete()
        else handler.postDelayed(timeoutRunnable, FLUSH_TIMEOUT_MS)
    }

    private val timeoutRunnable = Runnable { complete() }

    private fun complete() {
        handler.removeCallbacks(timeoutRunnable)
        val p = pending ?: return
        pending = null
        p()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val t = event.timestamp / 1_000_000   // ns → ms, elapsedRealtime 기준
        samples.addLast(t to event.values[0])
        while (samples.isNotEmpty() && t - samples.first().first > KEEP_MS) samples.removeFirst()
    }

    override fun onFlushCompleted(sensor: Sensor) = complete()

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
