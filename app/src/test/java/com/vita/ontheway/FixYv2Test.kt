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
 * Fix Y v2: false ACCEPT 차단 강화 테스트.
 *
 * 1. CALL(17:52) + TIMEOUT(17:57) + ACCEPT(18:03) → STALE_PENDING_BLOCKED
 * 2. ORPHAN_CLASSIFIED → ACCEPT_CONFIRMED 발생 X
 * 3. 정상 케이스 (CALL + 1분 내 ACCEPT) → CONFIRMED 정상
 * 4. 기존 Fix Y 회귀 없음 (BAEMIN_PROGRESS 자기-confirm 차단)
 */
class FixYv2Test {

    private lateinit var ctx: Context
    private var ledgerEvents = mutableListOf<Pair<String, com.vita.ontheway.ledger.LedgerEventType>>()
    private var confirmedCandidates = mutableListOf<AcceptLifecycle.Candidate>()
    private var unconfirmedCandidates = mutableListOf<AcceptLifecycle.Candidate>()
    private var acceptStateWrites = mutableListOf<Triple<String, String, Long?>>()

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

        mockkObject(
            EarningsTracker, JudgmentMatchLogger, FilterLog, OtwFileLogger,
            com.vita.ontheway.ledger.LedgerAppender, com.vita.ontheway.ledger.CallSessionRegistry
        )
        every { EarningsTracker.recordAccept(any(), any(), any(), any(), any()) } returns Unit
        every { JudgmentMatchLogger.onAcceptDetected(any()) } returns Unit
        every { FilterLog.recordAccepted(any(), any(), any(), any(), any(), any()) } returns Unit
        every { FilterLog.getRecent(any(), any()) } returns emptyList()
        every { OtwFileLogger.log(any(), any()) } returns Unit
        every { com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit
        every { com.vita.ontheway.ledger.LedgerAppender.appendLifecycleSync(any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit

        AcceptCoordinator.resetForTest()
        AcceptLifecycle.resetForTest()
        JudgmentMatchLogger.resetForTest()
        ledgerEvents.clear()
        confirmedCandidates.clear()
        unconfirmedCandidates.clear()
        acceptStateWrites.clear()

        AcceptLifecycle.onConfirmed = { _, c -> confirmedCandidates.add(c) }
        AcceptLifecycle.onUnconfirmed = { _, c -> unconfirmedCandidates.add(c) }
        AcceptLifecycle.ledgerWriter = { _, sessionId, _, _, _, type, _, _ ->
            ledgerEvents.add(sessionId to type)
        }
        AcceptLifecycle.acceptStateWriter = { sessionId, state, confirmedAt ->
            acceptStateWrites.add(Triple(sessionId, state, confirmedAt))
        }
        AcceptLifecycle.driverActionWriter = { _, _, _ -> }
    }

    @After
    fun teardown() {
        AcceptCoordinator.resetForTest()
        AcceptLifecycle.resetForTest()
        JudgmentMatchLogger.resetForTest()
        unmockkAll()
    }

    // ── 1. CALL(17:52) + TIMEOUT(17:57) + ACCEPT(18:03) → STALE_PENDING_BLOCKED ──

    @Test
    fun `stale pending - CALL + 6m gap → ACCEPT 차단`() {
        val t1752 = 1_000_000L  // 17:52 CALL_DETECTED
        val t1803 = t1752 + 6 * 60 * 1000 + 9 * 1000  // 18:03 (6분 9초 후)

        var now = t1752
        AcceptCoordinator.timeSource = { now }

        // 17:52 CALL_DETECTED
        AcceptCoordinator.recordCallDetected("baemin", t1752)

        // 18:03 ACCEPT 시도 (6분 9초 후)
        now = t1803
        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4500, "baemin", storeName = "중국성", eventId = "evt-1"
        )

        // STALE_PENDING_BLOCKED → AcceptLifecycle에 candidate 등록 안 됨
        assertEquals(0, AcceptLifecycle.pendingCount())
        assertEquals(0, confirmedCandidates.size)
    }

    @Test
    fun `stale pending - ledger에 STALE_PENDING_BLOCKED 기록`() {
        val t1752 = 1_000_000L
        val t1803 = t1752 + 6 * 60 * 1000 + 9 * 1000

        var now = t1752
        AcceptCoordinator.timeSource = { now }
        AcceptCoordinator.recordCallDetected("baemin", t1752)

        now = t1803
        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4500, "baemin", storeName = "중국성", eventId = "evt-1"
        )

        // LedgerAppender.appendLifecycle should have been called with STALE_PENDING_BLOCKED
        io.mockk.verify(atLeast = 1) {
            com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                any(), any(), any(), any(), eq("baemin"),
                eq(com.vita.ontheway.ledger.LedgerEventType.STALE_PENDING_BLOCKED),
                eq("stale_guard"), any()
            )
        }
    }

    @Test
    fun `stale pending - 정확히 5분 → 통과`() {
        val t0 = 1_000_000L
        val t5min = t0 + 5 * 60 * 1000  // exactly 5min

        var now = t0
        AcceptCoordinator.timeSource = { now }
        AcceptCoordinator.recordCallDetected("baemin", t0)
        AcceptCoordinator.ledgerChecker = { _, _ -> false }
        AcceptCoordinator.ledgerSyncWriter = { _, _, _, _, _, _, _, _ -> }

        now = t5min
        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4500, "baemin", storeName = "중국성", eventId = "evt-1"
        )

        // 5분 = 300,000ms 정확히 → NOT stale (> 이므로 통과)
        // handleAccept → immediateConfirm → 즉시 CONFIRMED
        assertEquals(1, confirmedCandidates.size)
    }

    // ── 2. ORPHAN_CLASSIFIED → ACCEPT_CONFIRMED 발생 X ──

    @Test
    fun `orphan - no pending match → ORPHAN_ACCEPT (not CONFIRMED)`() {
        // JudgmentMatchLogger에 pending 없이 AcceptLifecycle candidate를 confirm 시도
        // → hasPendingMatch = false → ORPHAN_ACCEPT
        AcceptLifecycle.onConfirmed = null  // 실제 경로
        AcceptLifecycle.onUnconfirmed = null
        AcceptLifecycle.ledgerWriter = { _, sessionId, _, _, _, type, _, _ ->
            ledgerEvents.add(sessionId to type)
        }

        // JudgmentMatchLogger에 pending 등록하지 않음 (no_pending_match 상황)
        // hasPendingMatch mock — 실제 객체 사용 (pending 없으니 false 반환)

        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-orphan", "evt-orphan", "ord-1", "baemin",
            4500, "중국성", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )

        // GPS 90m 이동 → promoteConfirmed 시도 → ORPHAN 감지
        AcceptLifecycle.onGpsUpdate(ctx, 37.5673, 126.9780)

        // ACCEPT_CONFIRMED 없어야 함
        val confirmed = ledgerEvents.filter { it.second == com.vita.ontheway.ledger.LedgerEventType.ACCEPT_CONFIRMED }
        assertEquals("ACCEPT_CONFIRMED should not fire for orphan", 0, confirmed.size)

        // ORPHAN_ACCEPT 있어야 함
        val orphan = ledgerEvents.filter { it.second == com.vita.ontheway.ledger.LedgerEventType.ORPHAN_ACCEPT }
        assertEquals("ORPHAN_ACCEPT should fire", 1, orphan.size)

        // accept_state = ORPHAN_ACCEPT
        val orphanStates = acceptStateWrites.filter { it.second == "ORPHAN_ACCEPT" }
        assertEquals(1, orphanStates.size)
    }

    @Test
    fun `orphan - with pending match → CONFIRMED 정상`() {
        AcceptLifecycle.onConfirmed = null
        AcceptLifecycle.onUnconfirmed = null
        AcceptLifecycle.ledgerWriter = { _, sessionId, _, _, _, type, _, _ ->
            ledgerEvents.add(sessionId to type)
        }

        // JudgmentMatchLogger에 pending 등록
        JudgmentMatchLogger.onJudgmentIssued(
            ctx, "baemin", 4500, null, "중국성", "OK", null,
            sessionId = "evt-good", eventId = "evt-good"
        )

        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-good", "evt-good", "ord-1", "baemin",
            4500, "중국성", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )

        // GPS 90m → CONFIRMED
        AcceptLifecycle.onGpsUpdate(ctx, 37.5673, 126.9780)

        val confirmed = ledgerEvents.filter { it.second == com.vita.ontheway.ledger.LedgerEventType.ACCEPT_CONFIRMED }
        assertEquals("ACCEPT_CONFIRMED should fire for matched pending", 1, confirmed.size)

        val orphan = ledgerEvents.filter { it.second == com.vita.ontheway.ledger.LedgerEventType.ORPHAN_ACCEPT }
        assertEquals("ORPHAN_ACCEPT should not fire", 0, orphan.size)
    }

    // ── 3. 정상 케이스 (CALL + 1분 내 ACCEPT) → CONFIRMED 정상 ──

    @Test
    fun `normal - CALL + 30초 ACCEPT → 통과`() {
        val t0 = 1_000_000L
        val t30s = t0 + 30_000

        var now = t0
        AcceptCoordinator.timeSource = { now }
        AcceptCoordinator.recordCallDetected("baemin", t0)
        AcceptCoordinator.ledgerChecker = { _, _ -> false }
        AcceptCoordinator.ledgerSyncWriter = { _, _, _, _, _, _, _, _ -> }

        now = t30s
        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4500, "baemin", storeName = "맘스터치", eventId = "evt-1"
        )

        // 정상 통과 → AcceptLifecycle에 candidate 등록 (immediateConfirm이므로 즉시 CONFIRMED)
        assertEquals(1, confirmedCandidates.size)
    }

    @Test
    fun `normal - no prior CALL_DETECTED → 통과 (하위 호환)`() {
        // lastCallDetectedPerPlatform에 기록 없으면 차단하지 않음 (하위 호환)
        var now = 1_000_000L
        AcceptCoordinator.timeSource = { now }
        AcceptCoordinator.ledgerChecker = { _, _ -> false }
        AcceptCoordinator.ledgerSyncWriter = { _, _, _, _, _, _, _, _ -> }

        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4500, "baemin", storeName = "맘스터치", eventId = "evt-1"
        )

        assertEquals(1, confirmedCandidates.size)
    }

    // ── 4. 기존 Fix Y 회귀 없음 ──

    @Test
    fun `fixY regression - BAEMIN_PROGRESS + baemin_in_progress → suppressed`() {
        val t0 = 1_000_000L
        AcceptCoordinator.timeSource = { t0 }
        AcceptCoordinator.recordCallDetected("baemin", t0)

        // JudgmentMatchLogger에 pending 등록 (ORPHAN 방지)
        JudgmentMatchLogger.onJudgmentIssued(
            ctx, "baemin", 4000, null, "맘스터치", "OK", null,
            sessionId = "evt-A", eventId = "evt-A"
        )

        AcceptLifecycle.markAcceptCandidate(
            ctx, "sess-fy", "evt-A", "ord-1", "baemin",
            4000, "맘스터치", AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            37.5665, 126.9780, joinEligible = true
        )

        // Fix Y: BAEMIN_PROGRESS source가 baemin_in_progress 신호로 자기-confirm 차단
        AcceptLifecycle.onStateSignal(ctx, "baemin_in_progress", "baemin", "evt-A")
        assertEquals(1, AcceptLifecycle.pendingCount())
        assertEquals(0, confirmedCandidates.size)
    }

    @Test
    fun `stale pending - cross platform 무간섭`() {
        // baemin CALL_DETECTED 후 coupang ACCEPT → coupang에는 CALL_DETECTED 없으므로 통과
        val t0 = 1_000_000L
        var now = t0
        AcceptCoordinator.timeSource = { now }
        AcceptCoordinator.recordCallDetected("baemin", t0)
        AcceptCoordinator.ledgerChecker = { _, _ -> false }
        AcceptCoordinator.ledgerSyncWriter = { _, _, _, _, _, _, _, _ -> }

        now = t0 + 7 * 60 * 1000  // 7분 후
        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.COUPANG_PICKUP,
            3450, "coupang", storeName = "BBQ", eventId = "evt-c1"
        )

        // coupang에는 CALL_DETECTED 기록 없으므로 stale check 통과
        assertEquals(1, confirmedCandidates.size)
    }

    @Test
    fun `stale pending - recordAcceptCandidate도 차단`() {
        val t0 = 1_000_000L
        val t6min = t0 + 6 * 60 * 1000
        var now = t0
        AcceptCoordinator.timeSource = { now }
        AcceptCoordinator.recordCallDetected("coupang", t0)

        now = t6min
        AcceptCoordinator.recordAcceptCandidate(
            ctx, AcceptCoordinator.AcceptSource.COUPANG_PICKUP,
            3450, "coupang", storeName = "BBQ", eventId = "evt-c2"
        )

        assertEquals(0, AcceptLifecycle.pendingCount())
    }
}
