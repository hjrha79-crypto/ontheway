package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * backfillAcceptedAt 매칭 로직 단위 테스트.
 * DB 없이 순수 매칭 알고리즘만 검증.
 */
class BackfillAcceptedAtTest {

    data class LedgerAccept(val platform: String, val wallTs: Long)
    data class CallLogRow(val id: Long, val platform: String, val timestamp: Long,
                          val verdict: String, var acceptedAt: Long? = null,
                          var actionSource: String? = null)

    private val WINDOW_BEFORE_MS = 5 * 60_000L
    private val WINDOW_AFTER_MS = 30_000L

    /**
     * 매칭 로직 재현: platform 동일, timestamp 근접, verdict=ACCEPT, acceptedAt=null
     */
    private fun backfill(ledgerEvents: List<LedgerAccept>, callLogs: MutableList<CallLogRow>): Pair<Int, Int> {
        var matched = 0; var skipped = 0
        for (le in ledgerEvents) {
            val candidates = callLogs.filter { cl ->
                cl.platform == le.platform &&
                cl.verdict == "ACCEPT" &&
                cl.acceptedAt == null &&
                cl.timestamp >= le.wallTs - WINDOW_BEFORE_MS &&
                cl.timestamp <= le.wallTs + WINDOW_AFTER_MS
            }
            val best = candidates.minByOrNull { abs(it.timestamp - le.wallTs) }
            if (best != null) {
                best.acceptedAt = le.wallTs
                best.actionSource = "ledger_backfill"
                matched++
            } else {
                skipped++
            }
        }
        return matched to skipped
    }

    @Test
    fun `정상 매칭 - 같은 platform, 시간 범위 내`() {
        val t = 1_000_000_000L
        val ledger = listOf(LedgerAccept("baemin", t))
        val calls = mutableListOf(
            CallLogRow(1, "baemin", t - 60_000, "ACCEPT")
        )
        val (m, s) = backfill(ledger, calls)
        assertEquals(1, m)
        assertEquals(0, s)
        assertEquals(t, calls[0].acceptedAt)
        assertEquals("ledger_backfill", calls[0].actionSource)
    }

    @Test
    fun `다른 platform은 매칭 안됨`() {
        val t = 1_000_000_000L
        val ledger = listOf(LedgerAccept("baemin", t))
        val calls = mutableListOf(
            CallLogRow(1, "coupang", t - 60_000, "ACCEPT")
        )
        val (m, s) = backfill(ledger, calls)
        assertEquals(0, m)
        assertEquals(1, s)
        assertNull(calls[0].acceptedAt)
    }

    @Test
    fun `verdict REJECT는 매칭 안됨`() {
        val t = 1_000_000_000L
        val ledger = listOf(LedgerAccept("baemin", t))
        val calls = mutableListOf(
            CallLogRow(1, "baemin", t - 60_000, "REJECT")
        )
        val (m, s) = backfill(ledger, calls)
        assertEquals(0, m)
        assertEquals(1, s)
    }

    @Test
    fun `이미 acceptedAt이 있으면 skip`() {
        val t = 1_000_000_000L
        val ledger = listOf(LedgerAccept("baemin", t))
        val calls = mutableListOf(
            CallLogRow(1, "baemin", t - 60_000, "ACCEPT", acceptedAt = t - 100)
        )
        val (m, s) = backfill(ledger, calls)
        assertEquals(0, m)
        assertEquals(1, s)
        assertEquals(t - 100, calls[0].acceptedAt) // 기존값 유지
    }

    @Test
    fun `5분 초과는 매칭 안됨`() {
        val t = 1_000_000_000L
        val ledger = listOf(LedgerAccept("baemin", t))
        val calls = mutableListOf(
            CallLogRow(1, "baemin", t - 5 * 60_000 - 1, "ACCEPT") // 5분 1ms 초과
        )
        val (m, s) = backfill(ledger, calls)
        assertEquals(0, m)
        assertEquals(1, s)
    }

