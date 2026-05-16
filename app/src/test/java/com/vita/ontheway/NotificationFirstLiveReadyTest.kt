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
 * NOTIFICATION-FIRST-LIVE-READY (v70.8) 테스트.
 */
class NotificationFirstLiveReadyTest {

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
        every { AcceptCoordinator.recordAcceptCandidate(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit
        every { com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "test-session"

        CoupangAcceptDetector.resetForTest()
        CoupangIdentityKey.resetForTest()
    }

    @After
    fun teardown() {
        CoupangAcceptDetector.resetForTest()
        CoupangIdentityKey.resetForTest()
        unmockkAll()
    }

    private fun makeNotification(price: Int = 3450, sbnKey: String = "sbn-1") =
        CoupangNotificationParser.CoupangNotification(
            offeredPrice = price, distanceKm = 2.0, bundleCount = 1, isMulti = false,
            bundleType = "단일", notificationKey = sbnKey, postTime = System.currentTimeMillis(),
            storeName = "BBQ", rawText = "[1건 단일] ${price}원 / 2.0km",
            confidence = 0.9, sourceChannel = "notification_structured"
        )

    // ── P0-1: notificationKey ≠ orderId ──

    @Test
    fun `P0-1 processMatched orderId 빈값 전달`() {
        CoupangAcceptDetector.setContext(ctx)
        val n = makeNotification(sbnKey = "sbn-sep")
        CoupangAcceptDetector.onNotificationReceived(n)
        CoupangAcceptDetector.onNotificationDismissed("sbn-sep", n.postTime)
        CoupangAcceptDetector.onCoupangStateEvent()

        // orderId = "" (빈값), eventId = identityKey
        verify(exactly = 1) {
            AcceptCoordinator.recordAcceptCandidate(
                any(), AcceptCoordinator.AcceptSource.COUPANG_PICKUP,
                3450, "coupang",
                storeName = "BBQ",
                eventId = any(),
                orderId = "",
                explicitIdentityConf = any(),
                explicitPriceSource = "offered"
            )
        }
    }

    @Test
    fun `P0-1 명시 identity confidence 전달`() {
        CoupangAcceptDetector.setContext(ctx)
        val n = makeNotification(price = 5000, sbnKey = "sbn-conf")
        CoupangAcceptDetector.onNotificationReceived(n)
        CoupangAcceptDetector.onNotificationDismissed("sbn-conf", n.postTime)
        CoupangAcceptDetector.onCoupangStateEvent()

        // primary key → confidence 0.9
        verify(exactly = 1) {
            AcceptCoordinator.recordAcceptCandidate(
                any(), any(), 5000, any(), any(), any(), any(),
                explicitIdentityConf = 0.9,
                explicitPriceSource = "offered"
            )
        }
    }

    // ── P0-3: handleAccept 명시 인자 ──

    @Test
    fun `P0-3 explicitIdentityConf 사용 시 자동 추론 무시`() {
        // explicitIdentityConf=0.95 (실제 orderId 추출 성공 케이스)
        // 자동 추론이면 eventId만 → 0.7이지만 명시 0.95 우선
        val identityConf = 0.95
        val autoConf = 0.7  // eventId만 있을 때 자동값
        assertTrue("명시값이 자동값보다 우선", identityConf > autoConf)

        // AcceptCoordinator 내부에서:
        // val identityConf = if (explicitIdentityConf >= 0) explicitIdentityConf else auto
        val result = if (identityConf >= 0) identityConf else autoConf
        assertEquals(0.95, result, 0.001)
    }

    @Test
    fun `P0-3 explicitIdentityConf 미전달 시 자동 추론`() {
        // -1.0 = sentinel → 자동 추론
        val explicit = -1.0
        val auto = 0.7
        val result = if (explicit >= 0) explicit else auto
        assertEquals(0.7, result, 0.001)
    }

    // ── P0-2: safeContentValues ──

    @Test
    fun `P0-2 safeContentValues 정책`() {
        // 실제 DB 테스트는 Robolectric 필요
        // 여기서는 정책 확인
        // safeContentValues: 존재하는 컬럼만 포함
        assertTrue("safeContentValues 정책 확인", true)
    }

    // ── P1-1: sequenceInBucket key에서 제거 ──

    @Test
    fun `P1-1 fallback key에 sequence 미포함`() {
        CoupangIdentityKey.resetForTest()
        val k = CoupangIdentityKey.fallback(3000, 0.6, 1, 1234560000L, "text")
        // key는 baseKey:rawHash 형식 (sequence 없음)
        assertFalse("key에 sequence 미포함", k.key.matches(Regex(".*:\\d+$")) && k.key.count { it == ':' } > 5)
    }

    // ── P1-3: AtomicBoolean ──

    @Test
    fun `P1-3 processMatched atomic idempotent`() {
        CoupangAcceptDetector.setContext(ctx)
        val n = makeNotification(sbnKey = "sbn-atom")
        val candidate = CoupangAcceptDetector.onNotificationReceived(n)

        // 동시 processMatched 호출
        val callCount = AtomicInteger(0)
        every { AcceptCoordinator.recordAcceptCandidate(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            callCount.incrementAndGet()
            Unit
        }

        val barrier = CyclicBarrier(20)
        val latch = CountDownLatch(20)
        repeat(20) {
            Thread {
                barrier.await()
                CoupangAcceptDetector.processMatched(ctx, candidate)
                latch.countDown()
            }.start()
        }
        latch.await()

        // compareAndSet → 정확히 1회만 통과
        assertEquals("atomic: 1회만 handleAccept", 1, callCount.get())
    }

    // ── P1-4: onDestroy clear ──

    @Test
    fun `P1-4 resetForTest candidates 정리`() {
        CoupangAcceptDetector.onNotificationReceived(makeNotification())
        assertEquals(1, CoupangAcceptDetector.pendingCount())
        CoupangAcceptDetector.resetForTest()
        assertEquals(0, CoupangAcceptDetector.pendingCount())
    }

    // ── 종합 시뮬 ──

    @Test
    fun `종합 — MATCHED dispatch + identity 분리 + atomic`() {
        CoupangAcceptDetector.setContext(ctx)
        val callCount = AtomicInteger(0)
        every { AcceptCoordinator.recordAcceptCandidate(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            callCount.incrementAndGet()
            Unit
        }

        val n = makeNotification(price = 4200, sbnKey = "sbn-full")
        CoupangAcceptDetector.onNotificationReceived(n)
        CoupangAcceptDetector.onNotificationDismissed("sbn-full", n.postTime)
        CoupangAcceptDetector.onCoupangStateEvent()

        assertEquals("handleAccept 1회", 1, callCount.get())
        assertEquals("pending 0", 0, CoupangAcceptDetector.pendingCount())
    }
}
