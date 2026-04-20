package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test

class ScreenTypeDetectorTest {

    @Test
    fun detect_idle() {
        val result = ScreenTypeDetector.detect("배차 대기 신규배차를 켜고 배달을 시작하세요")
        assertEquals(ScreenTypeDetector.ScreenType.IDLE, result.type)
        assertEquals(ScreenTypeDetector.Confidence.HIGH, result.confidence)
    }

    @Test
    fun detect_inProgress() {
        val result = ScreenTypeDetector.detect("배달 중 피나치공 뿌리공스 태전점 고객에게 전달")
        assertEquals(ScreenTypeDetector.ScreenType.IN_PROGRESS, result.type)
    }

    @Test
    fun detect_deliveryResume() {
        val result = ScreenTypeDetector.detect("배달 이어서 하기 배달료 4,990원")
        assertEquals(ScreenTypeDetector.ScreenType.DELIVERY_RESUME, result.type)
    }

    @Test
    fun detect_completedList() {
        val result = ScreenTypeDetector.detect("16:30 완료 16:45 완료 17:00 완료 배달료 3,500원")
        assertEquals(ScreenTypeDetector.ScreenType.COMPLETED_LIST, result.type)
    }

    @Test
    fun detect_bundleSession() {
        val result = ScreenTypeDetector.detect("총 합계 12,000원 2건 모두 수락")
        assertEquals(ScreenTypeDetector.ScreenType.BUNDLE_SESSION, result.type)
    }

    @Test
    fun detect_newCall() {
        val result = ScreenTypeDetector.detect("픽업지 피나치공 뿌리공스 태전점 배달료 4,990원 38.3P")
        assertEquals(ScreenTypeDetector.ScreenType.NEW_CALL, result.type)
    }

    @Test
    fun detect_unknown() {
        val result = ScreenTypeDetector.detect("아무 관련 없는 텍스트")
        assertEquals(ScreenTypeDetector.ScreenType.UNKNOWN, result.type)
    }
}
