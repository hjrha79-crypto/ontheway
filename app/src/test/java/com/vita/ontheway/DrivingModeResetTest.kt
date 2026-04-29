package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * DrivingModeManager 날짜 리셋 로직 단위 테스트.
 * SharedPreferences 없이 순수 날짜 비교 로직만 검증.
 */
class DrivingModeResetTest {

    /** 날짜 변경 여부 판정 (DrivingModeManager 내부 로직 추출) */
    private fun shouldResetDate(savedDate: String, today: String): Boolean {
        return savedDate.isNotEmpty() && savedDate != today
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
    fun `빈 날짜 → 리셋 불필요 (첫 실행)`() {
        assertFalse(shouldResetDate("", "2026-04-29"))
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
}
