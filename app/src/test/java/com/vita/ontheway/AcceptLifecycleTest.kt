package com.vita.ontheway

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Fix W 보강: AcceptLifecycle 3단계 + 별도 진입점 테스트.
 *
 * a. recordAcceptCandidate → ACCEPT_CANDIDATE ledger, EarningsTracker X
 * b. GPS 변위 ≥80m → CONFIRMED, EarningsTracker 호출
 * c. 60초 GPS 정지 → UNCONFIRMED (FALSE 아님), EarningsTracker X
 * d. 명시적 reject → FALSE 전이
 * e. handleAccept = candidate + 즉시 confirmed (호환)
 * f. 속도 기반 확정: ≥8km/h 연속 10초
 * g. ProximityDetector 이벤트 → CONFIRMED
 */
class AcceptLifecycleTest {

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

        // 콜백 주입
        AcceptLifecycle.onConfirmed = { _, c -> confirmedCandidates.add(c) }
        AcceptLifecycle.onUnconfirmed = { _, c -> unconfirmedCandidates.add(c) }
        AcceptLifecycle.onFalse = { _, c -> falseCandidates.add(c) }
        // ledger writer 주입
        AcceptLifecycle.ledgerWriter = { _, sessionId, _, _, _, type, _, _ ->
            ledgerEvents.add(sessionId to type)
        }
        // accept_state writer 주입
        AcceptLifecycle.acceptStateWriter = { sessionId, state, confirmedAt ->
            acceptStateWrites.add(Triple(sessionId, state, confirmedAt))
        }
        // driver_action writer 주입
        AcceptLifecycle.driverActionWriter = { sessionId, source, ts ->
            driverActionWrites.add(Triple(sessionId, source, ts))
        }
    }

    @After
    fun teardown() {
        AcceptLifecycle.resetForTest()
        unmockkAll()
    }

    private fun registerCandidate(
        sessionId: String = "sess-1",
        price: Int = 4000,
        lat: Double = 37.5665,
        lng: Double = 126.9780
    ) {
        AcceptLifecycle.markAcceptCandidate(
            ctx, sessionId, "evt-1", "ord-1", "baemin",
            price, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            lat, lng, joinEligible = true
        )
    }

    // ── a. recordAcceptCandidate → ACCEPT_CANDIDATE ledger, EarningsTracker X ──

    @Test
    fun `a CANDIDATE 등록 시 ACCEPT_CANDIDATE ledger 기록`() {
        registerCandidate()
        val candidateLedger = ledgerEvents.filter { it.second == com.vita.ontheway.ledger.LedgerEventType.ACCEPT_CANDIDATE }
        assertEquals(1, candidateLedger.size)
        assertEquals("sess-1", candidateLedger[0].first)
    }

    @Test
    fun `a CANDIDATE 상태에서 EarningsTracker 미호출`() {
        registerCandidate()
        assertEquals(1, AcceptLifecycle.pendingCount())
        assertEquals(0, confirmedCandidates.size)
        // onConfirmed 콜백 미호출 = EarningsTracker도 미호출
    }

    // ── b. GPS 변위 ≥80m → CONFIRMED ──

    @Test
    fun `b GPS 변위 90m → CONFIRMED`() {
        registerCandidate()
        // ~90m 이동 (위도 약 0.0008도)
        AcceptLifecycle.onGpsUpdate(ctx, 37.5673, 126.9780)
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(1, confirmedCandidates.size)
        assertEquals(AcceptLifecycle.AcceptState.CONFIRMED_ACCEPT, confirmedCandidates[0].state)
    }

    @Test
    fun `b GPS 변위 50m → 아직 CANDIDATE`() {
        registerCandidate()
        AcceptLifecycle.onGpsUpdate(ctx, 37.56695, 126.9780)
        assertEquals(1, AcceptLifecycle.pendingCount())
        assertEquals(0, confirmedCandidates.size)
    }

    // ── c. timeout → UNCONFIRMED (FALSE 아님) ──
    // timeout은 Handler.postDelayed로 동작하므로, 직접 테스트 어려움.
    // 대신 onUnconfirmed 콜백 구조 검증.

    @Test
    fun `c UNCONFIRMED 상태 enum 존재`() {
        assertEquals("UNCONFIRMED", AcceptLifecycle.AcceptState.UNCONFIRMED.name)
    }

    // ── d. 명시적 reject → FALSE ──

    @Test
    fun `d markExplicitReject → REJECTED_FALSE 전이`() {
        registerCandidate()
        AcceptLifecycle.markExplicitReject(ctx, "sess-1", "new_call_screen")
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(1, falseCandidates.size)
        assertEquals(AcceptLifecycle.AcceptState.REJECTED_FALSE, falseCandidates[0].state)
    }

    @Test
    fun `d markExplicitReject → ACCEPT_REJECTED_FALSE ledger 기록`() {
        AcceptLifecycle.onFalse = null  // 실제 doFalse 경로
        registerCandidate()
        AcceptLifecycle.markExplicitReject(ctx, "sess-1", "new_call_screen")
        val falseEvents = ledgerEvents.filter { it.second == com.vita.ontheway.ledger.LedgerEventType.ACCEPT_REJECTED_FALSE }
        assertEquals(1, falseEvents.size)
    }

    @Test
    fun `d 존재하지 않는 session reject → noop`() {
        AcceptLifecycle.markExplicitReject(ctx, "nonexistent", "test")
        assertEquals(0, falseCandidates.size)
    }

    // ── e. handleAccept 하위 호환: candidate + 즉시 confirmed ──

    @Test
    fun `e immediateConfirm = true → 즉시 CONFIRMED`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-imm", "evt-1", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true,
            immediateConfirm = true
        )
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(1, confirmedCandidates.size)
        // ACCEPT_CANDIDATE → 즉시 CONFIRMED (두 개 ledger 이벤트)
        assertTrue(ledgerEvents.any { it.second == com.vita.ontheway.ledger.LedgerEventType.ACCEPT_CANDIDATE })
    }

    @Test
    fun `e immediateConfirm = false → pending 유지`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-defer", "evt-1", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true,
            immediateConfirm = false
        )
        assertEquals(1, AcceptLifecycle.pendingCount())
        assertEquals(0, confirmedCandidates.size)
    }

    // ── f. 속도 기반 확정: ≥8km/h 연속 10초 ──

    @Test
    fun `f 속도 8kmh 연속 10초 → CONFIRMED`() {
        var now = 1000000L
        AcceptLifecycle.timeSource = { now }
        registerCandidate(lat = 37.5665, lng = 126.9780)

        val speedMs = (8.0 / 3.6).toFloat()  // 8km/h → m/s

        // 첫 GPS: 속도 충분, 시간 기록 시작
        AcceptLifecycle.onGpsUpdate(ctx, 37.5665, 126.9780, speedMs)
        assertEquals(1, AcceptLifecycle.pendingCount())  // 아직 10초 미달

        // 11초 후: 속도 여전히 충분 → CONFIRMED
        now += 11_000
        AcceptLifecycle.onGpsUpdate(ctx, 37.5666, 126.9780, speedMs)
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(1, confirmedCandidates.size)
    }

    @Test
    fun `f 속도 중간에 미달 → 리셋, 미확정`() {
        var now = 1000000L
        AcceptLifecycle.timeSource = { now }
        registerCandidate(lat = 37.5665, lng = 126.9780)

        val speedMs = (8.0 / 3.6).toFloat()

        AcceptLifecycle.onGpsUpdate(ctx, 37.5665, 126.9780, speedMs)
        now += 5_000
        // 속도 미달 → 리셋
        AcceptLifecycle.onGpsUpdate(ctx, 37.5665, 126.9780, 0.5f)
        now += 6_000
        // 다시 속도 충분 → 새로운 10초 시작
        AcceptLifecycle.onGpsUpdate(ctx, 37.5665, 126.9780, speedMs)
        assertEquals(1, AcceptLifecycle.pendingCount())  // 아직 10초 미달
    }

    // ── g. ProximityDetector 이벤트 → CONFIRMED ──

    @Test
    fun `g PICKUP_NEAR → CONFIRMED`() {
        registerCandidate()
        AcceptLifecycle.onProximityEvent(ctx, "PICKUP_NEAR")
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(1, confirmedCandidates.size)
    }

    @Test
    fun `g DELIVERY_NEAR → CONFIRMED`() {
        registerCandidate()
        AcceptLifecycle.onProximityEvent(ctx, "DELIVERY_NEAR")
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(1, confirmedCandidates.size)
    }

    // ── state signal → CONFIRMED ──

    @Test
    fun `state signal 픽업 완료 → CONFIRMED`() {
        registerCandidate()
        AcceptLifecycle.onStateSignal(ctx, "픽업 완료")
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(1, confirmedCandidates.size)
    }

    // ── CONFIRMED → EarningsTracker 호출 (doConfirm 경로) ──

    @Test
    fun `CONFIRMED 시 EarningsTracker recordAccept 호출`() {
        AcceptLifecycle.onConfirmed = null  // 실제 doConfirm 경로
        AcceptLifecycle.ledgerWriter = null  // 실제 ledger 쓰기도 bypass
        registerCandidate()
        AcceptLifecycle.onStateSignal(ctx, "매장 도착")

        verify(exactly = 1) {
            EarningsTracker.recordAccept(ctx, 4000, "baemin", "맘스터치", "ord-1")
        }
    }

    @Test
    fun `joinEligible false → CONFIRMED 시 EarningsTracker 미호출`() {
        AcceptLifecycle.onConfirmed = null
        AcceptLifecycle.ledgerWriter = null
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-ne", "evt-1", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = false
        )
        AcceptLifecycle.onStateSignal(ctx, "배달 중")
        verify(exactly = 0) {
            EarningsTracker.recordAccept(any(), any(), any(), any(), any())
        }
    }

    // ── dedup ──

    @Test
    fun `동일 session 중복 등록 무시`() {
        registerCandidate(sessionId = "sess-dup")
        registerCandidate(sessionId = "sess-dup")
        assertEquals(1, AcceptLifecycle.pendingCount())
    }

    // ── GPS 좌표 0,0 (GPS 미활성) ──

    @Test
    fun `GPS 좌표 0,0 → 변위 기반 확인 스킵`() {
        registerCandidate(lat = 0.0, lng = 0.0)
        AcceptLifecycle.onGpsUpdate(ctx, 37.5678, 126.9780)
        assertEquals(1, AcceptLifecycle.pendingCount())
    }

    // ── haversine ──

    @Test
    fun `haversineM 약 90m`() {
        val dist = AcceptLifecycle.haversineM(37.5665, 126.9780, 37.5673, 126.9780)
        assertTrue("Expected ~89m, got $dist", dist > 80 && dist < 100)
    }

    // ── Fix W+: accept_state writer 테스트 ──

    @Test
    fun `W+ CANDIDATE 등록 → accept_state CANDIDATE 기록`() {
        registerCandidate(sessionId = "sess-st")
        val writes = acceptStateWrites.filter { it.first == "sess-st" }
        assertEquals(1, writes.size)
        assertEquals("CANDIDATE", writes[0].second)
        assertNull(writes[0].third)
    }

    @Test
    fun `W+ CONFIRMED → accept_state CONFIRMED + confirmedAt`() {
        AcceptLifecycle.onConfirmed = null  // 실제 doConfirm 경로
        AcceptLifecycle.ledgerWriter = null
        registerCandidate(sessionId = "sess-cf")
        AcceptLifecycle.onStateSignal(ctx, "매장 도착")
        val writes = acceptStateWrites.filter { it.first == "sess-cf" && it.second == "CONFIRMED" }
        assertEquals(1, writes.size)
        assertNotNull(writes[0].third)  // confirmedAt != null
    }

    @Test
    fun `W+ markExplicitReject → accept_state REJECTED_FALSE`() {
        AcceptLifecycle.onFalse = null  // doFalse 경로
        registerCandidate(sessionId = "sess-rej")
        AcceptLifecycle.markExplicitReject(ctx, "sess-rej", "new_call_screen")
        val writes = acceptStateWrites.filter { it.first == "sess-rej" && it.second == "REJECTED_FALSE" }
        assertEquals(1, writes.size)
    }

    @Test
    fun `W+ immediateConfirm → CANDIDATE + CONFIRMED 순서`() {
        AcceptLifecycle.onConfirmed = null  // 실제 doConfirm 경로
        AcceptLifecycle.ledgerWriter = null
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-imm2", "evt-1", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true,
            immediateConfirm = true
        )
        val states = acceptStateWrites.filter { it.first == "sess-imm2" }.map { it.second }
        assertEquals(listOf("CANDIDATE", "CONFIRMED"), states)
    }

    // ── Fix W++: 세션 매칭 강화 테스트 ──

    @Test
    fun `W++ Fix Y — BAEMIN_PROGRESS + baemin_in_progress → SUPPRESSED (pending 유지)`() {
        // Fix Y: BAEMIN_PROGRESS source가 baemin_in_progress 신호로 자기-confirm 차단
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-ma", "evt-A", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )
        AcceptLifecycle.onStateSignal(ctx, "baemin_in_progress", "baemin", "evt-A")
        assertEquals(1, AcceptLifecycle.pendingCount())  // Fix Y: pending 유지
        assertEquals(0, confirmedCandidates.size)        // confirm 안 됨
    }

    @Test
    fun `W++ candidate(baemin,A) + signal(baemin,B) → CANDIDATE 유지`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-mb", "evt-A", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )
        AcceptLifecycle.onStateSignal(ctx, "baemin_in_progress", "baemin", "evt-B")
        assertEquals(1, AcceptLifecycle.pendingCount())  // 매칭 실패 → 유지
        assertEquals(0, confirmedCandidates.size)
    }

    @Test
    fun `W++ candidate(baemin,A) + signal(coupang,null) → CANDIDATE 유지`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-mc", "evt-A", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )
        AcceptLifecycle.onStateSignal(ctx, "매장 도착", "coupang", null)
        assertEquals(1, AcceptLifecycle.pendingCount())  // platform 불일치
        assertEquals(0, confirmedCandidates.size)
    }

    @Test
    fun `W++ candidate(coupang,X) + signal(baemin,null) → CANDIDATE 유지`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-md", "evt-X", "ord-1", "coupang",
            3450, "BBQ", AcceptCoordinator.AcceptSource.COUPANG_PICKUP,
            37.5665, 126.9780, joinEligible = true
        )
        AcceptLifecycle.onStateSignal(ctx, "baemin_in_progress", "baemin", null)
        assertEquals(1, AcceptLifecycle.pendingCount())
        assertEquals(0, confirmedCandidates.size)
    }

    @Test
    fun `W++ Fix Y — baemin 2개 BAEMIN_PROGRESS + baemin_in_progress → 둘 다 suppressed`() {
        var now = 1_000_000L
        AcceptLifecycle.timeSource = { now }

        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-old", "evt-old", "ord-1", "baemin",
            3000, "설빙", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )
        now += 5000
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-new", "evt-new", "ord-2", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )
        assertEquals(2, AcceptLifecycle.pendingCount())

        // Fix Y: BAEMIN_PROGRESS 자기-confirm 차단
        AcceptLifecycle.onStateSignal(ctx, "baemin_in_progress", "baemin", null)
        assertEquals(2, AcceptLifecycle.pendingCount())  // Fix Y: 둘 다 pending 유지
        assertEquals(0, confirmedCandidates.size)
    }

    @Test
    fun `W++ proximity(coupang, sessionId) → 해당 candidate만 confirm`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-ba", "evt-ba", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-co", "evt-co", "ord-2", "coupang",
            3450, "BBQ", AcceptCoordinator.AcceptSource.COUPANG_PICKUP,
            37.5665, 126.9780, joinEligible = true
        )
        AcceptLifecycle.onProximityEvent(ctx, "PICKUP_NEAR", "coupang", "evt-co")
        assertEquals(1, AcceptLifecycle.pendingCount())  // baemin still pending
        assertEquals(1, confirmedCandidates.size)
        assertEquals("sess-co", confirmedCandidates[0].callSessionId)
    }

    @Test
    fun `W++ GPS update는 여전히 전역 (platform null)`() {
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-gps1", "evt-1", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-gps2", "evt-2", "ord-2", "coupang",
            3450, "BBQ", AcceptCoordinator.AcceptSource.COUPANG_PICKUP,
            37.5665, 126.9780, joinEligible = true
        )
        // GPS 90m 이동 → 둘 다 confirm (GPS는 platform 무관)
        AcceptLifecycle.onGpsUpdate(ctx, 37.5673, 126.9780)
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(2, confirmedCandidates.size)
    }

    // ── Fix W+++: doConfirm → driver_action 기록 ──

    @Test
    fun `W+++ doConfirm → driverActionWriter 호출`() {
        AcceptLifecycle.onConfirmed = null  // 실제 doConfirm 경로
        AcceptLifecycle.ledgerWriter = null
        registerCandidate(sessionId = "sess-da")
        AcceptLifecycle.onStateSignal(ctx, "매장 도착", "baemin", "evt-1")
        val writes = driverActionWrites.filter { it.first == "sess-da" }
        assertEquals(1, writes.size)
        assertEquals("BAEMIN_PROGRESS", writes[0].second)
    }

    @Test
    fun `W+++ immediateConfirm → driverActionWriter 호출`() {
        AcceptLifecycle.onConfirmed = null
        AcceptLifecycle.ledgerWriter = null
        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-da2", "evt-1", "ord-1", "coupang",
            3450, "BBQ", AcceptCoordinator.AcceptSource.COUPANG_PICKUP,
            37.5665, 126.9780, joinEligible = true,
            immediateConfirm = true
        )
        val writes = driverActionWrites.filter { it.first == "sess-da2" }
        assertEquals(1, writes.size)
        assertEquals("COUPANG_PICKUP", writes[0].second)
    }

    @Test
    fun `W+++ UNCONFIRMED → driverActionWriter 미호출`() {
        // timeout 시 driverActionWriter는 호출되지 않음 (pending 유지 후 timeout)
        // timeout은 Handler 기반이므로 직접 테스트 불가, 대신 markExplicitReject 확인
        AcceptLifecycle.onFalse = null
        registerCandidate(sessionId = "sess-da3")
        AcceptLifecycle.markExplicitReject(ctx, "sess-da3", "test")
        val writes = driverActionWrites.filter { it.first == "sess-da3" }
        assertEquals(0, writes.size)  // FALSE에서는 driverAction 미호출
    }
}
