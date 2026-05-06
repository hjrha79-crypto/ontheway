package com.vita.ontheway

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityHealthMonitorTest {

    @Test
    fun `instance dead 감지`() {
        val result = AccessibilityHealthMonitor.check(
            instanceAlive = false,
            lastEventTimeMs = System.currentTimeMillis(),
            nowMs = System.currentTimeMillis()
        )
        assertEquals(AccessibilityHealthMonitor.Status.INSTANCE_DEAD, result.status)
    }

    @Test
    fun `event stale 60초 정지 감지`() {
        val now = System.currentTimeMillis()
        val result = AccessibilityHealthMonitor.check(
            instanceAlive = true,
            lastEventTimeMs = now - 90_000L,  // 90초 전
            nowMs = now
        )
        assertEquals(AccessibilityHealthMonitor.Status.EVENT_STALE, result.status)
    }

    @Test
    fun `root null 비율 30pct 이상 감지`() {
        val now = System.currentTimeMillis()
        val result = AccessibilityHealthMonitor.check(
            instanceAlive = true,
            lastEventTimeMs = now - 5_000L,  // 5초 전 (정상)
            nowMs = now,
            rootNullRatio = 0.35
        )
        assertEquals(AccessibilityHealthMonitor.Status.ROOT_DEGRADED, result.status)
    }

    @Test
    fun `정상 상태 ALIVE`() {
        val now = System.currentTimeMillis()
        val result = AccessibilityHealthMonitor.check(
            instanceAlive = true,
            lastEventTimeMs = now - 5_000L,  // 5초 전 (정상)
            nowMs = now,
            rootNullRatio = 0.1
        )
        assertEquals(AccessibilityHealthMonitor.Status.ALIVE, result.status)
    }

    @Test
    fun `이벤트 미수신 상태 (lastEventTime=0) ALIVE`() {
        // 서비스 시작 직후 아직 이벤트 없음 → stale 판정 X
        val now = System.currentTimeMillis()
        val result = AccessibilityHealthMonitor.check(
            instanceAlive = true,
            lastEventTimeMs = 0L,
            nowMs = now
        )
        assertEquals(AccessibilityHealthMonitor.Status.ALIVE, result.status)
    }

    @Test
    fun `instance dead가 event stale보다 우선`() {
        val now = System.currentTimeMillis()
        val result = AccessibilityHealthMonitor.check(
            instanceAlive = false,
            lastEventTimeMs = now - 90_000L,  // stale 조건도 충족
            nowMs = now,
            rootNullRatio = 0.5  // degraded 조건도 충족
        )
        // instance dead가 1순위
        assertEquals(AccessibilityHealthMonitor.Status.INSTANCE_DEAD, result.status)
    }
}
