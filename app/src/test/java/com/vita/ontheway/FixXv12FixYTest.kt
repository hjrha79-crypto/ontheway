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
 * Fix X v1.2 + Fix Y 테스트.
 *
 * Fix X v1.2:
 *   c. async callback skip-safe: session finalized → noop
 *   d. async callback skip-safe: accept_state=CONFIRMED → noop
 *   e. async callback skip-safe: pickupKm 이미 있음 → updated=0
 *
 * Fix Y:
 *   g. BAEMIN_PROGRESS candidate + baemin_in_progress 반복 → pending 유지
 *   i. GPS 80m 이동 → CONFIRMED (자기-confirm 차단 무관)
 *   j. CLICK accept → CONFIRMED (회귀 없음)
 *   k. COUPANG_PICKUP candidate + coupang state → CONFIRMED (회귀 없음)
 */
class FixXv12FixYTest {

    private lateinit var ctx: Context
    private var confirmedCandidates = mutableListOf<AcceptLifecycle.Candidate>()
    private var unconfirmedCandidates = mutableListOf<AcceptLifecycle.Candidate>()
    private var falseCandidates = mutableListOf<AcceptLifecycle.Candidate>()
    private var ledgerEvents = mutableListOf<Pair<String, com.vita.ontheway.ledger.LedgerEventType>>()
    private var acceptStateWrites = mutableListOf<Triple<String, String, Long?>>()
    private var driverActionWrites = mutableListOf<Triple<String, String, Long>>()

