package com.example.parkingwidget

import android.content.Context

/**
 * 위젯이 보여주는 층 값 + "그 값이 이번 주차 건으로 확인된 것인가" 상태 (단일 출처).
 *
 * 배경(2026-09-10 로그): 15:47 B1 주차가 정상 판정된 뒤, 20:35 B2 주차 때는 앵커가
 * 한 건도 안 잡혀 판정이 아예 실행되지 않았다(`evaluateFloor`는 근거 0건이면 return).
 * 그 결과 위젯은 7시간 전 다른 주차 건의 "지하1층"을 아무 표시 없이 확신에 차서
 * 계속 보여줬다. 값이 없다는 것과 값이 낡았다는 것을 구분할 수 없던 게 진짜 문제였다.
 *
 * → 주차 시각(차량 BT 해제)과 층 확정 시각을 각각 남겨, 확정이 주차보다 오래되면
 *   위젯이 "미확인"으로 렌더링하도록 한다.
 */
object ParkingState {

    private const val PREFS = "ParkingWidgetPrefs"
    private const val KEY_FLOOR = "SelectedFloor"
    private const val KEY_PARKED_AT = "ParkedAt"      // 마지막 주차 이벤트(차량 BT 해제) 시각
    private const val KEY_FIXED_AT = "FloorFixedAt"   // 마지막 층 확정 시각 (자동 판정 or 수동 탭)

    /**
     * 주차 직후 이만큼은 아직 "확인 중"으로 보고 기존 값을 그대로 보여준다.
     * 정상 동작 시 앵커는 주차 후 5~15초 내 잡히고(09-07/08/09 로그), 주차 버스트 스캔도
     * 90초면 끝나므로 3분이면 충분히 여유 있다.
     */
    const val UNCONFIRMED_GRACE_MS = 3 * 60_000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun floor(context: Context): Int = prefs(context).getInt(KEY_FLOOR, 0)

    fun parkedAt(context: Context): Long = prefs(context).getLong(KEY_PARKED_AT, 0L)

    fun fixedAt(context: Context): Long = prefs(context).getLong(KEY_FIXED_AT, 0L)

    /** 층 확정(자동 판정/수동 탭 공용) — 값과 확정 시각을 함께 남긴다. */
    fun setFloor(context: Context, floor: Int, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putInt(KEY_FLOOR, floor).putLong(KEY_FIXED_AT, at).apply()
    }

    /** 주차 이벤트(차량 BT 해제) 기록 — 이 시각 이후의 확정만 "이번 주차 건"으로 인정된다. */
    fun markParked(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_PARKED_AT, at).apply()
    }

    /** 출차(차량 BT 연결) — 운행 중엔 층 값이 의미 없으므로 미확인 표시를 내린다. */
    fun clearParked(context: Context) {
        prefs(context).edit().remove(KEY_PARKED_AT).apply()
    }

    /**
     * 화면에 뜬 층이 "이번 주차 건으로 확인된 값"이 아닌 상태.
     * 주차 이벤트 후 유예 시간이 지나도록 층 확정이 없었으면 true.
     */
    fun isUnconfirmed(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val parked = parkedAt(context)
        return parked > 0L && fixedAt(context) < parked && now - parked > UNCONFIRMED_GRACE_MS
    }
}
