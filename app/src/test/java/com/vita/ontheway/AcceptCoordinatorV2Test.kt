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
 * P0-1 v2 AcceptCoordinator eventId/orderId 테스트.
 */
class AcceptCoordinatorV2Test {

    private lateinit var ctx: Context

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

        // 각 호출마다 고유 sessionId 반환
        var sessionCounter = 0
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } answers {
            "session-${sessionCounter++}"
        }
        every { com.vita.ontheway.ledger.CallSessionRegistry.resetForTest() } returns Unit

        AcceptCoordinator.resetForTest()
        AcceptLifecycle.resetForTest()
        AcceptLifecycle.autoConfirm = true
        // ledger에 ACCEPT 없음 (신규)
        AcceptCoordinator.ledgerChecker = { _, _ -> false }
        // 동기 writer 테스트 bypass
        AcceptCoordinator.ledgerSyncWriter = { _, _, _, _, _, _, _, _ -> }
    }

    @After
    fun teardown() {
        AcceptCoordinator.resetForTest()
        AcceptLifecycle.resetForTest()
        unmockkAll()
    }

    @Test
    fun `eventId 전달 시 FilterLog에 eventId 포함`() {
        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4000, "baemin", storeName = "맘스터치",
            eventId = "evt-123", orderId = "T2CN456"
        )
        verify {
            FilterLog.recordAccepted(ctx, 4000, "baemin", "evt-123", "T2CN456", "맘스터치")
        }
    }

    @Test
    fun `orderId 전달 시 FilterLog에 orderId 포함`() {
        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.COUPANG_PICKUP,
            3000, "coupang", orderId = "ORD-789"
        )
        verify {
            FilterLog.recordAccepted(ctx, 3000, "coupang", "", "ORD-789", "")
        }
    }

    @Test
    fun `store fallback — eventId orderId 빈값`() {
        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.FALLBACK,
            5000, "baemin", storeName = "설빙"
        )
        verify {
            FilterLog.recordAccepted(ctx, 5000, "baemin", "", "", "설빙")
        }
    }

    @Test
    fun `동일 sessionId 영구 중복 스킵`() {
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "same-session"

        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4000, "baemin", eventId = "evt-dup"
        )
        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.SYSTEM_NOTI,
            4000, "baemin", eventId = "evt-dup"
        )
        verify(exactly = 1) {
            FilterLog.recordAccepted(any(), 4000, "baemin", "evt-dup", any(), any())
        }
    }

    @Test
    fun `동일 orderId 영구 중복 스킵`() {
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "same-session-ord"

        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.COUPANG_PICKUP,
            3000, "coupang", orderId = "ORD-DUP"
        )
        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.FALLBACK,
            3000, "coupang", orderId = "ORD-DUP"
        )
        verify(exactly = 1) {
            FilterLog.recordAccepted(any(), 3000, "coupang", any(), "ORD-DUP", any())
        }
    }

    @Test
    fun `다른 eventId 는 중복 아님`() {
        var counter = 0
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } answers {
            "session-diff-${counter++}"
        }

        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            4000, "baemin", eventId = "evt-1"
        )
        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
            5000, "baemin", eventId = "evt-2"
        )
        verify(exactly = 2) {
            FilterLog.recordAccepted(any(), any(), "baemin", any(), any(), any())
        }
    }

    @Test
    fun `price 0 무시`() {
        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.FALLBACK, 0, "baemin"
        )
        verify(exactly = 0) {
            FilterLog.recordAccepted(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `EarningsTracker orderId 전달 확인`() {
        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.SYSTEM_NOTI,
            6000, "coupang", storeName = "BBQ", orderId = "ORD-999"
        )
        verify {
            EarningsTracker.recordAccept(ctx, 6000, "coupang", "BBQ", "ORD-999")
        }
    }

    @Test
    fun `EarningsTracker orderId blank 시 null 전달`() {
        AcceptCoordinator.handleAccept(
            ctx, AcceptCoordinator.AcceptSource.SYSTEM_NOTI,
            6000, "coupang", storeName = "BBQ", eventId = "evt-noti"
        )
        verify {
            EarningsTracker.recordAccept(ctx, 6000, "coupang", "BBQ", null)
        }
    }

    @Test
    fun `RecentCall 데이터 클래스 필드 확인`() {
        val rc = AcceptCoordinator.RecentCall(
            price = 4000, platform = "baemin",
            eventId = "evt-1", orderId = "T2CN-1",
            storeName = "맘스터치"
        )
        assertEquals(4000, rc.price)
        assertEquals("baemin", rc.platform)
        assertEquals("evt-1", rc.eventId)
        assertEquals("T2CN-1", rc.orderId)
        assertEquals("맘스터치", rc.storeName)
    }
}
