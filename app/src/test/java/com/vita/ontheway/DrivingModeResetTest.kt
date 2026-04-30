package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * DrivingModeManager 날짜 리셋 로직 단위 테스트.
 * SharedPreferences 없이 순수 날짜 비교 로직만 검증.
 */
class DrivingModeResetTest {

    /** 날짜 변경 여부 판정 — 누적 시간 리셋 (savedDate가 다르면 리셋) */
    private fun shouldResetAccumulated(savedDate: String, today: String, oldTotal: Long = 0L): Boolean {
        return savedDate != today && (savedDate.isNotEmpty() || oldTotal > 0)
    }

    /** 날짜 변경 여부 판정 (하위 호환) */
    private fun shouldResetDate(savedDate: String, today: String): Boolean {
        return shouldResetAccumulated(savedDate, today)
    }

    /** startedAt → effectiveStart 클램프 (자정 이후만 오늘 계산) */
    private fun effectiveStart(startedAtMs: Long, todayMidnightMs: Long): Long {
        return maxOf(startedAtMs, todayMidnightMs)
    }

    @Test
    fun `어제 날짜 → 오늘 날짜 변경 감지`() {
        assertTrue(shouldResetDate("2026-04-28", "2026-04-29"))
    }

    @Test
    fun `같은 날짜 → 리셋 불필요`() {
        assertFalse(shouldResetDate("2026-04-29", "2026-04-29"))
    }

    @Test
    fun `빈 날짜 + 누적값 0 → 리셋 불필요 (첫 실행)`() {
        assertFalse(shouldResetAccumulated("", "2026-04-29", 0L))
    }

    @Test
    fun `빈 날짜 + 누적값 있음 → 리셋 필요 (고아 데이터)`() {
        assertTrue(shouldResetAccumulated("", "2026-04-29", 36_000_000L))
    }

    @Test
    fun `자정 넘어서 운행 ON → 오늘 자정부터 계산`() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.APRIL, 28, 22, 0, 0) // 어제 22시 시작
        val startedAt = cal.timeInMillis

        cal.set(2026, Calendar.APRIL, 29, 0, 0, 0) // 오늘 자정
        cal.set(Calendar.MILLISECOND, 0)
        val todayMidnight = cal.timeInMillis

        val effective = effectiveStart(startedAt, todayMidnight)
        assertEquals(todayMidnight, effective)
    }

    @Test
    fun `오늘 시작한 운행 → startedAt 그대로 사용`() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.APRIL, 29, 8, 0, 0) // 오늘 08시 시작
        val startedAt = cal.timeInMillis

        cal.set(2026, Calendar.APRIL, 29, 0, 0, 0) // 오늘 자정
        cal.set(Calendar.MILLISECOND, 0)
        val todayMidnight = cal.timeInMillis

        val effective = effectiveStart(startedAt, todayMidnight)
        assertEquals(startedAt, effective)
    }

    @Test
    fun `ongoing 시간 계산 - 자정 넘김 케이스`() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.APRIL, 28, 22, 0, 0) // 어제 22시 시작
        val startedAt = cal.timeInMillis

        cal.set(2026, Calendar.APRIL, 29, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayMidnight = cal.timeInMillis

        cal.set(2026, Calendar.APRIL, 29, 2, 0, 0) // 오늘 02시 현재
        val now = cal.timeInMillis

        val effective = effectiveStart(startedAt, todayMidnight)
        val ongoing = now - effective
        // 자정~02시 = 2시간 = 7,200,000ms
        assertEquals(2 * 3600 * 1000L, ongoing)
    }

    @Test
    fun `날짜 변경 시 startedAt을 현재로 설정하면 팬텀 시간 없음`() {
        // 시나리오: 어제 ON, 앱 kill, 오늘 재시작
        // fix: startedAt = now (자정 아님)
        val cal = Calendar.getInstance()

        cal.set(2026, Calendar.APRIL, 29, 10, 0, 0) // 오늘 10시 재시작
        val restartNow = cal.timeInMillis

        // fix 적용: startedAt = restartNow (not midnight)
        val startedAt = restartNow

        cal.set(2026, Calendar.APRIL, 29, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayMidnight = cal.timeInMillis

        val effective = effectiveStart(startedAt, todayMidnight)
        val ongoing = restartNow - effective
        // startedAt(10시) >= midnight → effective = 10시
        // ongoing = 10시 - 10시 = 0 (팬텀 시간 없음)
        assertEquals(0L, ongoing)
    }

    @Test
    fun `OFF→ON 후 누적 리셋 시뮬레이션`() {
        // 어제 10시간 운행, 오늘 OFF→ON 시 리셋 확인
        val yesterday = "2026-04-28"
        val today = "2026-04-29"
        val oldAccumulated = 10 * 3600 * 1000L // 10시간

        // checkAndResetDate: savedDate != today → 리셋
        assertTrue(shouldResetAccumulated(yesterday, today, oldAccumulated))

        // 리셋 후 accumulated = 0
        val accumulated = 0L

        // fix 적용: startedAt = now (not midnight)
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.APRIL, 29, 8, 0, 0)
        val now = cal.timeInMillis
        val startedAt = now // fix: 현재 시각

        // OFF 시점 (즉시)
        cal.set(2026, Calendar.APRIL, 29, 8, 0, 1)
        val offTime = cal.timeInMillis

        cal.set(2026, Calendar.APRIL, 29, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val midnight = cal.timeInMillis

        val effective = effectiveStart(startedAt, midnight)
        val duration = offTime - effective // ~1초
        val total = accumulated + duration
        assertTrue("팬텀 시간 없이 1초 이하여야 함", total < 2000L)
    }
}
