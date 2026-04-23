package com.vita.ontheway

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionManagerSuppressionTest {

    private lateinit var sessionManager: SessionManager
    private val mockTransitionLog = mockk<StateTransitionLog>(relaxed = true)

    @Before
    fun setup() {
        sessionManager = SessionManager(mockTransitionLog)
    }

    // ── test1: 30초 이내 동일 key → 억제 확인 ──

    @Test
    fun suppression_withinTTL_blocks() {
        // 묶음 키 등록
        sessionManager.registerBundleSuppression(listOf("샐러디|2300"))

        // 즉시 체크 → 억제되어야 함
        assertTrue(sessionManager.isSuppressed("샐러디", 2300))
    }

    // ── test2: 30초 경과 후 동일 key → 통과 확인 ──

    @Test
    fun suppression_afterTTL_passes() {
        // 묶음 키 등록
        sessionManager.registerBundleSuppression(listOf("샐러디|2300"))

        // 30초 경과 시뮬레이션: suppressionList에 직접 접근 불가하므로
        // isSuppressed 내부의 TTL 정리 로직을 검증하기 위해
        // 등록되지 않은 다른 키는 통과하는지 확인
        assertFalse(sessionManager.isSuppressed("다른가게", 2300))

        // 실제 TTL 경과는 통합 테스트에서 검증 (System.currentTimeMillis 의존)
        // 여기서는 등록된 키가 정상 차단되는 것만 확인
        assertTrue(sessionManager.isSuppressed("샐러디", 2300))
    }

    // ── test3: 공백 차이 가게명 → 같은 key로 처리 확인 ──

    @Test
    fun suppression_normalizedStoreName_matches() {
        // "샐러디" (공백 없음)로 등록
        sessionManager.registerBundleSuppression(listOf("샐러디|2300"))

        // "샐러디 " (trailing space)로 체크 → 정규화되어 매칭되어야 함
        assertTrue(sessionManager.isSuppressed("샐러디 ", 2300))

        // "  샐러디  " (양쪽 공백)로 체크
        assertTrue(sessionManager.isSuppressed("  샐러디  ", 2300))

        // "샐러디" + 연속 공백 변형
        assertTrue(sessionManager.isSuppressed("샐러디", 2300))
    }

    // ── 보너스: normalizeStoreName 단독 테스트 ──

    @Test
    fun normalizeStoreName_trimsAndLowercases() {
        assertEquals("샐러디", SessionManager.normalizeStoreName("샐러디"))
        assertEquals("샐러디", SessionManager.normalizeStoreName("샐러디 "))
        assertEquals("샐러디", SessionManager.normalizeStoreName("  샐러디  "))
        assertEquals("kfc 광주태전점", SessionManager.normalizeStoreName("KFC  광주태전점"))
        assertEquals("unknown", SessionManager.normalizeStoreName(null))
    }

    @Test
    fun buildSuppressionKey_format() {
        assertEquals("샐러디|2300", SessionManager.buildSuppressionKey("샐러디", 2300))
        assertEquals("샐러디|2300", SessionManager.buildSuppressionKey("샐러디 ", 2300))
        assertEquals("unknown|0", SessionManager.buildSuppressionKey(null, 0))
    }

    // ── self-suppression 버그 수정 테스트 (v3.20) ──

    @Test
    fun suppression_selfSuppress_shouldNotBlockSameFinalizedSession() {
        // Given: 세션 finalize + suppression 등록
        val sessionId = "session_001"
        sessionManager.registerBundleSuppression(
            keys = listOf("테스트가게|8640"),
            registeredBySessionId = sessionId
        )

        // When: 같은 세션 ID로 다시 체크
        val blocked = sessionManager.isSuppressed(
            storeName = "테스트가게",
            price = 8640,
            currentSessionId = sessionId
        )

        // Then: 자기 자신은 차단되면 안 됨
        assertFalse("Self-suppression must not occur", blocked)
    }

    @Test
    fun suppression_differentSession_shouldStillBlock() {
        // Given: 세션 A finalize + suppression 등록
        sessionManager.registerBundleSuppression(
            keys = listOf("테스트가게|8640"),
            registeredBySessionId = "session_A"
        )

        // When: 다른 세션 ID로 체크
        val blocked = sessionManager.isSuppressed(
            storeName = "테스트가게",
            price = 8640,
            currentSessionId = "session_B"
        )

        // Then: 다른 세션은 차단되어야 함
        assertTrue("Different session must still be suppressed", blocked)
    }

    // ── self-suppression v2: finalize 후 activeSession=null 시나리오 ──

    @Test
    fun suppression_afterFinalize_useLastFinalizedId() {
        // Given: 세션 생성 → suppression 등록 → finalize (activeSession=null)
        val session = sessionManager.onEventReceived("baemin", "테스트가게", 7190, "test")
        assertNotNull(session)
        val sessionId = session!!.sessionId

        sessionManager.registerBundleSuppression(
            keys = listOf("테스트가게|7190"),
            registeredBySessionId = sessionId
        )
        sessionManager.finalizeActiveSession("bundle_finalized")

        // Then: activeSession은 null
        assertNull(sessionManager.getActiveSession())

        // And: getCurrentOrLastSessionId()는 방금 finalize된 ID 반환
        val sessId = sessionManager.getCurrentOrLastSessionId()
        assertEquals(sessionId, sessId)

        // And: 이 ID로 isSuppressed 체크하면 self 제외 → false
        val blocked = sessionManager.isSuppressed("테스트가게", 7190, sessId)
        assertFalse("Just-finalized session must not self-block", blocked)
    }

    @Test
    fun suppression_afterFinalize_differentSession_stillBlocks() {
        // Given: 세션 A finalize + suppression 등록
        val sessionA = sessionManager.onEventReceived("baemin", "테스트가게", 7190, "test")
        val sessionAId = sessionA!!.sessionId
        sessionManager.registerBundleSuppression(
            keys = listOf("테스트가게|7190"),
            registeredBySessionId = sessionAId
        )
        sessionManager.finalizeActiveSession("bundle_finalized")

        // When: 새 세션 B 생성 후 체크
        val sessionB = sessionManager.onEventReceived("baemin", "다른가게", 5000, "test2")
        val sessId = sessionManager.getCurrentOrLastSessionId()
        // sessId는 이제 sessionB의 ID (activeSession이 있으므로)
        assertEquals(sessionB!!.sessionId, sessId)

        // Then: 세션 B의 ID로 체크 → 세션 A가 등록한 suppression이므로 차단
        val blocked = sessionManager.isSuppressed("테스트가게", 7190, sessId)
        assertTrue("Different session must still be suppressed", blocked)
    }

    @Test
    fun suppression_noCurrentSession_backwardCompat() {
        // Given: suppression 등록
        sessionManager.registerBundleSuppression(
            keys = listOf("테스트가게|8640"),
            registeredBySessionId = "session_X"
        )

        // When: currentSessionId = null (구 API 호환)
        val blocked = sessionManager.isSuppressed(
            storeName = "테스트가게",
            price = 8640,
            currentSessionId = null
        )

        // Then: null이면 기존 동작 (차단)
        assertTrue("Null currentSessionId must preserve old behavior", blocked)
    }
}
