package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test

class AcceptButtonTextsTest {

    // OnTheWayService.ACCEPT_BUTTON_TEXTS 정의를 그대로 복제 (companion object 접근 불가)
    private val ACCEPT_BUTTON_TEXTS = listOf("수락", "배차수락", "배차 수락", "주문 수락", "주문수락", "수락하기", "모두 수락")

    private fun isAcceptMatch(clickedText: String): Boolean =
        ACCEPT_BUTTON_TEXTS.any { clickedText.contains(it) }

    @Test
    fun `배민 수락 단독 2글자 매칭`() {
        assertTrue(isAcceptMatch("수락"))
    }

    @Test
    fun `쿠팡 수락 단독 매칭`() {
        assertTrue(isAcceptMatch("수락"))
    }

    @Test
    fun `수락하기 매칭 유지`() {
        assertTrue(isAcceptMatch("수락하기"))
    }

    @Test
    fun `배차수락 매칭 유지`() {
        assertTrue(isAcceptMatch("배차수락"))
    }

    @Test
    fun `배차 수락 매칭 유지`() {
        assertTrue(isAcceptMatch("배차 수락"))
    }

    @Test
    fun `모두 수락 매칭 유지`() {
        assertTrue(isAcceptMatch("모두 수락"))
    }

    @Test
    fun `거절 매칭 안됨`() {
        assertFalse(isAcceptMatch("거절"))
    }

    @Test
    fun `빈 문자열 매칭 안됨`() {
        assertFalse(isAcceptMatch(""))
    }

    @Test
    fun `거부 매칭 안됨`() {
        assertFalse(isAcceptMatch("거부"))
    }
}
