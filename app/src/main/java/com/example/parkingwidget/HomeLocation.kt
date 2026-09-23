package com.example.parkingwidget

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * "이번 주차가 우리 집 차고인가" 판별 (단일 출처).
 *
 * 배경(2026-09-24, 주차 이벤트 25건 집계): 미확인으로 끝난 19건 중 18건은 주차 전후
 * 5분 안에 앵커가 한 번도 안 잡힌 건이었다 — 회사·외출 주차다. 집 차고와 무관한 주차마다
 * 위젯에 '?'가 뜨고, 90초짜리 고출력 버스트 스캔 + 웨이크락이 헛돌았다.
 *
 * 그래서 주차 순간 마지막 위치를 보고 집에서 멀면 둘 다 건너뛴다. 새 권한은 필요 없다
 * (BLE 스캔 때문에 이미 위치 "항상 허용" 상태). 능동 측위는 하지 않고
 * `getLastKnownLocation`만 읽으므로 배터리 비용도 없다.
 *
 * 판단이 불가능하면(집 좌표 미학습·마지막 위치 없음·너무 오래됨) **기존 동작 그대로**
 * 처리한다 — 위치를 모른다는 이유로 미확인 경고를 잃으면 안 되기 때문(fail-open).
 */
object HomeLocation {

    private const val PREFS = "HomeLocation"
    private const val KEY_LAT = "HomeLatBits"
    private const val KEY_LON = "HomeLonBits"

    /**
     * 이 거리를 넘으면 외부 주차로 본다.
     *
     * 넉넉히 잡는 이유: 지하로 들어오면 측위가 멈춰서, 집 좌표로 학습되는 값이 실제로는
     * "집 근처를 달리던 중의 위치"일 수 있다(실측: 마지막 fused 고정 오차 hAcc=100m).
     * 오차 방향도 비대칭이다 — 집인데 외부로 오판하면 미확인 경고를 잃지만(나쁨),
     * 외부인데 집으로 오판하면 예전처럼 스캔 한 번 더 돌 뿐이다(무해).
     */
    private const val HOME_RADIUS_M = 400f

    /** 지하로 들어오면 측위가 멈추므로 어느 정도 묵은 값은 허용하되, 지난 외출 위치는 배제 */
    private const val MAX_FIX_AGE_MS = 30 * 60_000L
    private const val MAX_ACCURACY_M = 200f

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 앵커로 층이 확정된 순간 = 집 차고 안이 확실한 순간. 그때의 마지막 측위를 집 좌표로 갱신한다.
     * (사용자가 좌표를 직접 넣을 필요가 없고, 이사·주차장 변경에도 자동으로 따라간다)
     */
    fun learn(context: Context) {
        val fix = lastFix(context) ?: return
        prefs(context).edit()
            .putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(fix.latitude))
            .putLong(KEY_LON, java.lang.Double.doubleToRawLongBits(fix.longitude))
            .apply()
    }

    /** 집 좌표까지의 거리(m). 집 좌표 미학습이거나 쓸 만한 마지막 위치가 없으면 null. */
    fun distanceFromHome(context: Context): Float? {
        val p = prefs(context)
        if (!p.contains(KEY_LAT) || !p.contains(KEY_LON)) return null
        val fix = lastFix(context) ?: return null
        val out = FloatArray(1)
        Location.distanceBetween(
            java.lang.Double.longBitsToDouble(p.getLong(KEY_LAT, 0)),
            java.lang.Double.longBitsToDouble(p.getLong(KEY_LON, 0)),
            fix.latitude, fix.longitude, out
        )
        return out[0]
    }

    /** 외부 주차로 판단되면 집까지의 거리(m), 아니면 null (= 집 차고이거나 판단 불가). */
    fun awayDistanceOrNull(context: Context): Float? =
        distanceFromHome(context)?.takeIf { it > HOME_RADIUS_M }

    /** 앱 상태창용 한 줄 */
    fun describe(context: Context): String {
        val p = prefs(context)
        if (!p.contains(KEY_LAT)) return "ℹ 집 좌표: 미학습 (앵커로 층이 확정되면 자동 저장)"
        val d = distanceFromHome(context)
            ?: return "ℹ 집 좌표: 저장됨 (현재 위치 불명 — 외부 주차 판별 일시 중지)"
        return if (d > HOME_RADIUS_M) "ℹ 집 좌표: 저장됨 (현재 ${d.toInt()}m — 외부로 판별)"
        else "ℹ 집 좌표: 저장됨 (현재 ${d.toInt()}m — 집 범위)"
    }

    /** 능동 측위 없이, 각 공급자의 마지막 위치 중 가장 최근이면서 쓸 만한 것 */
    @SuppressLint("MissingPermission")
    private fun lastFix(context: Context): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val now = System.currentTimeMillis()
        return try {
            lm.getProviders(true)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { now - it.time in 0..MAX_FIX_AGE_MS }
                .filter { !it.hasAccuracy() || it.accuracy <= MAX_ACCURACY_M }
                .maxByOrNull { it.time }
        } catch (_: SecurityException) { null }
    }
}
