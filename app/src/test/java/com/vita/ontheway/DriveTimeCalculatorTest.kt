package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test

class DriveTimeCalculatorTest {

    private val WINDOW_MS = 5 * 60 * 1000L // 5분

    // ── 기존 v1 테스트 (시간가중 평균 반영) ──

    @Test
    fun `빈 포인트 리스트 → 0 반환`() {
        val result = DriveTimeCalculator.calculateFromPoints(
            emptyList(), 0L, 10 * 60_000L
        )
        assertEquals(0L, result)
    }

    @Test
    fun `startTs가 endTs 이상이면 0 반환`() {
        val points = listOf(100L to 30.0)
        assertEquals(0L, DriveTimeCalculator.calculateFromPoints(points, 100L, 100L))
        assertEquals(0L, DriveTimeCalculator.calculateFromPoints(points, 200L, 100L))
    }

    @Test
    fun `전체 구간 이동 중 → 전체 시간 반환`() {
        val start = 0L
        val end = 15 * 60_000L
        val points = mutableListOf<Pair<Long, Double>>()
        for (i in 0 until 15) {
            points.add((i * 60_000L) to 30.0)
        }
        val result = DriveTimeCalculator.calculateFromPoints(points, start, end)
        assertEquals(end, result)
    }

    @Test
    fun `전체 구간 정지 → 0 반환`() {
        val start = 0L
        val end = 10 * 60_000L
        val points = mutableListOf<Pair<Long, Double>>()
        for (i in 0 until 10) {
            points.add((i * 60_000L) to 0.5)
        }
        val result = DriveTimeCalculator.calculateFromPoints(points, start, end)
        assertEquals(0L, result)
    }

    @Test
    fun `일부 윈도우만 이동 → 해당 윈도우 시간만`() {
        val start = 0L
        val end = 15 * 60_000L

        val points = mutableListOf<Pair<Long, Double>>()
        for (i in 0 until 5) { points.add((i * 60_000L) to 30.0) }
        for (i in 5 until 10) { points.add((i * 60_000L) to 0.3) }
        for (i in 10 until 15) { points.add((i * 60_000L) to 20.0) }

        val result = DriveTimeCalculator.calculateFromPoints(points, start, end)
        assertEquals(2 * WINDOW_MS, result)
    }

    @Test
    fun `speed null → 0 처리 (avg below threshold)`() {
        val start = 0L
        val end = WINDOW_MS
        val points = listOf(
            (1 * 60_000L) to 0.0,
            (2 * 60_000L) to 0.0,
            (3 * 60_000L) to 0.0
        )
        val result = DriveTimeCalculator.calculateFromPoints(points, start, end)
        assertEquals(0L, result)
    }

    @Test
    fun `윈도우 1개만 부분 카운트`() {
        val start = 0L
        val end = 3 * 60_000L
        val points = listOf(
            (1 * 60_000L) to 50.0,
            (2 * 60_000L) to 40.0
        )
        val result = DriveTimeCalculator.calculateFromPoints(points, start, end)
        assertEquals(3 * 60_000L, result)
    }

    @Test
    fun `경계값 speed = 1_0 → 카운트 안됨`() {
        val start = 0L
        val end = WINDOW_MS
        val points = listOf(
            (1 * 60_000L) to 1.0,
            (2 * 60_000L) to 1.0
        )
        val result = DriveTimeCalculator.calculateFromPoints(points, start, end)
        assertEquals(0L, result)
    }

    @Test
    fun `경계값 speed = 1_1 → 카운트됨`() {
        val start = 0L
        val end = WINDOW_MS
        val points = listOf(
            (1 * 60_000L) to 1.1,
            (2 * 60_000L) to 1.1
        )
        val result = DriveTimeCalculator.calculateFromPoints(points, start, end)
        assertEquals(WINDOW_MS, result)
    }

    @Test
    fun `107분 시나리오 (5-11 데이터 시뮬레이션)`() {
        val start = 0L
        val end = 107 * 60_000L

        val points = mutableListOf<Pair<Long, Double>>()
        for (i in 0 until 107) {
            points.add((i * 60_000L) to 25.0)
        }

        val result = DriveTimeCalculator.calculateFromPoints(points, start, end)
        assertEquals(107 * 60_000L, result)
    }

    @Test
    fun `가게 대기 시간 제외 시나리오`() {
        val start = 0L
        val end = 30 * 60_000L

        val points = mutableListOf<Pair<Long, Double>>()
        for (i in 0 until 10) { points.add((i * 60_000L) to 30.0) }
        for (i in 10 until 20) { points.add((i * 60_000L) to 0.2) }
        for (i in 20 until 30) { points.add((i * 60_000L) to 25.0) }

        val result = DriveTimeCalculator.calculateFromPoints(points, start, end)
        assertEquals(4 * WINDOW_MS, result)
    }

