package com.vita.ontheway

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * FIX-ACCEPT-LEDGER-PERMANENT-DEDUP + HARDENING 테스트.
 */
class AcceptPermanentDedupTest {

    private lateinit var ctx: Context
    private val ledgerAccepted = mutableSetOf<String>()

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
        every { FilterLog.recordAccepted(any(), any(), any(), any(), any(), any()) } returns Unit
        every { OtwFileLogger.log(any(), any()) } returns Unit
        every { com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit
        every { com.vita.ontheway.ledger.CallSessionRegistry.resetForTest() } returns Unit

        ledgerAccepted.clear()
        AcceptCoordinator.resetForTest()
        AcceptLifecycle.resetForTest()
        AcceptLifecycle.autoConfirm = true
        AcceptCoordinator.ledgerChecker = { _, sessionId -> sessionId in ledgerAccepted }
        AcceptCoordinator.ledgerSyncWriter = { _, _, _, _, _, _, _, _ -> }
    }

    @After
    fun teardown() {
        AcceptCoordinator.resetForTest()
        AcceptLifecycle.resetForTest()
        unmockkAll()
    }

    @Test
    fun `b2bf7d84 시뮬 — 같은 sessionId 5번 ACCEPT`() {
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "b2bf7d84"

        repeat(5) {
            AcceptCoordinator.handleAccept(
                ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
                4000, "baemin", storeName = "맘스터치", eventId = "evt-b2bf"
            )
        }

        verify(exactly = 1) { EarningsTracker.recordAccept(any(), 4000, "baemin", "맘스터치", any()) }
        verify(exactly = 1) { FilterLog.recordAccepted(any(), 4000, "baemin", any(), any(), any()) }
        // Fix U: rate-limit → 동일 key 60초 내 1건만 ledger 기록
        verify(exactly = 1) {
            com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                any(), "b2bf7d84", any(), any(), any(),
                com.vita.ontheway.ledger.LedgerEventType.DUPLICATE_ACCEPT_BLOCKED,
                any(), any()
            )
        }
    }

    @Test
    fun `다른 sessionId ACCEPT는 각각 정상`() {
        var counter = 0
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } answers {
            "session-${counter++}"
        }

        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS, 3000, "baemin", eventId = "evt-a")
        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.COUPANG_PICKUP, 5000, "coupang", eventId = "evt-b")
        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.CLICK, 4000, "baemin", eventId = "evt-c")

        verify(exactly = 3) { EarningsTracker.recordAccept(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `모든 source 타입에 dedup 적용`() {
        val sources = AcceptCoordinator.AcceptSource.entries
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "all-source-session"

        for (source in sources) {
            AcceptCoordinator.handleAccept(ctx, source, 3500, "baemin", eventId = "evt-all", storeName = "BBQ")
        }

        verify(exactly = 1) { EarningsTracker.recordAccept(any(), 3500, "baemin", any(), any()) }
    }

    @Test
    fun `앱 재시작 후 같은 sessionId 재시도 — ledger 쿼리로 차단`() {
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "restart-session"

        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS, 4500, "baemin",
            storeName = "BBQ", eventId = "evt-restart")
        verify(exactly = 1) { EarningsTracker.recordAccept(any(), 4500, any(), any(), any()) }

        // 앱 재시작 시뮬
        AcceptCoordinator.resetForTest()
        AcceptLifecycle.resetForTest()
        AcceptLifecycle.autoConfirm = true
        ledgerAccepted.add("restart-session")
        AcceptCoordinator.ledgerChecker = { _, sessionId -> sessionId in ledgerAccepted }
        AcceptCoordinator.ledgerSyncWriter = { _, _, _, _, _, _, _, _ -> }
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "restart-session"

        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS, 4500, "baemin",
            storeName = "BBQ", eventId = "evt-restart")
        verify(exactly = 1) { EarningsTracker.recordAccept(any(), 4500, any(), any(), any()) }
    }

    @Test
    fun `CLICK source 정상 처리`() {
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "click-session"

        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.CLICK, 5000, "baemin",
            storeName = "BBQ", eventId = "evt-click")
        verify(exactly = 1) { EarningsTracker.recordAccept(any(), 5000, "baemin", "BBQ", any()) }
    }

    @Test
    fun `FALLBACK source — joinEligible false 통계 미기록`() {
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "fallback-session"

        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.FALLBACK, 3000, "coupang")
        // FALLBACK + no ID → conf=0.3 → joinEligible=false → EarningsTracker 미호출
        verify(exactly = 0) { EarningsTracker.recordAccept(any(), any(), any(), any(), any()) }
    }
}
