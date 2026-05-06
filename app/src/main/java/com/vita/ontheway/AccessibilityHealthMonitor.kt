package com.vita.ontheway

import android.util.Log

/**
 * FIX-SELFPING: Accessibility 서비스 health check.
 * 서비스 외부에 위치 — 서비스 죽으면 내부 루프도 함께 죽으므로.
 *
 * 사용처:
 * - DeliveryNotificationService.onNotificationPosted() (배민/쿠팡 알림 수신 직후)
 * - MainActivity 운행 ON 시
 */
object AccessibilityHealthMonitor {
    private const val TAG = "A11yHealth"

    /** 이벤트 정지 판정 기준 (ms) */
    const val EVENT_STALE_MS = 60_000L

    /** root null 비율 임계값 */
    const val ROOT_NULL_THRESHOLD = 0.3

    enum class Status {
        ALIVE,            // 정상
        INSTANCE_DEAD,    // instance == null (토글 ON이지만 서비스 인스턴스 사망)
        EVENT_STALE,      // lastEventTime 60초 이상 정지
        ROOT_DEGRADED     // root null 비율 30% 이상
    }

    data class HealthResult(
        val status: Status,
        val message: String
    )

    /**
     * health check 실행.
     * @param instanceAlive OnTheWayService.instance != null
     * @param lastEventTimeMs OnTheWayService.lastAccessibilityEventTime (0 = 아직 이벤트 없음)
     * @param nowMs 현재 시간
     * @param rootNullRatio 최근 root null 비율 (0.0 ~ 1.0)
     */
    fun check(
        instanceAlive: Boolean,
        lastEventTimeMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        rootNullRatio: Double = 0.0
    ): HealthResult {
        // 1순위: 인스턴스 사망
        if (!instanceAlive) {
            Log.w(TAG, "INSTANCE_DEAD")
            return HealthResult(Status.INSTANCE_DEAD, "접근성 서비스 인스턴스가 멈춰 있습니다")
        }

        // 2순위: 이벤트 정지 (최소 1회 이벤트 수신 이후만 판정)
        if (lastEventTimeMs > 0 && (nowMs - lastEventTimeMs) > EVENT_STALE_MS) {
            val staleSec = (nowMs - lastEventTimeMs) / 1000
            Log.w(TAG, "EVENT_STALE: ${staleSec}s")
            return HealthResult(Status.EVENT_STALE, "접근성 이벤트 ${staleSec}초 정지")
        }

        // 3순위: root null 비율
        if (rootNullRatio >= ROOT_NULL_THRESHOLD) {
            Log.w(TAG, "ROOT_DEGRADED: ${(rootNullRatio * 100).toInt()}%")
            return HealthResult(Status.ROOT_DEGRADED, "root 접근 실패율 ${(rootNullRatio * 100).toInt()}%")
        }

        return HealthResult(Status.ALIVE, "정상")
    }
}