    // ── v1.1 보강 테스트 ──

    @Test
    fun `gap 120초 초과 → unknown 카운트`() {
        val start = 0L
        val end = WINDOW_MS
        // 0분에 포인트 1개, 3분에 포인트 1개 (gap = 180초 > 120초)
        val points = listOf(
            0L to 30.0,
            (3 * 60_000L) to 30.0
        )
        val breakdown = DriveTimeCalculator.calculateBreakdownFromPoints(points, start, end)
        assertEquals(0L, breakdown.drivingMs)
        assertEquals(WINDOW_MS, breakdown.unknownMs)
    }

    @Test
    fun `시간가중 평균 - 짧은 고속 + 긴 정지 = 운행 아님`() {
        // 30초짜리 80km/h + 4분30초짜리 0km/h
        // 시간가중: (80*30000 + 0*270000) / 300000 = 8.0 → 운행
        // 하지만 실제 의도: 더 극단적으로 테스트
        // 10초짜리 80km/h + 4분50초짜리 0km/h
        // 시간가중: (80*10000 + 0*290000) / 300000 = 2.67 → 운행
        // 5초짜리 80km/h + 4분55초짜리 0km/h
        // 시간가중: (80*5000 + 0*295000) / 300000 = 1.33 → 운행
        // 3초짜리 80km/h + 나머지 0km/h
        // 시간가중: (80*3000 + 0*297000) / 300000 = 0.8 → 정지!
        val start = 0L
        val end = WINDOW_MS // 5분 = 300,000ms
        val points = listOf(
            0L to 80.0,           // t=0, 3초 동안 80km/h
            3_000L to 0.0         // t=3초 ~ 5분, 0km/h
        )
        // weighted = (80 * 3000 + 0 * 297000) / 300000 = 0.8 < 1.0
        val breakdown = DriveTimeCalculator.calculateBreakdownFromPoints(points, start, end)
        assertEquals(0L, breakdown.drivingMs)
        assertEquals(WINDOW_MS, breakdown.stationaryMs)
    }

    @Test
    fun `unknown 시간 분리 합계 확인`() {
        val start = 0L
        val end = 15 * 60_000L // 3 윈도우

        val points = mutableListOf<Pair<Long, Double>>()
        // 윈도우 0-5: 이동 (매 30초 포인트)
        for (i in 0 until 10) { points.add((i * 30_000L) to 40.0) }
        // 윈도우 5-10: 포인트 없음 → unknown
        // 윈도우 10-15: 정지 (매 30초 포인트)
        for (i in 0 until 10) { points.add(((10 * 60_000L) + i * 30_000L) to 0.3) }

        val breakdown = DriveTimeCalculator.calculateBreakdownFromPoints(points, start, end)
        assertEquals(WINDOW_MS, breakdown.drivingMs)      // 0-5분
        assertEquals(WINDOW_MS, breakdown.unknownMs)       // 5-10분
        assertEquals(WINDOW_MS, breakdown.stationaryMs)    // 10-15분
        // 합계 = total
        assertEquals(end, breakdown.drivingMs + breakdown.stationaryMs + breakdown.unknownMs)
    }

    @Test
    fun `gpsCoveragePct 계산 정확성`() {
        val start = 0L
        val end = 15 * 60_000L // 3 윈도우

        val points = mutableListOf<Pair<Long, Double>>()
        // 윈도우 0-5: 이동
        for (i in 0 until 10) { points.add((i * 30_000L) to 30.0) }
        // 윈도우 5-10: 없음 (unknown)
        // 윈도우 10-15: 정지
        for (i in 0 until 10) { points.add(((10 * 60_000L) + i * 30_000L) to 0.5) }

        val breakdown = DriveTimeCalculator.calculateBreakdownFromPoints(points, start, end)
        // covered = driving + stationary = 2 윈도우 = 10분 / 15분 total
        val expected = (2.0 / 3.0) * 100.0
        assertEquals(expected, breakdown.gpsCoveragePct, 0.1)
    }

    @Test
    fun `gap 경계값 120초 정확히는 unknown 아님`() {
        val start = 0L
        val end = WINDOW_MS
        // gap = 정확히 120초 (2분) → 120,000ms 이하 → OK
        val points = listOf(
            0L to 30.0,
            120_000L to 30.0,     // gap = 120,000ms (exactly)
            (4 * 60_000L) to 30.0
        )
        val breakdown = DriveTimeCalculator.calculateBreakdownFromPoints(points, start, end)
        assertEquals(WINDOW_MS, breakdown.drivingMs)
        assertEquals(0L, breakdown.unknownMs)
    }
}
