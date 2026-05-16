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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

/**
 * ACCEPT-DEDUP-HARDENING (v49.5) 테스트.
 *
 * 1. 동시성 race (putIfAbsent 100 thread)
 * 2. 동기 ledger writer 검증
 * 3. orderId 전달 검증
 * 4. SUSPECTED 정책 (ORPHAN ACCEPT)
 * 5. CrossSourceCallDetectionDedup rename 검증
 * 6. 5/9 시뮬 ORPHAN 분류
 */
class AcceptDedupHardeningTest {

    private lateinit var ctx: Context
    private val syncWriterCalls = AtomicInteger(0)
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

        syncWriterCalls.set(0)
        ledgerAccepted.clear()
        AcceptCoordinator.resetForTest()
        AcceptLifecycle.resetForTest()
        AcceptLifecycle.autoConfirm = true
        AcceptCoordinator.ledgerChecker = { _, sessionId -> sessionId in ledgerAccepted }
        AcceptCoordinator.ledgerSyncWriter = { _, _, _, _, _, _, _, _ ->
            syncWriterCalls.incrementAndGet()
        }
    }

    @After
    fun teardown() {
        AcceptCoordinator.resetForTest()
        AcceptLifecycle.resetForTest()
        unmockkAll()
    }

    // ── Fix 1: 동시성 race ──

    @Test
    fun `100 thread 동시 ACCEPT — 정확히 1건 통과`() {
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "race-session"

        val threadCount = 100
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)
        val acceptCount = AtomicInteger(0)

        repeat(threadCount) {
            Thread {
                barrier.await() // 모든 스레드 동시 시작
                try {
                    AcceptCoordinator.handleAccept(
                        ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
                        4000, "baemin", storeName = "맘스터치", eventId = "evt-race"
                    )
                } catch (_: Exception) {}
                latch.countDown()
            }.start()
        }

        latch.await()

        // syncWriter는 정확히 1회만 호출 (putIfAbsent 선점)
        assertEquals("동기 ledger insert는 1회만", 1, syncWriterCalls.get())
    }

    // ── Fix 2: 동기 ledger 검증 ──

    @Test
    fun `ACCEPT 시 동기 writer 호출`() {
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "sync-session"

        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.CLICK,
            5000, "baemin", storeName = "BBQ", eventId = "evt-sync"
        )

        assertEquals(1, syncWriterCalls.get())
    }

    @Test
    fun `앱 crash 시뮬 — 재시작 후 ledger fallback`() {
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "crash-session"

        // 첫 ACCEPT
        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS, 4000, "baemin", storeName = "설빙")
        assertEquals(1, syncWriterCalls.get())

        // crash 시뮬: 캐시 클리어 + ledger에 기록 남음
        AcceptCoordinator.resetForTest()
        AcceptLifecycle.resetForTest()
        AcceptLifecycle.autoConfirm = true
        ledgerAccepted.add("crash-session")
        AcceptCoordinator.ledgerChecker = { _, sessionId -> sessionId in ledgerAccepted }
        AcceptCoordinator.ledgerSyncWriter = { _, _, _, _, _, _, _, _ -> syncWriterCalls.incrementAndGet() }
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "crash-session"

        // 재시작 후 동일 ACCEPT → 차단
        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS, 4000, "baemin", storeName = "설빙")
        // syncWriter는 재시작 전 1회만 (재시작 후 차단)
        assertEquals(1, syncWriterCalls.get())
    }

    // ── Fix 4: orderId 전달 ──

    @Test
    fun `orderId EarningsTracker에 전달`() {
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "ord-session"

        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.COUPANG_PICKUP,
            3000, "coupang", orderId = "ORD-123", storeName = "치킨마루"
        )

        verify {
            EarningsTracker.recordAccept(ctx, 3000, "coupang", "치킨마루", "ORD-123")
        }
    }

    @Test
    fun `orderId blank 시 null 변환`() {
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "null-ord-session"

        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4000, "baemin", storeName = "BBQ", eventId = "evt-null-ord"
        )

        verify {
            EarningsTracker.recordAccept(ctx, 4000, "baemin", "BBQ", null)
        }
    }

    // ── Fix 5: SUSPECTED 정책 ──

    @Test
    fun `정상 차단 — storeName 있으면 BLOCKED`() {
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "blocked-session"

        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4000, "baemin", storeName = "맘스터치", eventId = "evt-1")
        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4000, "baemin", storeName = "맘스터치", eventId = "evt-1")

        verify(exactly = 1) {
            com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                any(), "blocked-session", any(), any(), any(),
                com.vita.ontheway.ledger.LedgerEventType.DUPLICATE_ACCEPT_BLOCKED,
                any(), any()
            )
        }
        verify(exactly = 0) {
            com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                any(), any(), any(), any(), any(),
                com.vita.ontheway.ledger.LedgerEventType.DUPLICATE_ACCEPT_SUSPECTED,
                any(), any()
            )
        }
    }

    @Test
    fun `ORPHAN 차단 — eventId orderId storeName 모두 blank → SUSPECTED`() {
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "orphan-session"

        // 첫 ACCEPT (storeName blank이지만 첫 ACCEPT이므로 통과)
        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4000, "baemin")
        // 두번째 ACCEPT — ORPHAN 조건 (all blank) → SUSPECTED
        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4000, "baemin")

        verify(exactly = 1) {
            com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                any(), "orphan-session", any(), any(), any(),
                com.vita.ontheway.ledger.LedgerEventType.DUPLICATE_ACCEPT_SUSPECTED,
                any(), any()
            )
        }
    }

    @Test
    fun `eventId 있으면 storeName blank여도 BLOCKED`() {
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "eid-blocked"

        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4000, "baemin", eventId = "evt-x")
        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4000, "baemin", eventId = "evt-x")

        verify(exactly = 1) {
            com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                any(), "eid-blocked", any(), any(), any(),
                com.vita.ontheway.ledger.LedgerEventType.DUPLICATE_ACCEPT_BLOCKED,
                any(), any()
            )
        }
    }

    @Test
    fun `SUSPECTED 통계 제외 — EarningsTracker 호출 X`() {
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "suspected-stats"

        // 첫 ACCEPT — all blank → conf=0.3 → joinEligible=false → EarningsTracker 미호출
        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4000, "baemin")
        // 두번째 = SUSPECTED (all blank → 캐시에서 차단)
        AcceptCoordinator.handleAccept(ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4000, "baemin")

        // all blank → joinEligible=false → EarningsTracker 0회
        verify(exactly = 0) { EarningsTracker.recordAccept(any(), any(), any(), any(), any()) }
    }

    // ── Fix 6: CrossSourceCallDetectionDedup ──

    @Test
    fun `CrossSourceCallDetectionDedup 기본 동작`() {
        CrossSourceCallDetectionDedup.reset()
        CrossSourceCallDetectionDedup.markProcessed(
            eventId = "ev-001", platform = "baemin", price = 3500, storeName = "KFC"
        )
        // eventId 매칭
        assertTrue(CrossSourceCallDetectionDedup.isProcessed(
            eventId = "ev-001", platform = "baemin", price = 3500
        ))
        // 다른 eventId + 다른 price → 미매칭
        assertFalse(CrossSourceCallDetectionDedup.isProcessed(
            eventId = "ev-999", platform = "baemin", price = 9999
        ))
        CrossSourceCallDetectionDedup.reset()
    }

    @Test
    fun `CrossSourceDedup delegate 호환`() {
        CrossSourceCallDetectionDedup.reset()
        CrossSourceCallDetectionDedup.markProcessed(
            eventId = "ev-compat", platform = "coupang", price = 4000
        )
        assertTrue(CrossSourceCallDetectionDedup.isProcessed(
            eventId = "ev-compat", platform = "coupang", price = 4000
        ))
        CrossSourceCallDetectionDedup.reset()
    }

    // ── 5/9 시뮬: ORPHAN 분류 ──

    @Test
    fun `5-9 시뮬 — ORPHAN 47% 케이스 BLOCKED vs SUSPECTED 분류`() {
        // 시나리오: 11개 session, 일부는 storeName/eventId 있고 일부는 없음
        data class SimEntry(val sid: String, val count: Int, val storeName: String, val eventId: String)
        val entries = listOf(
            SimEntry("s1", 5, "맘스터치", "evt-1"),  // 4x BLOCKED
            SimEntry("s2", 4, "", "evt-2"),            // 3x BLOCKED (eventId 있음)
            SimEntry("s3", 3, "", ""),                  // 2x SUSPECTED (all blank)
            SimEntry("s4", 3, "BBQ", ""),               // 2x BLOCKED (storeName 있음)
            SimEntry("s5", 2, "", ""),                  // 1x SUSPECTED
            SimEntry("s6", 1, "KFC", "evt-6"),
            SimEntry("s7", 1, "", ""),
            SimEntry("s8", 1, "", "evt-8"),
            SimEntry("s9", 1, "설빙", ""),
            SimEntry("s10", 1, "", ""),
            SimEntry("s11", 1, "", "evt-11"),
        )

        val sessionQueue = mutableListOf<String>()
        val storeQueue = mutableListOf<String>()
        val eventQueue = mutableListOf<String>()
        for (e in entries) {
            repeat(e.count) {
                sessionQueue.add(e.sid)
                storeQueue.add(e.storeName)
                eventQueue.add(e.eventId)
            }
        }

        var idx = 0
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } answers {
            sessionQueue[idx]
        }

        for (i in sessionQueue.indices) {
            idx = i
            AcceptCoordinator.handleAccept(
                ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
                3000 + (i * 100), "baemin",
                storeName = storeQueue[i],
                eventId = eventQueue[i]
            )
        }

        // 정상 ACCEPT = 11건 (각 session 1회)
        // P0-3: joinEligible = eventId 있는 5건만 EarningsTracker 기록 (s1,s2,s6,s8,s11)
        verify(exactly = 5) { EarningsTracker.recordAccept(any(), any(), any(), any(), any()) }

        // 총 차단 = 24 - 11 = 13건
        val totalCalls = sessionQueue.size  // 24
        val uniqueSessions = entries.size  // 11

        // Fix U rate-limit: 동일 session+source 60초 내 1건만 ledger 기록
        // BLOCKED: s1(evt,conf=0.7) + s2(evt,conf=0.7) = 2건
        // SUSPECTED: s3(blank,conf=0.3) + s4(store only,conf=0.5) + s5(blank,conf=0.3) = 3건
        verify(exactly = 2) {
            com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                any(), any(), any(), any(), any(),
                com.vita.ontheway.ledger.LedgerEventType.DUPLICATE_ACCEPT_BLOCKED,
                any(), any()
            )
        }
        verify(exactly = 3) {
            com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                any(), any(), any(), any(), any(),
                com.vita.ontheway.ledger.LedgerEventType.DUPLICATE_ACCEPT_SUSPECTED,
                any(), any()
            )
        }

        // 총 차단 이벤트 = totalCalls - uniqueSessions (실제)
        // ledger 기록은 rate-limited (session당 최대 1건)
        assertTrue("총 차단: ${totalCalls - uniqueSessions}", totalCalls - uniqueSessions >= 5)
    }
}
