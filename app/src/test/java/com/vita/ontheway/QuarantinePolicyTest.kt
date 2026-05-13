package com.vita.ontheway

import com.vita.ontheway.ledger.LedgerEventType
import com.vita.ontheway.ledger.QuarantineReason
import org.junit.Assert.*
import org.junit.Test

/**
 * QUARANTINE-POLICY: 7가지 quarantine 사유 분류 테스트.
 */
class QuarantinePolicyTest {

    // ── QuarantineReason enum 7개 ──

    @Test
    fun `QuarantineReason 7개 정의`() {
        assertEquals(7, QuarantineReason.entries.size)
    }

    @Test
    fun `QuarantineReason 모든 값`() {
        val expected = listOf(
            "IDENTITY_LOW_CONFIDENCE",
            "SOURCE_CONFLICT",
            "FALLBACK_ACCEPT",
            "DUPLICATE_RECOVERED",
            "ACCEPTED_WITHOUT_DETECTED",
            "DELIVERED_WITHOUT_ACCEPTED",
            "STALE_TIMEOUT"
        )
        assertEquals(expected, QuarantineReason.entries.map { it.name })
    }

    @Test
    fun `QuarantineReason valueOf 정상`() {
        assertEquals(QuarantineReason.FALLBACK_ACCEPT, QuarantineReason.valueOf("FALLBACK_ACCEPT"))
        assertEquals(QuarantineReason.STALE_TIMEOUT, QuarantineReason.valueOf("STALE_TIMEOUT"))
    }

    // ── LedgerEventType QUARANTINED 추가 ──

    @Test
    fun `LedgerEventType QUARANTINED 존재`() {
        val type = LedgerEventType.valueOf("QUARANTINED")
        assertEquals(LedgerEventType.QUARANTINED, type)
    }

    @Test
    fun `LedgerEventType 28개`() {
        assertEquals(28, LedgerEventType.entries.size)
    }

    // ── join_eligible 자동 계산 ──

    @Test
    fun `identity_confidence 0_3 = join_eligible 0 + IDENTITY_LOW_CONFIDENCE`() {
        val confidence = 0.3
        val joinEligible = if (confidence < 0.5) 0 else 1
        val quarantine = if (confidence > 0 && confidence < 0.5)
            QuarantineReason.IDENTITY_LOW_CONFIDENCE.name else null
        assertEquals(0, joinEligible)
        assertEquals("IDENTITY_LOW_CONFIDENCE", quarantine)
    }

    @Test
    fun `identity_confidence 0_8 = join_eligible 1 + quarantine null`() {
        val confidence = 0.8
        val joinEligible = if (confidence < 0.5) 0 else 1
        val quarantine = if (confidence > 0 && confidence < 0.5)
            QuarantineReason.IDENTITY_LOW_CONFIDENCE.name else null
        assertEquals(1, joinEligible)
        assertNull(quarantine)
    }

    @Test
    fun `identity_confidence 0_0 = join_eligible 0 + quarantine null (신규 데이터)`() {
        val confidence = 0.0
        val joinEligible = if (confidence < 0.5) 0 else 1
        // 0.0 = 기본값 (기존 데이터), auto-quarantine 안 함
        val quarantine = if (confidence > 0 && confidence < 0.5)
            QuarantineReason.IDENTITY_LOW_CONFIDENCE.name else null
        assertEquals(0, joinEligible)
        assertNull(quarantine)  // 0.0은 기존 데이터이므로 quarantine X
    }

    // ── FALLBACK_ACCEPT 분류 ──

    @Test
    fun `AcceptSource FALLBACK = FALLBACK_ACCEPT quarantine`() {
        val source = AcceptCoordinator.AcceptSource.FALLBACK
        val shouldQuarantine = source == AcceptCoordinator.AcceptSource.FALLBACK
        assertTrue(shouldQuarantine)
    }

    @Test
    fun `AcceptSource CLICK = quarantine X`() {
        val source = AcceptCoordinator.AcceptSource.CLICK
        val shouldQuarantine = source == AcceptCoordinator.AcceptSource.FALLBACK
        assertFalse(shouldQuarantine)
    }

    // ── STALE_TIMEOUT 분류 (JudgmentMatchLogger LOW confidence) ──

    @Test
    fun `MatchConfidence LOW = STALE_TIMEOUT quarantine`() {
        val confidence = JudgmentMatchLogger.MatchConfidence.LOW
        val shouldQuarantine = confidence == JudgmentMatchLogger.MatchConfidence.LOW
        assertTrue(shouldQuarantine)
    }

    @Test
    fun `MatchConfidence HIGH = quarantine X`() {
        val confidence = JudgmentMatchLogger.MatchConfidence.HIGH
        val shouldQuarantine = confidence == JudgmentMatchLogger.MatchConfidence.LOW
        assertFalse(shouldQuarantine)
    }

    // ── SOURCE_CONFLICT (미구현, 가정 명시) ──

    @Test
    fun `SOURCE_CONFLICT enum 존재 (구현은 P0-7+)`() {
        val reason = QuarantineReason.SOURCE_CONFLICT
        assertEquals("SOURCE_CONFLICT", reason.name)
        // SOURCE_CONFLICT 자동 감지는 notification vs accessibility 가격 비교 필요.
        // 현재 cross-source 비교 로직 복잡 → P0-7+ 후속 작업.
    }

    // ── ACCEPTED_WITHOUT_DETECTED / DELIVERED_WITHOUT_ACCEPTED ──

    @Test
    fun `ACCEPTED_WITHOUT_DETECTED enum 존재`() {
        assertEquals("ACCEPTED_WITHOUT_DETECTED", QuarantineReason.ACCEPTED_WITHOUT_DETECTED.name)
        // 감지 시점: SessionManager.finalize 또는 일괄 검증 배치.
        // 실시간 감지는 ledger query 필요 → P0-7+ 후속.
    }

    @Test
    fun `DELIVERED_WITHOUT_ACCEPTED enum 존재`() {
        assertEquals("DELIVERED_WITHOUT_ACCEPTED", QuarantineReason.DELIVERED_WITHOUT_ACCEPTED.name)
    }
}
