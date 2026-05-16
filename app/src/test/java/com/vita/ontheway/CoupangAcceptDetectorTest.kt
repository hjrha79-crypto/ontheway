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

class CoupangAcceptDetectorTest {

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

    private fun makeNotification(price: Int = 3450, sbnKey: String = "sbn-1", postTime: Long = System.currentTimeMillis()) =
        CoupangNotificationParser.CoupangNotification(
            offeredPrice = price, distanceKm = 2.0, bundleCount = 1, isMulti = false,
            bundleType = "단일", notificationKey = sbnKey, postTime = postTime,
            storeName = "BBQ", rawText = "[1건 단일] ${price}원 / 2.0km",
            confidence = 0.9, sourceChannel = "notification_structured"
        )

    @Test
    fun `candidate 생성`() {
        val c = CoupangAcceptDetector.onNotificationReceived(makeNotification())
        assertEquals(3450, c.notification.offeredPrice)
        assertEquals(1, CoupangAcceptDetector.pendingCount())
    }

    @Test
    fun `dismiss + state = processed`() {
        val postTime = System.currentTimeMillis()
        CoupangAcceptDetector.onNotificationReceived(makeNotification(sbnKey = "sbn-match", postTime = postTime))
        CoupangAcceptDetector.onNotificationDismissed("sbn-match", postTime)
        CoupangAcceptDetector.onCoupangStateEvent()
        assertEquals(0, CoupangAcceptDetector.pendingCount())
    }

    @Test
    fun `다중 candidate 독립`() {
        CoupangAcceptDetector.onNotificationReceived(makeNotification(price = 3000, sbnKey = "sbn-a", postTime = 1000L))
        CoupangAcceptDetector.onNotificationReceived(makeNotification(price = 5000, sbnKey = "sbn-b", postTime = 2000L))
        assertEquals(2, CoupangAcceptDetector.pendingCount())

        CoupangAcceptDetector.onNotificationDismissed("sbn-a", 1000L)
        CoupangAcceptDetector.onCoupangStateEvent()
        // sbn-a processed, sbn-b still pending
        assertEquals(1, CoupangAcceptDetector.pendingCount())
    }

    @Test
    fun `resetForTest 초기화`() {
        CoupangAcceptDetector.onNotificationReceived(makeNotification())
        CoupangAcceptDetector.resetForTest()
        assertEquals(0, CoupangAcceptDetector.pendingCount())
    }

    // ── Q-0 + Q-0a: 상태 이벤트 텍스트 기반 승격 테스트 ──

    @Test
    fun `매장 도착 + candidate 존재 → 승격 (HIGH)`() {
        val postTime = System.currentTimeMillis()
        CoupangAcceptDetector.onNotificationReceived(makeNotification(sbnKey = "sbn-q0", postTime = postTime))
        CoupangAcceptDetector.onNotificationDismissed("sbn-q0", postTime)
        val promoted = CoupangAcceptDetector.onCoupangStateEvent("매장 도착 완료")
        assertEquals(1, promoted)
        assertEquals(0, CoupangAcceptDetector.pendingCount())
    }

    @Test
    fun `매장 픽업 + candidate 존재 → 승격 (HIGH)`() {
        val postTime = System.currentTimeMillis()
        CoupangAcceptDetector.onNotificationReceived(makeNotification(sbnKey = "sbn-q0b", postTime = postTime))
        CoupangAcceptDetector.onNotificationDismissed("sbn-q0b", postTime)
        val promoted = CoupangAcceptDetector.onCoupangStateEvent("매장 픽업 진행 중")
        assertEquals(1, promoted)
        assertEquals(0, CoupangAcceptDetector.pendingCount())
    }

    @Test
    fun `픽업 완료 되었습니다 + candidate 존재 → 승격 (MEDIUM)`() {
        val postTime = System.currentTimeMillis()
        CoupangAcceptDetector.onNotificationReceived(makeNotification(sbnKey = "sbn-q0a", postTime = postTime))
        CoupangAcceptDetector.onNotificationDismissed("sbn-q0a", postTime)
        val promoted = CoupangAcceptDetector.onCoupangStateEvent("픽업 완료 되었습니다")
        assertEquals(1, promoted)
        assertEquals(0, CoupangAcceptDetector.pendingCount())
    }

    @Test
    fun `픽업 완료 단독 (candidate 없음) → noop`() {
        val promoted = CoupangAcceptDetector.onCoupangStateEvent("픽업 완료 되었습니다")
        assertEquals(0, promoted)
    }

    @Test
    fun `일반 텍스트 → noop`() {
        val postTime = System.currentTimeMillis()
        CoupangAcceptDetector.onNotificationReceived(makeNotification(sbnKey = "sbn-gen", postTime = postTime))
        CoupangAcceptDetector.onNotificationDismissed("sbn-gen", postTime)
        val promoted = CoupangAcceptDetector.onCoupangStateEvent("배달 현황 리스트")
        assertEquals(0, promoted)
        assertEquals(1, CoupangAcceptDetector.pendingCount()) // 아직 pending
    }

    @Test
    fun `candidate 1건 + 매장 도착 2회 → 1회만 승격 (dedup)`() {
        val postTime = System.currentTimeMillis()
        CoupangAcceptDetector.onNotificationReceived(makeNotification(sbnKey = "sbn-dup", postTime = postTime))
        CoupangAcceptDetector.onNotificationDismissed("sbn-dup", postTime)
        val promoted1 = CoupangAcceptDetector.onCoupangStateEvent("매장 도착")
        val promoted2 = CoupangAcceptDetector.onCoupangStateEvent("매장 도착")
        assertEquals(1, promoted1)
        assertEquals(0, promoted2)
    }

