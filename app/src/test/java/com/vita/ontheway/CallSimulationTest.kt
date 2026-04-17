package com.vita.ontheway

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * 실제 배달 콜 시나리오 시뮬레이션 유닛 테스트 (18개)
 *
 * BaeminParser / CoupangParser → CallFilter 판정까지 검증
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CallSimulationTest {

    private lateinit var ctx: Context

    @Before
    fun setup() {
        // SharedPreferences 목: 모든 get 호출에 기본값 반환
        val mockPrefs = mockk<SharedPreferences>()
        every { mockPrefs.getInt(any(), any()) } answers { secondArg() }
        every { mockPrefs.getBoolean(any(), any()) } answers { secondArg() }
        every { mockPrefs.getString(any(), any()) } answers { secondArg() }

        ctx = mockk<Context>()
        every { ctx.getSharedPreferences(any(), any()) } returns mockPrefs
    }

    // ══════════════════════════════════════════
    // 배민 단일 콜
    // ══════════════════════════════════════════

    @Test
    fun `01 배민 저가 3000원 8P REJECT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 3,000원", "8.0P")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertEquals(3000, calls[0].price)
        assertEquals(8.0, calls[0].point!!, 0.01)
        // 8P → 2km, 단가 1500원/km < 2000 → REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("01 PASS: ${result.reason}")
    }

    @Test
    fun `02 배민 중가 5500원 10P ACCEPT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 5,500원", "10.0P")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertEquals(5500, calls[0].price)
        // 10P → 2.5km, 단가 2200원/km ≥ 2000 → ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("02 PASS: ${result.reason}")
    }

    @Test
    fun `03 배민 고가 10000원 16P ACCEPT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 10,000원", "16.0P")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 10000 ≥ 7000 → 고액 보호 → ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("03 PASS: ${result.reason}")
    }

    @Test
    fun `04 배민 고액보호 7500원 20P ACCEPT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 7,500원", "20.0P")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 7500 ≥ 7000 → 고액 보호 → ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("04 PASS: ${result.reason}")
    }

    @Test
    fun `05 배민 저포인트 3500원 6P ACCEPT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 3,500원", "6.0P")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertEquals(6.0, calls[0].point!!, 0.01)
        // 6P → 1.5km, 단가 2333원/km ≥ 2000 → ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("05 PASS: ${result.reason}")
    }

    @Test
    fun `06 배민 고포인트 4000원 30P REJECT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 4,000원", "30.0P")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 30P > 25 → 포인트구간 최소 5000원, 4000 < 5000 → REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("06 PASS: ${result.reason}")
    }

    // ══════════════════════════════════════════
    // 배민 묶음 콜
    // ══════════════════════════════════════════

    @Test
    fun `07 배민 묶음 2건 단일픽업 5500원 ACCEPT`() {
        // 묶음은 results.size >= 2 로 판정, bundleCount = results.size
        val texts = listOf("맘스터치", "배달료 3,000원", "배달료 2,500원")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertTrue("묶음 판정 실패", calls[0].isMulti)
        assertEquals(5500, calls[0].price)
        assertEquals(2, calls[0].bundleCount)
        // bundleMin = 3000+(2-1)*2500 = 5500, 5500 ≥ 5500 → ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("07 PASS: ${result.reason}")
    }

    @Test
    fun `08 배민 묶음 2건 단일픽업 4500원 REJECT`() {
        val texts = listOf("맘스터치", "배달료 2,500원", "배달료 2,000원")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertEquals(4500, calls[0].price)
        // 4500 < bundleMin(5500) → REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("08 PASS: ${result.reason}")
    }

    @Test
    fun `09 배민 묶음 2건 다중픽업 7000원 ACCEPT`() {
        val texts = listOf("맘스터치", "버거킹", "배달료 4,000원", "배달료 3,000원")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertTrue("다중픽업 판정 실패", calls[0].isMultiPickup)
        assertEquals(7000, calls[0].price)
        // multiPickupMin(2건) = 7000, effectiveMin = max(5500, 7000) = 7000 → ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("09 PASS: ${result.reason}")
    }

    @Test
    fun `10 배민 묶음 3건 8000원 ACCEPT`() {
        val texts = listOf("맘스터치", "배달료 3,000원", "배달료 2,800원", "배달료 2,200원")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertEquals(8000, calls[0].price)
        assertEquals(3, calls[0].bundleCount)
        // bundleMin = 3000+(3-1)*2500 = 8000 → ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("10 PASS: ${result.reason}")
    }

    @Test
    fun `11 배민 묶음 3건 7000원 REJECT`() {
        val texts = listOf("맘스터치", "배달료 2,500원", "배달료 2,300원", "배달료 2,200원")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertEquals(7000, calls[0].price)
        // 7000 < bundleMin(8000) → REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("11 PASS: ${result.reason}")
    }

    // ══════════════════════════════════════════
    // 비콜 필터링
    // ══════════════════════════════════════════

    @Test
    fun `12 배민 미션 완료시최대 비콜`() {
        // "완료 시 최대" 는 OnTheWayService에서 스킵 + 파서에 "배달료" 없어 파싱 불가
        val texts = listOf("완료 시 최대", "20,000원", "미션 전체보기")
        val calls = BaeminParser.parse(texts)
        assertTrue("비콜이어야 함 (미션 텍스트)", calls.isEmpty())
        println("12 PASS: 배민 미션 텍스트 비콜 필터링 성공")
    }

    @Test
    fun `13 배민 비콜 배달을시작해`() {
        // "배달을 시작해" 는 OnTheWayService에서 스킵 + 파서에 "배달료" 없어 파싱 불가
        val texts = listOf("배달을 시작해", "신규배차를 켜세요")
        val calls = BaeminParser.parse(texts)
        assertTrue("비콜이어야 함", calls.isEmpty())
        println("13 PASS: 배민 비콜 필터링 성공")
    }

    @Test
    fun `14 쿠팡 정상 콜 4325원 3점9km`() {
        val texts = listOf("치킨집", "4,325원", "배달 거리 3.9km", "거절", "주문 수락")
        val calls = CoupangParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertEquals(4325, calls[0].price)
        assertEquals(3.9, calls[0].distance!!, 0.01)
        assertEquals("coupang", calls[0].platform)
        println("14 PASS: 쿠팡 정상 콜 파싱 성공 — ${calls[0].price}원/${calls[0].distance}km")
    }

    @Test
    fun `15 쿠팡 유령콜 버튼없음 비콜`() {
        // "주문 수락"/"거절" 버튼 없음 → 콜 화면 아님
        val texts = listOf("20,000원", "배달 거리 1.5km")
        val calls = CoupangParser.parse(texts)
        assertTrue("비콜이어야 함 (주문수락 버튼 없음)", calls.isEmpty())
        println("15 PASS: 쿠팡 유령 콜 필터링 성공 (버튼 없음)")
    }

    @Test
    fun `16 쿠팡 비콜 출근하기`() {
        // "출근하기" NON_CALL_KEYWORDS 매칭
        val texts = listOf("출근하기", "4,000원", "거절")
        val calls = CoupangParser.parse(texts)
        assertTrue("비콜이어야 함 (출근하기 키워드)", calls.isEmpty())
        println("16 PASS: 쿠팡 비콜 필터링 성공")
    }

    // ══════════════════════════════════════════
    // 거리 없음 (포인트도 없음)
    // ══════════════════════════════════════════

    @Test
    fun `17 거리없음 3500원 ACCEPT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 3,500원")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertNull("거리 없어야 함", calls[0].distance)
        assertNull("포인트 없어야 함", calls[0].point)
        // 거리/포인트 없음 → 단건: 3500 ≥ 3000(minPrice) → ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("17 PASS: ${result.reason}")
    }

    @Test
    fun `18 거리없음 2500원 REJECT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 2,500원")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 2500 < 3000(minPrice) → REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("18 PASS: ${result.reason}")
    }
}
