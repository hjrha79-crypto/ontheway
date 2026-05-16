package com.vita.ontheway

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Fix V+: 배민 IN_PROGRESS 매칭 강화 테스트.
 *
 * 5순위 매칭:
 * 1. orderId exact → verified
 * 2. eventId exact → verified
 * 3. session_id exact → verified
 * 4. price±300 + storeName fuzzy (양쪽 non-blank + 후보 1개) → verified
 * 5. 그 외 → unverified
 */
class BaeminProgressVerifyTest {

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

        var sessionCounter = 0
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } answers {
            "session-v-${sessionCounter++}"
        }
        every { com.vita.ontheway.ledger.CallSessionRegistry.buildFingerprint(any(), any(), any()) } answers {
            "${firstArg<String>()}|${secondArg<Int>()}|${thirdArg<String>()}"
        }
        every { com.vita.ontheway.ledger.CallSessionRegistry.resetForTest() } returns Unit

        AcceptCoordinator.resetForTest()
        AcceptLifecycle.resetForTest()
        AcceptLifecycle.autoConfirm = true
        AcceptCoordinator.ledgerChecker = { _, _ -> false }
        AcceptCoordinator.ledgerSyncWriter = { _, _, _, _, _, _, _, _ -> }
    }

    @After
    fun teardown() {
        AcceptCoordinator.resetForTest()
        AcceptLifecycle.resetForTest()
        unmockkAll()
    }

    private fun makeEntry(
        platform: String = "baemin",
        price: Int = 4000,
        storeName: String = "맘스터치",
        eventId: String = "evt-1",
        orderId: String = "",
        tsOffset: Long = -60_000
    ): JSONObject = JSONObject().apply {
        put("ts", System.currentTimeMillis() + tsOffset)
        put("platform", platform)
        put("price", price)
        put("storeName", storeName)
        put("eventId", eventId)
        if (orderId.isNotBlank()) put("orderId", orderId)
        put("verdict", "PASS")
    }

    private val recentMs get() = System.currentTimeMillis() - 60_000  // 1분 전

    // ── 1순위: orderId exact match ──

    @Test
    fun `a orderId exact match → verified via orderId`() {
        every { FilterLog.getRecent(ctx, 10) } returns listOf(
            makeEntry(orderId = "T2CN-123", eventId = "evt-x", price = 9999)
        )
        val r = AcceptCoordinator.verifyAcceptCandidate(
            ctx, "baemin", 4000, "BBQ", "evt-nope", "T2CN-123", recentMs
        )
        assertTrue(r.verified)
        assertEquals("orderId", r.matchedVia)
    }

    // ── 2순위: eventId exact match ──

    @Test
    fun `b eventId exact match (orderId 없을 때) → verified via eventId`() {
        every { FilterLog.getRecent(ctx, 10) } returns listOf(
            makeEntry(eventId = "evt-match", price = 9999)
        )
        val r = AcceptCoordinator.verifyAcceptCandidate(
            ctx, "baemin", 4000, "BBQ", "evt-match", null, recentMs
        )
        assertTrue(r.verified)
        assertEquals("eventId", r.matchedVia)
    }

    @Test
    fun `b eventId exact match 가격 불일치해도 verified`() {
        every { FilterLog.getRecent(ctx, 10) } returns listOf(
            makeEntry(price = 9000, storeName = "BBQ", eventId = "evt-match")
        )
        val r = AcceptCoordinator.verifyAcceptCandidate(
            ctx, "baemin", 4000, "맘스터치", "evt-match", null, recentMs
        )
        assertTrue(r.verified)
        assertEquals("eventId", r.matchedVia)
    }

    // ── 3순위: session_id exact match (state 필드) ──

    @Test
    fun `c session_id match via state field → verified`() {
        val entry = makeEntry(price = 9000).apply { put("state", "sess-abc") }
        every { FilterLog.getRecent(ctx, 10) } returns listOf(entry)
        val r = AcceptCoordinator.verifyAcceptCandidate(
            ctx, "baemin", 4000, "BBQ", "sess-abc", null, recentMs
        )
        assertTrue(r.verified)
        assertEquals("session_id", r.matchedVia)
    }

    // ── 4순위: price±300 + storeName fuzzy (양쪽 non-blank + 후보 1개) ──

    @Test
    fun `d fuzzy 양쪽 non-blank + 후보 1개 → verified via fuzzy`() {
        every { FilterLog.getRecent(ctx, 10) } returns listOf(
            makeEntry(price = 4200, storeName = "맘스터치 강남점")
        )
        val r = AcceptCoordinator.verifyAcceptCandidate(
            ctx, "baemin", 4000, "맘스터치", null, null, recentMs
        )
        assertTrue(r.verified)
        assertEquals("fuzzy", r.matchedVia)
    }

    @Test
    fun `d storeName 한쪽 blank → unverified (fuzzy 차단)`() {
        every { FilterLog.getRecent(ctx, 10) } returns listOf(
            makeEntry(price = 4000, storeName = "맘스터치")
        )
        val r = AcceptCoordinator.verifyAcceptCandidate(
            ctx, "baemin", 4000, "", null, null, recentMs  // storeName blank
        )
        assertFalse(r.verified)
        assertNull(r.matchedVia)
    }

    @Test
    fun `d entry storeName blank → unverified (fuzzy 차단)`() {
        every { FilterLog.getRecent(ctx, 10) } returns listOf(
            makeEntry(price = 4000, storeName = "")
        )
        val r = AcceptCoordinator.verifyAcceptCandidate(
            ctx, "baemin", 4000, "맘스터치", null, null, recentMs
        )
        assertFalse(r.verified)
    }

    @Test
    fun `f fuzzy 후보 2개 (ambiguous) → unverified`() {
        every { FilterLog.getRecent(ctx, 10) } returns listOf(
            makeEntry(price = 4000, storeName = "맘스터치 강남", eventId = "e1"),
            makeEntry(price = 4100, storeName = "맘스터치 역삼", eventId = "e2")
        )
        val r = AcceptCoordinator.verifyAcceptCandidate(
            ctx, "baemin", 4000, "맘스터치", null, null, recentMs
        )
        assertFalse(r.verified)
        assertNull(r.matchedVia)
    }

    // ── 5순위: 그 외 ──

    @Test
    fun `가격 불일치 3000원 → unverified`() {
        every { FilterLog.getRecent(ctx, 10) } returns listOf(
            makeEntry(price = 7000, storeName = "맘스터치")
        )
        val r = AcceptCoordinator.verifyAcceptCandidate(
            ctx, "baemin", 4000, "맘스터치", null, null, recentMs
        )
        assertFalse(r.verified)
    }

    @Test
    fun `다른 platform → unverified`() {
        every { FilterLog.getRecent(ctx, 10) } returns listOf(
            makeEntry(platform = "coupang", price = 4000)
        )
        val r = AcceptCoordinator.verifyAcceptCandidate(
            ctx, "baemin", 4000, "맘스터치", null, null, recentMs
        )
        assertFalse(r.verified)
    }

    @Test
    fun `FilterLog 비어있음 + 윈도우 밖 → unverified`() {
        every { FilterLog.getRecent(ctx, 10) } returns emptyList()
        val r = AcceptCoordinator.verifyAcceptCandidate(
            ctx, "baemin", 4000, "맘스터치", null, null,
            System.currentTimeMillis() - 600_000  // 10분 전
        )
        assertFalse(r.verified)
    }

    @Test
    fun `시간 윈도우 초과 → unverified`() {
        every { FilterLog.getRecent(ctx, 10) } returns listOf(
            makeEntry(price = 4000, tsOffset = -10 * 60_000L)
        )
        val r = AcceptCoordinator.verifyAcceptCandidate(
            ctx, "baemin", 4000, "맘스터치", null, null,
            System.currentTimeMillis() - 10 * 60_000  // 10분 전
        )
        assertFalse(r.verified)
    }

    // ── 매칭 우선순위: orderId > eventId ──

    @Test
    fun `orderId match 우선 (eventId도 있지만 orderId 먼저)`() {
        every { FilterLog.getRecent(ctx, 10) } returns listOf(
            makeEntry(orderId = "T2CN-456", eventId = "evt-other", price = 9000)
        )
        val r = AcceptCoordinator.verifyAcceptCandidate(
            ctx, "baemin", 4000, "BBQ", "evt-nope", "T2CN-456", recentMs
        )
        assertEquals("orderId", r.matchedVia)
    }

    // ── dedup ──

    @Test
    fun `동일 session 5회 handleAccept → 1회만 DRIVER_ACCEPTED`() {
        every { com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(any(), any(), any()) } returns "same-session-v"
        repeat(5) {
            AcceptCoordinator.handleAccept(
                ctx, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS,
                4000, "baemin", storeName = "맘스터치",
                eventId = "evt-same", orderId = "ord-same"
            )
        }
        verify(exactly = 1) {
            EarningsTracker.recordAccept(any(), 4000, "baemin", "맘스터치", "ord-same")
        }
    }

    // ── storeNameFuzzyMatch ──

    @Test
    fun `fuzzy match 정확 일치`() {
        assertTrue(AcceptCoordinator.storeNameFuzzyMatch("맘스터치", "맘스터치"))
    }

    @Test
    fun `fuzzy match 포함 관계`() {
        assertTrue(AcceptCoordinator.storeNameFuzzyMatch("맘스터치", "맘스터치 강남점"))
    }

    @Test
    fun `fuzzy match 첫 2글자`() {
        assertTrue(AcceptCoordinator.storeNameFuzzyMatch("맘스터치", "맘스"))
    }

    @Test
    fun `fuzzy match 실패`() {
        assertFalse(AcceptCoordinator.storeNameFuzzyMatch("맘스터치", "BBQ"))
    }
}
