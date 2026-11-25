package com.example.parkingwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PressureRecorderTest {

    private val parkAt = 1_000_000L

    /** 1Hz 샘플 생성: secBefore초 전 시각의 기압을 pressureAt(secBefore)로 */
    private fun samples(fromSecBefore: Int, pressureAt: (Int) -> Float) =
        (fromSecBefore downTo 0).map { sec -> (parkAt - sec * 1000L) to pressureAt(sec) }

    @Test
    fun descentShowsAsPositiveDelta() {
        // 150초 전까지 거리(1000.00), 그 뒤 60초에 걸쳐 0.40 상승한 뒤 주차
        val s = samples(200) { sec ->
            when {
                sec >= 90 -> 1000.00f
                sec <= 30 -> 1000.40f
                else -> 1000.00f + 0.40f * (90 - sec) / 60f
            }
        }
        val line = PressureRecorder.summarize(s, parkAt)
        assertTrue(line, line.contains("주차 1000.40hPa"))
        assertTrue(line, line.contains("Δ+0.40 hPa"))
        assertTrue(line, line.contains("120초 전 대비 +0.40"))
    }

    @Test
    fun ignoresSamplesAfterParkAndOutsideLookback() {
        val s = samples(400) { 1000.00f } + listOf((parkAt + 5_000L) to 1010f)
        val line = PressureRecorder.summarize(s, parkAt)
        assertTrue(line, line.contains("Δ+0.00 hPa"))
        assertTrue(line, line.contains("샘플 180개"))
    }

    @Test
    fun reportsStepAfterEngineOff() {
        // 주차까지 1000.50(송풍으로 가압), 시동 끈 뒤 3초는 과도 구간, 5~20초는 1000.38
        val before = samples(200) { 1000.50f }
        val after = (1..20).map { sec -> (parkAt + sec * 1000L) to (if (sec <= 3) 1000.45f else 1000.38f) }
        val line = PressureRecorder.summarize(before + after, parkAt)
        assertTrue(line, line.contains("시동 끈 뒤 5~20초 -0.12(15개)"))
        assertTrue(line, line.contains("Δ+0.00 hPa"))   // 주차 후 샘플은 Δ 계산에 안 섞인다
    }

    @Test
    fun predictsFloorFromDelta() {
        assertEquals("B1", PressureRecorder.predictFloor(0.00))
        assertEquals("B1", PressureRecorder.predictFloor(0.41))   // 지금까지 B1 최대
        assertEquals("B2", PressureRecorder.predictFloor(0.57))   // 지금까지 B2 최소
        assertEquals("B2", PressureRecorder.predictFloor(0.50))   // 기준값은 B2 쪽
        val s = samples(200) { sec -> if (sec >= 90) 1000.00f else 1000.60f }
        assertTrue(PressureRecorder.summarize(s, parkAt).contains("기압 예측 B2 (기준 Δ0.5, 판정엔 미사용)"))
    }

    @Test
    fun predictsFloorFromMinAge() {
        assertEquals("B1", PressureRecorder.predictFloorByMinAge(0))
        assertEquals("B1", PressureRecorder.predictFloorByMinAge(70))    // 사전 등록 때 B1 최대
        assertEquals("B1", PressureRecorder.predictFloorByMinAge(75))
        assertEquals("B2", PressureRecorder.predictFloorByMinAge(80))    // 기준값은 B2 쪽
        assertEquals("B2", PressureRecorder.predictFloorByMinAge(90))    // 사전 등록 때 B2 최소
        assertEquals("B2", PressureRecorder.predictFloorByMinAge(120))
    }

    @Test
    fun logsMinAgePredictionFromBottomTime() {
        // 최저점(1000.00)이 주차 60~64초 전에만 있으면 B1 쪽, 100~104초 전이면 B2 쪽
        fun lineWithBottomAt(sec0: Int) = PressureRecorder.summarize(
            samples(200) { sec -> if (sec in sec0..sec0 + 4) 1000.00f else 1000.40f }, parkAt)
        val b1 = lineWithBottomAt(60)
        assertTrue(b1, b1.contains("3분내 최저 1000.00(60초 전)"))
        assertTrue(b1, b1.contains("최저시점 예측 B1 (기준 80초, 판정엔 미사용)"))
        val b2 = lineWithBottomAt(100)
        assertTrue(b2, b2.contains("3분내 최저 1000.00(100초 전)"))
        assertTrue(b2, b2.contains("최저시점 예측 B2 (기준 80초, 판정엔 미사용)"))
    }

    @Test
    fun reportsMissingSamples() {
        assertEquals("기압 기록 — 샘플 없음 (운행 중 기록 안 됨)", PressureRecorder.summarize(emptyList(), parkAt))
        val old = samples(100) { 1000f }.filter { parkAt - it.first > 20_000 }
        assertTrue(PressureRecorder.summarize(old, parkAt).contains("주차 직전 15초 샘플 없음"))
    }
}