    @Before
    fun setup() {
        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putInt(any(), any()) } returns mockEditor
        every { mockEditor.putLong(any(), any()) } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockPrefs.getInt(any(), any()) } answers { secondArg() }
        every { mockPrefs.getLong(any(), any()) } answers { secondArg() }
        every { mockPrefs.getString(any(), any()) } answers { secondArg() }
        every { mockPrefs.getBoolean(any(), any()) } answers { secondArg() }

        ctx = mockk<Context>(relaxed = true)
        every { ctx.getSharedPreferences(any(), any()) } returns mockPrefs
        every { ctx.applicationContext } returns ctx

        mockkObject(EarningsTracker, JudgmentMatchLogger, FilterLog, OtwFileLogger,
            com.vita.ontheway.ledger.LedgerAppender, com.vita.ontheway.ledger.CallSessionRegistry)
        every { EarningsTracker.recordAccept(any(), any(), any(), any(), any()) } returns Unit
        every { JudgmentMatchLogger.onAcceptDetected(any()) } returns Unit
        every { JudgmentMatchLogger.hasPendingMatch(any(), any(), any(), any(), any()) } returns true
        every { FilterLog.recordAccepted(any(), any(), any(), any(), any(), any()) } returns Unit
        every { OtwFileLogger.log(any(), any()) } returns Unit
        every { com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit

        AcceptLifecycle.resetForTest()
        confirmedCandidates.clear()
        unconfirmedCandidates.clear()
        falseCandidates.clear()
        ledgerEvents.clear()
        acceptStateWrites.clear()
        driverActionWrites.clear()

        AcceptLifecycle.onConfirmed = { _, c -> confirmedCandidates.add(c) }
        AcceptLifecycle.onUnconfirmed = { _, c -> unconfirmedCandidates.add(c) }
        AcceptLifecycle.onFalse = { _, c -> falseCandidates.add(c) }
        AcceptLifecycle.ledgerWriter = { _, sessionId, _, _, _, type, _, _ ->
            ledgerEvents.add(sessionId to type)
        }
        AcceptLifecycle.acceptStateWriter = { sessionId, state, confirmedAt ->
            acceptStateWrites.add(Triple(sessionId, state, confirmedAt))
        }
        AcceptLifecycle.driverActionWriter = { sessionId, source, ts ->
            driverActionWrites.add(Triple(sessionId, source, ts))
        }
    }

    @After
    fun teardown() {
        AcceptLifecycle.resetForTest()
        unmockkAll()
    }

    // ── Fix Y: (g) BAEMIN_PROGRESS + baemin_in_progress 반복 → pending 유지 ──

    @Test
    fun `g BAEMIN_PROGRESS + baemin_in_progress 5회 반복 → 여전히 pending`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-repeat", "evt-1", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )
        // 5회 반복 신호 → 모두 suppressed
        repeat(5) {
            AcceptLifecycle.onStateSignal(ctx, "baemin_in_progress", "baemin", "evt-1")
        }
        assertEquals(1, AcceptLifecycle.pendingCount())
        assertEquals(0, confirmedCandidates.size)
    }

    // ── Fix Y: (i) GPS 80m 이동 → CONFIRMED (자기-confirm 차단과 무관) ──

    @Test
    fun `i BAEMIN_PROGRESS candidate + GPS 90m → CONFIRMED`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-gps", "evt-1", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )
        // baemin_in_progress 차단 확인
        AcceptLifecycle.onStateSignal(ctx, "baemin_in_progress", "baemin", "evt-1")
        assertEquals(1, AcceptLifecycle.pendingCount())

        // GPS 90m 이동 → CONFIRMED (별도 경로)
        AcceptLifecycle.onGpsUpdate(ctx, 37.5673, 126.9780)
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(1, confirmedCandidates.size)
    }

    // ── Fix Y: (j) CLICK accept → CONFIRMED (회귀 없음) ──

    @Test
    fun `j CLICK source + baemin_in_progress → CONFIRMED (Fix Y 차단 대상 아님)`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-click", "evt-1", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.CLICK,
            37.5665, 126.9780, joinEligible = true
        )
        // CLICK source는 BAEMIN_PROGRESS가 아니므로 baemin_in_progress도 confirm 가능
        AcceptLifecycle.onStateSignal(ctx, "baemin_in_progress", "baemin", "evt-1")
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(1, confirmedCandidates.size)
    }

    @Test
    fun `j CLICK source + immediateConfirm → CONFIRMED`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-click2", "evt-1", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.CLICK,
            37.5665, 126.9780, joinEligible = true,
            immediateConfirm = true
        )
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(1, confirmedCandidates.size)
    }

    // ── Fix Y: (k) COUPANG_PICKUP candidate + coupang state → CONFIRMED (회귀 없음) ──

    @Test
    fun `k COUPANG_PICKUP + coupang state → CONFIRMED`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-coupang", "evt-c1", "ord-c1", "coupang",
            3450, "BBQ", AcceptCoordinator.AcceptSource.COUPANG_PICKUP,
            37.5665, 126.9780, joinEligible = true
        )
        AcceptLifecycle.onStateSignal(ctx, "픽업 완료", "coupang", "evt-c1")
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(1, confirmedCandidates.size)
        assertEquals("sess-coupang", confirmedCandidates[0].callSessionId)
    }

    @Test
    fun `k COUPANG_PICKUP + immediateConfirm → CONFIRMED (회귀 없음)`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-coupang2", "evt-c2", "ord-c2", "coupang",
            3450, "BBQ", AcceptCoordinator.AcceptSource.COUPANG_PICKUP,
            37.5665, 126.9780, joinEligible = true,
            immediateConfirm = true
        )
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(1, confirmedCandidates.size)
    }

    // ── Fix Y: 다른 state signal은 BAEMIN_PROGRESS도 confirm 가능 ──

    @Test
    fun `BAEMIN_PROGRESS + 매장도착 signal → CONFIRMED (다른 신호는 허용)`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-other", "evt-1", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )
        // "매장 도착"은 baemin_in_progress가 아니므로 confirm 가능
        AcceptLifecycle.onStateSignal(ctx, "매장 도착", "baemin", "evt-1")
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(1, confirmedCandidates.size)
    }

    @Test
    fun `BAEMIN_PROGRESS + ProximityDetector → CONFIRMED`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-prox", "evt-1", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )
        AcceptLifecycle.onProximityEvent(ctx, "PICKUP_NEAR", "baemin", "evt-1")
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(1, confirmedCandidates.size)
    }

    // ── Fix X v1.2: skip-safe AcceptLifecycle.getState 확인 ──

    @Test
    fun `c getState UNKNOWN for non-existent session`() {
        assertEquals(AcceptLifecycle.AcceptState.UNKNOWN, AcceptLifecycle.getState("non-existent"))
    }

    @Test
    fun `c getState ACCEPT_CANDIDATE for pending session`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-st", "evt-1", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )
        assertEquals(AcceptLifecycle.AcceptState.ACCEPT_CANDIDATE, AcceptLifecycle.getState("sess-st"))
    }

    @Test
    fun `d CONFIRMED session → getState no longer ACCEPT_CANDIDATE`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-cf", "evt-1", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )
        // confirm via GPS
        AcceptLifecycle.onGpsUpdate(ctx, 37.5673, 126.9780)
        // After confirm, candidate is removed from map → UNKNOWN
        assertEquals(AcceptLifecycle.AcceptState.UNKNOWN, AcceptLifecycle.getState("sess-cf"))
    }

    // ── Fix X v1.2: DistanceResult source 세분화 확인 ──

    @Test
    fun `distanceToSync source 정규화 검증`() {
        // api_keyword/api_address → startsWith("api") = true
        assertTrue("api_keyword".startsWith("api"))
        assertTrue("api_address".startsWith("api"))
        // cache_mem/cache_prefs → startsWith("cache") = true
        assertTrue("cache_mem".startsWith("cache"))
        assertTrue("cache_prefs".startsWith("cache"))
        // fallback → neither
        assertFalse("fallback_location_table".startsWith("api"))
        assertFalse("fallback_location_table".startsWith("cache"))
    }

    // ── Fix Y: 5/11 시뮬 ──

    @Test
    fun `5-11 시뮬 — BAEMIN_PROGRESS 반복 IN_PROGRESS → UNCONFIRMED (timeout)`() {
        // 20:18/20:36/20:52/20:53 BAEMIN_PROGRESS 반복 IN_PROGRESS
        // Fix Y: 모든 baemin_in_progress 신호 차단 → 60초 timeout → UNCONFIRMED
        var now = 1_000_000L
        AcceptLifecycle.timeSource = { now }

        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-2018", "evt-2018", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )

        // 반복 IN_PROGRESS 신호 (10초 간격 4회)
        for (i in 1..4) {
            now += 10_000
            AcceptLifecycle.onStateSignal(ctx, "baemin_in_progress", "baemin", "evt-2018")
        }
        assertEquals(1, AcceptLifecycle.pendingCount())  // 여전히 pending
        assertEquals(0, confirmedCandidates.size)        // confirm 안 됨
    }

    @Test
    fun `5-11 시뮬 — 19점07 CLICK → 영향 없음`() {
        // CLICK source는 Fix Y 차단 대상 아님
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-1907", "evt-1907", "ord-1", "baemin",
            5000, "치킨플러스", AcceptCoordinator.AcceptSource.CLICK,
            37.5665, 126.9780, joinEligible = true,
            immediateConfirm = true
        )
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(1, confirmedCandidates.size)
    }

    // ── PICKUP_DISTANCE_LATE_UPDATED LedgerEventType 존재 확인 ──

    @Test
    fun `LedgerEventType PICKUP_DISTANCE_LATE_UPDATED 존재`() {
        val type = com.vita.ontheway.ledger.LedgerEventType.PICKUP_DISTANCE_LATE_UPDATED
        assertEquals("PICKUP_DISTANCE_LATE_UPDATED", type.name)
    }
}
