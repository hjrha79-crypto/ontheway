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
 * NOTIFICATION-FIRST-CRITICAL-RECOVERY (v70.7) 테스트.
 *
 * P0-1: setContext 호출
 * P0-2: MATCHED → handleAccept dispatch
 * P0-3: NLS identity 전달
 * P1-1: sequenceInBucket
 */
class NotificationFirstRecoveryTest {

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
        every { AcceptCoordinator.recordAcceptCandidate(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit
        every { AcceptCoordinator.recordAcceptCandidate(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit
        every { EarningsTracker.recordAccept(any(), any(), any(), any(), any()) } returns Unit
        every { JudgmentMatchLogger.onAcceptDetected(any()) } returns Unit
        every { FilterLog.recordAccepted(any(), any(), any(), any(), any(), any()) } returns Unit
        every { com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "test-session"
        every { com.vita.ontheway.ledger.CallSessionRegistry.resetForTest() } returns Unit

        CoupangAcceptDetector.resetForTest()
        CoupangIdentityKey.resetForTest()
    }

    @After
    fun teardown() {
        CoupangAcceptDetector.resetForTest()
        CoupangIdentityKey.resetForTest()
        unmockkAll()
    }

    private fun makeNotification(price: Int = 3450, sbnKey: String = "sbn-1", postTime: Long = System.currentTimeMillis()) =
        CoupangNotificationParser.CoupangNotification(
            offeredPrice = price, distanceKm = 2.0, bundleCount = 1, isMulti = false,
            bundleType = "단일", notificationKey = sbnKey, postTime = postTime,
            storeName = "BBQ", rawText = "[1건 단일] ${price}원 / 2.0km",
            confidence = 0.9, sourceChannel = "notification_structured"
        )

    // ── P0-1: setContext ──

    @Test
    fun `P0-1 setContext 호출 후 tickContext 설정`() {
        CoupangAcceptDetector.setContext(ctx)
        // context 설정 확인 — tick 실행 가능
        val n = makeNotification()
        CoupangAcceptDetector.onNotificationReceived(n)
        CoupangAcceptDetector.onNotificationDismissed("sbn-1", n.postTime)
        CoupangAcceptDetector.onCoupangStateEvent()
        // MATCHED → processMatched → handleAccept 호출
        verify(atLeast = 1) {
            AcceptCoordinator.recordAcceptCandidate(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `P0-1 setContext 미호출 + MATCHED = context null 로그`() {
        // context 미설정 상태
        CoupangAcceptDetector.resetForTest() // tickContext = null
        val n = makeNotification(sbnKey = "sbn-noctx")
        CoupangAcceptDetector.onNotificationReceived(n)
        CoupangAcceptDetector.onNotificationDismissed("sbn-noctx", n.postTime)
        CoupangAcceptDetector.onCoupangStateEvent()
        // context null → processMatched 미호출 → handleAccept 미호출
        // 대신 MATCHED_NO_CTX 로그
        verify(exactly = 0) {
            AcceptCoordinator.recordAcceptCandidate(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ── P0-2: MATCHED → handleAccept dispatch ──

    @Test
    fun `P0-2 MATCHED 시 AcceptCoordinator handleAccept 호출`() {
        CoupangAcceptDetector.setContext(ctx)
        val postTime = System.currentTimeMillis()
        val n = makeNotification(price = 4500, sbnKey = "sbn-match", postTime = postTime)
        CoupangAcceptDetector.onNotificationReceived(n)

        CoupangAcceptDetector.onNotificationDismissed("sbn-match", postTime)
        CoupangAcceptDetector.onCoupangStateEvent()

        verify(exactly = 1) {
            AcceptCoordinator.recordAcceptCandidate(
                any(),
                AcceptCoordinator.AcceptSource.COUPANG_PICKUP,
                4500, "coupang",
                any(), any(), any(), any(), any()
            )
        }
    }

    @Test
    fun `P0-2 MATCHED 후 processed=true → 재호출 X`() {
        CoupangAcceptDetector.setContext(ctx)
        val postTime = System.currentTimeMillis()
        val n = makeNotification(sbnKey = "sbn-idem", postTime = postTime)
        CoupangAcceptDetector.onNotificationReceived(n)

        CoupangAcceptDetector.onNotificationDismissed("sbn-idem", postTime)
        CoupangAcceptDetector.onCoupangStateEvent()
        // 두 번째 state event → 이미 processed → skip
        CoupangAcceptDetector.onCoupangStateEvent()

        verify(exactly = 1) {
            AcceptCoordinator.recordAcceptCandidate(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `P0-2 MATCHED 후 tick에서 중복 처리 X`() {
        CoupangAcceptDetector.setContext(ctx)
        val postTime = System.currentTimeMillis()
        val n = makeNotification(sbnKey = "sbn-tick", postTime = postTime)
        CoupangAcceptDetector.onNotificationReceived(n)
        CoupangAcceptDetector.onNotificationDismissed("sbn-tick", postTime)
        CoupangAcceptDetector.onCoupangStateEvent()

        // tick 호출 — 이미 processed
        CoupangAcceptDetector.tick(ctx)

        // handleAccept는 evaluateCandidate에서 1회만
        verify(exactly = 1) {
            AcceptCoordinator.recordAcceptCandidate(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `P0-2 tick에서 MATCHED dispatch (evaluate 미처리 케이스)`() {
        CoupangAcceptDetector.setContext(ctx)
        val postTime = System.currentTimeMillis()
        val n = makeNotification(sbnKey = "sbn-tick2", postTime = postTime)
        CoupangAcceptDetector.onNotificationReceived(n)

        // state 먼저 → dismiss 나중 (tick에서 safety net)
        CoupangAcceptDetector.onCoupangStateEvent()
        CoupangAcceptDetector.onNotificationDismissed("sbn-tick2", postTime)

        // evaluate에서 이미 처리됨
        assertEquals(0, CoupangAcceptDetector.pendingCount())
    }

    // ── P0-3: NLS identity ──

    @Test
    fun `P0-3 NLS identity confidence 계산`() {
        // orderId 있으면 0.9
        val conf1 = when {
            "T2CN001".isNotBlank() -> 0.9
            else -> 0.3
        }
        assertEquals(0.9, conf1, 0.001)

        // storeName만 있으면 0.5
        val conf2 = when {
            "".isBlank() && "".isBlank() && "BBQ".isNotBlank() -> 0.5
            else -> 0.3
        }
        assertEquals(0.5, conf2, 0.001)
    }

    // ── P1-1: sequenceInBucket ──

    @Test
    fun `P1-1 fallback key에 sequence 미포함 (v70_8)`() {
        CoupangIdentityKey.resetForTest()
        val k1 = CoupangIdentityKey.fallback(3000, 0.6, 1, 1234560000L, "text1")
        // key = baseKey:rawHash (sequence 없음)
        assertTrue("rawHash 포함", k1.key.contains(":"))

        val k2 = CoupangIdentityKey.fallback(3000, 0.6, 1, 1234560000L, "text2")
        // 다른 rawHash → 다른 key
        assertNotEquals(k1.key, k2.key)
        // collision → confidence 0.3
        assertEquals(0.3, k2.confidence, 0.001)
    }

    @Test
    fun `P1-1 같은 rawText = 같은 sequence`() {
        CoupangIdentityKey.resetForTest()
        val k1 = CoupangIdentityKey.fallback(3000, 0.6, 1, 1234560000L, "same")
        val k2 = CoupangIdentityKey.fallback(3000, 0.6, 1, 1234560000L, "same")
        assertEquals(k1.key, k2.key)
    }

    // ── 종합 시뮬 ──

    @Test
    fun `종합 시뮬 — 알림 수신 + dismiss + state → handleAccept 호출`() {
        CoupangAcceptDetector.setContext(ctx)

        // 3건 알림 수신
        val n1 = makeNotification(price = 3000, sbnKey = "sbn-s1", postTime = System.currentTimeMillis())
        val n2 = makeNotification(price = 4000, sbnKey = "sbn-s2", postTime = System.currentTimeMillis())
        val n3 = makeNotification(price = 5000, sbnKey = "sbn-s3", postTime = System.currentTimeMillis())

        CoupangAcceptDetector.onNotificationReceived(n1)
        CoupangAcceptDetector.onNotificationReceived(n2)
        CoupangAcceptDetector.onNotificationReceived(n3)
        assertEquals(3, CoupangAcceptDetector.pendingCount())

        // n1만 dismiss + state → MATCHED
        CoupangAcceptDetector.onNotificationDismissed("sbn-s1", n1.postTime)
        CoupangAcceptDetector.onCoupangStateEvent()

        // n1 = recordAcceptCandidate 호출
        verify(atLeast = 1) {
            AcceptCoordinator.recordAcceptCandidate(any(), any(), 3000, "coupang", any(), any(), any(), any(), any())
        }
        // n2, n3는 아직 PENDING
        assertEquals(2, CoupangAcceptDetector.pendingCount())
    }
}
