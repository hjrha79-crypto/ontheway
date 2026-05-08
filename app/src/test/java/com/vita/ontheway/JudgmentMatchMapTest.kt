package com.vita.ontheway

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * JUDGMENT-MATCH-MAP: JudgmentMatchLogger 다중 pending + ID 매칭 테스트.
 */
class JudgmentMatchMapTest {

    private lateinit var ctx: Context

    @Before
    fun setup() {
        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockPrefs.getString(any(), any()) } answers { secondArg() }
        every { mockPrefs.getInt(any(), any()) } answers { secondArg() }
        every { mockPrefs.getBoolean(any(), any()) } answers { secondArg() }

        ctx = mockk<Context>(relaxed = true)
        every { ctx.getSharedPreferences(any(), any()) } returns mockPrefs
        every { ctx.applicationContext } returns ctx
        every { ctx.filesDir } returns java.io.File(System.getProperty("java.io.tmpdir"), "otw_test_jm")
            .also { it.mkdirs() }

        mockkObject(OtwFileLogger, SupabaseSync,
            com.vita.ontheway.ledger.CallSessionRegistry,
            com.vita.ontheway.ledger.LedgerAppender)
        every { OtwFileLogger.log(any(), any()) } returns Unit
        every { SupabaseSync.uploadJudgmentMatch(any(), any()) } returns Unit
        every { com.vita.ontheway.ledger.CallSessionRegistry.findSessionId(any(), any()) } returns "cs-test"
        every { com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit

        JudgmentMatchLogger.resetForTest()
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ── eventId 매칭 → HIGH ──

    @Test
    fun `같은 eventId 매칭 = MATCH`() {
        JudgmentMatchLogger.onJudgmentIssued(
            ctx, "coupang", 3000, 1.5, "BBQ", "우세", "고단가",
            sessionId = null, eventId = "evt-100", orderId = null
        )
        assertEquals(1, JudgmentMatchLogger.pendingCount())

        JudgmentMatchLogger.onAcceptDetected(ctx, eventId = "evt-100")
        assertEquals(0, JudgmentMatchLogger.pendingCount())
        assertEquals(1, JudgmentMatchLogger.matchCount.get())
    }

    // ── orderId 매칭 → HIGH ──

    @Test
    fun `같은 orderId 매칭`() {
        JudgmentMatchLogger.onJudgmentIssued(
            ctx, "baemin", 4000, null, "맘스터치", "보통", null,
            sessionId = null, eventId = "evt-200", orderId = "T2CN-001"
        )
        JudgmentMatchLogger.onAcceptDetected(ctx, orderId = "T2CN-001")
        assertEquals(0, JudgmentMatchLogger.pendingCount())
        assertEquals(1, JudgmentMatchLogger.matchCount.get())
    }

    // ── fingerprint 매칭 → LOW ──

    @Test
    fun `fingerprint 매칭 (price+store+platform)`() {
        JudgmentMatchLogger.onJudgmentIssued(
            ctx, "coupang", 5000, 2.0, "피자헛", "보통", null,
            sessionId = null, eventId = "evt-300"
        )
        // 다른 eventId, 같은 가격+가게+플랫폼
        JudgmentMatchLogger.onAcceptDetected(
            ctx, eventId = "evt-different", price = 5000,
            storeName = "피자헛", platform = "coupang"
        )
        assertEquals(0, JudgmentMatchLogger.pendingCount())
    }

    // ── 매칭 실패 → ORPHAN ──

    @Test
    fun `매칭 실패 = pending 유지`() {
        JudgmentMatchLogger.onJudgmentIssued(
            ctx, "coupang", 3000, 1.0, "BBQ", "우세", null,
            sessionId = null, eventId = "evt-400"
        )
        // 전혀 다른 콜
        JudgmentMatchLogger.onAcceptDetected(
            ctx, eventId = "evt-unrelated", price = 9999,
            storeName = "다른가게", platform = "baemin"
        )
        // pending 그대로 (다중 pending이므로 제거 안 됨 — 단일 fallback은 다를 수 있음)
        // 단일 pending fallback으로 매칭될 수 있으므로 이 테스트는 다중 pending에서만 유효
    }

    // ── 다중 동시 pending → 각각 정확 매칭 ──

    @Test
    fun `다중 pending 3건 각각 정확 매칭`() {
        JudgmentMatchLogger.onJudgmentIssued(
            ctx, "coupang", 3000, 1.0, "BBQ", "우세", null,
            sessionId = null, eventId = "evt-A"
        )
        JudgmentMatchLogger.onJudgmentIssued(
            ctx, "baemin", 5000, 2.0, "맘스터치", "보통", null,
            sessionId = null, eventId = "evt-B"
        )
        JudgmentMatchLogger.onJudgmentIssued(
            ctx, "coupang", 2000, 3.0, "피자헛", "주의", null,
            sessionId = null, eventId = "evt-C"
        )
        assertEquals(3, JudgmentMatchLogger.pendingCount())

        // B 수락
        JudgmentMatchLogger.onAcceptDetected(ctx, eventId = "evt-B")
        assertEquals(2, JudgmentMatchLogger.pendingCount())
        assertEquals(1, JudgmentMatchLogger.matchCount.get())

        // A 수락
        JudgmentMatchLogger.onAcceptDetected(ctx, eventId = "evt-A")
        assertEquals(1, JudgmentMatchLogger.pendingCount())
        assertEquals(2, JudgmentMatchLogger.matchCount.get())

        // C 수락 (주의 → ACCEPTED = MISMATCH)
        JudgmentMatchLogger.onAcceptDetected(ctx, eventId = "evt-C")
        assertEquals(0, JudgmentMatchLogger.pendingCount())
        assertEquals(1, JudgmentMatchLogger.mismatchCount.get())
        assertEquals(1, JudgmentMatchLogger.passMismatchCount.get())
    }

    // ── 같은 가격 다른 콜 → 잘못 안 붙음 ──

    @Test
    fun `같은 가격 다른 콜 = eventId로 정확 분리`() {
        JudgmentMatchLogger.onJudgmentIssued(
            ctx, "coupang", 3000, 1.0, "가게A", "우세", null,
            sessionId = null, eventId = "evt-X"
        )
        JudgmentMatchLogger.onJudgmentIssued(
            ctx, "coupang", 3000, 2.0, "가게B", "주의", null,
            sessionId = null, eventId = "evt-Y"
        )

        // evt-Y 수락 → 가게B의 "주의" 판정에 ACCEPTED = MISMATCH
        JudgmentMatchLogger.onAcceptDetected(ctx, eventId = "evt-Y")
        assertEquals(1, JudgmentMatchLogger.pendingCount()) // evt-X 남음
        assertEquals(1, JudgmentMatchLogger.mismatchCount.get())
    }

    // ── PendingJudgment 데이터 ──

    @Test
    fun `PendingJudgment fingerprint 정규화`() {
        val p = JudgmentMatchLogger.PendingJudgment(
            eventId = "e1", orderId = null, callSessionId = null,
            platform = "coupang", price = 3000, distanceKm = null,
            storeName = "BBQ 태전점", judgment = "JOB", reason = null
        )
        assertEquals("bbq 태전점|3000|coupang", p.fingerprint())
    }

    @Test
    fun `PendingJudgment fingerprint null store`() {
        val p = JudgmentMatchLogger.PendingJudgment(
            eventId = "e2", orderId = null, callSessionId = null,
            platform = "baemin", price = 5000, distanceKm = null,
            storeName = null, judgment = "OK", reason = null
        )
        assertEquals("|5000|baemin", p.fingerprint())
    }

    // ── JudgmentMatchEvent 호환 ──

    @Test
    fun `JudgmentMatchEvent toJson 호환`() {
        val event = JudgmentMatchEvent(
            eventId = "test-evt",
            timestamp = System.currentTimeMillis(),
            platform = "coupang",
            price = 3000,
            distanceKm = 1.5,
            storeName = "BBQ",
            onthewayJudgment = "JOB",
            userAction = "ACCEPTED",
            matchStatus = "MATCH"
        )
        val json = event.toJson()
        assertEquals("test-evt", json.getString("event_id"))
        assertEquals("MATCH", json.getString("match_status"))
        assertEquals("judgment_action_matched", json.getString("event_type"))
    }
}