    @Test
    fun `classifyStateConfidence 분류 검증`() {
        assertEquals(CoupangAcceptDetector.StateConfidence.HIGH,
            CoupangAcceptDetector.classifyStateConfidence("매장 도착 화면"))
        assertEquals(CoupangAcceptDetector.StateConfidence.HIGH,
            CoupangAcceptDetector.classifyStateConfidence("매장 픽업 진행"))
        assertEquals(CoupangAcceptDetector.StateConfidence.MEDIUM,
            CoupangAcceptDetector.classifyStateConfidence("픽업 완료 되었습니다"))
        assertNull(CoupangAcceptDetector.classifyStateConfidence("배달 현황"))
        assertEquals(CoupangAcceptDetector.StateConfidence.HIGH,
            CoupangAcceptDetector.classifyStateConfidence(null))
    }

    // ── Q-0b: state event가 "배달 현황" 등 비콜 키워드와 공존해도 승격 ──

    @Test
    fun `Q-0b 매장 도착 + 배달 현황 동시 존재 → 승격 (mixed text)`() {
        val postTime = System.currentTimeMillis()
        CoupangAcceptDetector.onNotificationReceived(makeNotification(sbnKey = "sbn-mixed", postTime = postTime))
        CoupangAcceptDetector.onNotificationDismissed("sbn-mixed", postTime)
        // 실제 accessibility tree에서 "배달 현황" 탭도 포함되는 화면
        val promoted = CoupangAcceptDetector.onCoupangStateEvent("배달 현황 매장 도착 매장찾기 팁 복사")
        assertEquals(1, promoted)
        assertEquals(0, CoupangAcceptDetector.pendingCount())
    }

    @Test
    fun `Q-0b 매장 도착 + 신규배달 동시 → state event 우선 승격`() {
        val postTime = System.currentTimeMillis()
        CoupangAcceptDetector.onNotificationReceived(makeNotification(sbnKey = "sbn-dual", postTime = postTime))
        CoupangAcceptDetector.onNotificationDismissed("sbn-dual", postTime)
        // "매장 도착" + "신규배달" 동시 텍스트
        val promoted = CoupangAcceptDetector.onCoupangStateEvent("신규배달 매장 도착 3,450원")
        assertEquals(1, promoted)
    }

    @Test
    fun `Q-0b candidate 없음 → state event noop`() {
        val promoted = CoupangAcceptDetector.onCoupangStateEvent("매장 도착 완료")
        assertEquals(0, promoted)
    }

    // ── Q-0c: 픽업완료 guard 강화 ──

    @Test
    fun `Q-0c 픽업완료 + 신규배달 + 가격 → 승격 차단`() {
        val postTime = System.currentTimeMillis()
        CoupangAcceptDetector.onNotificationReceived(makeNotification(sbnKey = "sbn-g1", postTime = postTime))
        CoupangAcceptDetector.onNotificationDismissed("sbn-g1", postTime)
        // 신규 콜 화면에 "픽업 완료" 텍스트가 혼입
        val promoted = CoupangAcceptDetector.onCoupangStateEvent("픽업 완료 신규배달 5,000원 수락")
        assertEquals(0, promoted)
        assertEquals(1, CoupangAcceptDetector.pendingCount())  // 아직 pending
    }

    @Test
    fun `Q-0c 픽업완료 + 가격패턴만 → 승격 차단`() {
        val postTime = System.currentTimeMillis()
        CoupangAcceptDetector.onNotificationReceived(makeNotification(sbnKey = "sbn-g2", postTime = postTime))
        CoupangAcceptDetector.onNotificationDismissed("sbn-g2", postTime)
        val promoted = CoupangAcceptDetector.onCoupangStateEvent("픽업 완료 3,450원 배달 거리 2.1km")
        assertEquals(0, promoted)
    }

    @Test
    fun `Q-0c 순수 픽업완료 (신규콜 패턴 없음) → MEDIUM 승격`() {
        val postTime = System.currentTimeMillis()
        CoupangAcceptDetector.onNotificationReceived(makeNotification(sbnKey = "sbn-g3", postTime = postTime))
        CoupangAcceptDetector.onNotificationDismissed("sbn-g3", postTime)
        val promoted = CoupangAcceptDetector.onCoupangStateEvent("픽업 완료 되었습니다")
        assertEquals(1, promoted)
    }

    @Test
    fun `Q-0c 매장 도착 + 신규배달 동시 → HIGH 유지 (매장도착은 guard 없음)`() {
        val postTime = System.currentTimeMillis()
        CoupangAcceptDetector.onNotificationReceived(makeNotification(sbnKey = "sbn-g4", postTime = postTime))
        CoupangAcceptDetector.onNotificationDismissed("sbn-g4", postTime)
        val promoted = CoupangAcceptDetector.onCoupangStateEvent("매장 도착 신규배달 5,000원")
        assertEquals(1, promoted)  // 매장 도착은 항상 HIGH
    }

    @Test
    fun `Q-0c classifyStateConfidence 픽업완료+수락 → null`() {
        assertNull(CoupangAcceptDetector.classifyStateConfidence("픽업 완료 수락"))
    }

    @Test
    fun `Q-0c classifyStateConfidence 순수 픽업완료 → MEDIUM`() {
        assertEquals(CoupangAcceptDetector.StateConfidence.MEDIUM,
            CoupangAcceptDetector.classifyStateConfidence("픽업 완료 되었습니다"))
    }
}
