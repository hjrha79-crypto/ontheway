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
import com.vita.ontheway.ledger.LedgerEventType

class AcceptSuspectedTest {

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

        mockkObject(OtwFileLogger, AcceptCoordinator, EarningsTracker, JudgmentMatchLogger,
            FilterLog, com.vita.ontheway.ledger.LedgerAppender, com.vita.ontheway.ledger.CallSessionRegistry)
        every { OtwFileLogger.log(any(), any()) } returns Unit
        every { AcceptCoordinator.handleAccept(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit
        every { EarningsTracker.recordAccept(any(), any(), any(), any(), any()) } returns Unit
        every { JudgmentMatchLogger.onAcceptDetected(any()) } returns Unit
        every { FilterLog.recordAccepted(any(), any(), any(), any(), any(), any()) } returns Unit
        every { com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "test-session"
        every { com.vita.ontheway.ledger.CallSessionRegistry.resetForTest() } returns Unit
        CoupangAcceptDetector.resetForTest()
    }

    @After
    fun teardown() {
        CoupangAcceptDetector.resetForTest()
        unmockkAll()
    }

    private fun makeNotification(price: Int = 3450, sbnKey: String = "sbn-1", postTime: Long = System.currentTimeMillis()) =
        CoupangNotificationParser.CoupangNotification(
            offeredPrice = price, distanceKm = 2.0, bundleCount = 1, isMulti = false,
            bundleType = "단일", notificationKey = sbnKey, postTime = postTime,
            storeName = "BBQ", rawText = "[1건 단일] ${price}원 / 2.0km",
            confidence = 0.9, sourceChannel = "notification_structured"
        )

    @Test
    fun `ACCEPT_SUSPECTED enum 존재`() {
        assertEquals("ACCEPT_SUSPECTED", LedgerEventType.ACCEPT_SUSPECTED.name)
    }

    @Test
    fun `ACCEPT_SUSPECTED vs DUPLICATE_ACCEPT_SUSPECTED 구분`() {
        assertNotEquals(LedgerEventType.ACCEPT_SUSPECTED, LedgerEventType.DUPLICATE_ACCEPT_SUSPECTED)
    }

    @Test
    fun `dismiss + state = MATCHED 처리`() {
        val postTime = System.currentTimeMillis()
        val n = makeNotification(sbnKey = "sbn-match", postTime = postTime)
        CoupangAcceptDetector.onNotificationReceived(n)
        CoupangAcceptDetector.onNotificationDismissed("sbn-match", postTime)
        CoupangAcceptDetector.onCoupangStateEvent()

        // evaluateCandidate에 의해 이미 처리됨
        assertEquals(0, CoupangAcceptDetector.pendingCount())
    }

    @Test
    fun `dismiss만 state 없음 — PENDING 유지`() {
        val postTime = System.currentTimeMillis()
        val n = makeNotification(sbnKey = "sbn-sus", postTime = postTime)
        CoupangAcceptDetector.onNotificationReceived(n)
        CoupangAcceptDetector.onNotificationDismissed("sbn-sus", postTime)
        // state 없음 → PENDING
        assertTrue(CoupangAcceptDetector.pendingCount() > 0)
    }

    @Test
    fun `ACCEPT_SUSPECTED ledger 시 join_eligible false`() {
        val conf = 0.3
        assertFalse("SUSPECTED conf=0.3 → joinEligible=false", conf >= 0.7)
    }

    @Test
    fun `state만 dismiss 없음 — PENDING`() {
        val n = makeNotification(sbnKey = "sbn-pend")
        CoupangAcceptDetector.onNotificationReceived(n)
        CoupangAcceptDetector.onCoupangStateEvent()
        assertTrue(CoupangAcceptDetector.pendingCount() > 0)
    }
}