    @Test
    fun `+30초 초과는 매칭 안됨`() {
        val t = 1_000_000_000L
        val ledger = listOf(LedgerAccept("baemin", t))
        val calls = mutableListOf(
            CallLogRow(1, "baemin", t + 30_001, "ACCEPT") // 30초 1ms 초과
        )
        val (m, s) = backfill(ledger, calls)
        assertEquals(0, m)
        assertEquals(1, s)
    }

    @Test
    fun `경계값 -5분 정확히는 매칭됨`() {
        val t = 1_000_000_000L
        val ledger = listOf(LedgerAccept("baemin", t))
        val calls = mutableListOf(
            CallLogRow(1, "baemin", t - 5 * 60_000, "ACCEPT")
        )
        val (m, s) = backfill(ledger, calls)
        assertEquals(1, m)
    }

    @Test
    fun `경계값 +30초 정확히는 매칭됨`() {
        val t = 1_000_000_000L
        val ledger = listOf(LedgerAccept("baemin", t))
        val calls = mutableListOf(
            CallLogRow(1, "baemin", t + 30_000, "ACCEPT")
        )
        val (m, s) = backfill(ledger, calls)
        assertEquals(1, m)
    }

    @Test
    fun `동일 시각 ACCEPT 2건 - 가장 가까운 1건만 매칭`() {
        val t = 1_000_000_000L
        val ledger = listOf(LedgerAccept("baemin", t))
        val calls = mutableListOf(
            CallLogRow(1, "baemin", t - 120_000, "ACCEPT"), // 2분 전
            CallLogRow(2, "baemin", t - 30_000, "ACCEPT")   // 30초 전 (더 가까움)
        )
        val (m, s) = backfill(ledger, calls)
        assertEquals(1, m)
        assertEquals(0, s)
        assertNull(calls[0].acceptedAt)      // 2분 전 건은 미매칭
        assertEquals(t, calls[1].acceptedAt) // 30초 전 건만 매칭
    }

    @Test
    fun `ledger 비어있으면 noop`() {
        val calls = mutableListOf(
            CallLogRow(1, "baemin", 1_000_000_000L, "ACCEPT")
        )
        val (m, s) = backfill(emptyList(), calls)
        assertEquals(0, m)
        assertEquals(0, s)
    }

    @Test
    fun `ledger 2건이 동일 call_log에 매칭 시도 - 첫 건만 매칭`() {
        val t = 1_000_000_000L
        val ledger = listOf(
            LedgerAccept("baemin", t),
            LedgerAccept("baemin", t + 10_000) // 10초 후 또 다른 ACCEPT
        )
        val calls = mutableListOf(
            CallLogRow(1, "baemin", t - 30_000, "ACCEPT")
        )
        val (m, s) = backfill(ledger, calls)
        assertEquals(1, m)  // 첫 ledger만 매칭
        assertEquals(1, s)  // 두 번째는 이미 채워져서 skip
        assertEquals(t, calls[0].acceptedAt)
    }

    @Test
    fun `5-11 시나리오 - 배민 5건 + 배민 1건`() {
        val base = 1_715_400_000_000L // 5/11 14시대
        val ledger = listOf(
            LedgerAccept("baemin", base),
            LedgerAccept("baemin", base + 10 * 60_000),
            LedgerAccept("baemin", base + 20 * 60_000),
            LedgerAccept("baemin", base + 35 * 60_000),
            LedgerAccept("baemin", base + 50 * 60_000),
            LedgerAccept("baemin", base + 70 * 60_000)
        )
        val calls = mutableListOf(
            CallLogRow(1, "baemin", base - 60_000, "ACCEPT"),
            CallLogRow(2, "baemin", base + 9 * 60_000, "ACCEPT"),
            CallLogRow(3, "baemin", base + 19 * 60_000, "ACCEPT"),
            CallLogRow(4, "baemin", base + 34 * 60_000, "ACCEPT"),
            CallLogRow(5, "baemin", base + 49 * 60_000, "ACCEPT"),
            CallLogRow(6, "baemin", base + 69 * 60_000, "ACCEPT")
        )
        val (m, s) = backfill(ledger, calls)
        assertEquals(6, m)
        assertEquals(0, s)
        // 모든 건에 accepted_at 채워짐
        assertTrue(calls.all { it.acceptedAt != null })
    }
}
