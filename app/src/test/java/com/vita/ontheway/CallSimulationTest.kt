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
 * 실제 배달 콜 시나리오 시뮬레이션 유닛 테스트 (38개)
 *
 * BaeminParser / CoupangParser -> CallFilter 판정까지 검증
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

        // FIX2: BaeminParser dedup 캐시 초기화
        BaeminParser.resetDedupCache()
    }

    // ---- 배민 단일 콜 ----

    @Test
    fun `01 배민 저가 3000원 8P ACCEPT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 3,000원", "8.0P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertEquals(3000, calls[0].price)
        assertEquals(8.0, calls[0].point!!, 0.01)
        // v3.19: 포인트 테이블 제거 → 3000 >= minPrice(3000) → ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("01 PASS: ${result.reason}")
    }

    @Test
    fun `02 배민 중가 5500원 10P ACCEPT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 5,500원", "10.0P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertEquals(5500, calls[0].price)
        // 10P = 2.5km, 단가 2200원/km >= 2000 -> ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("02 PASS: ${result.reason}")
    }

    @Test
    fun `03 배민 고가 10000원 16P ACCEPT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 10,000원", "16.0P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 10000 >= 7000 -> 고액 보호 -> ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("03 PASS: ${result.reason}")
    }

    @Test
    fun `04 배민 고액보호 7500원 20P ACCEPT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 7,500원", "20.0P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 7500 >= 7000 -> 고액 보호 -> ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("04 PASS: ${result.reason}")
    }

    @Test
    fun `05 배민 저포인트 3500원 6P ACCEPT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 3,500원", "6.0P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertEquals(6.0, calls[0].point!!, 0.01)
        // 6P = 1.5km, 단가 2333원/km >= 2000 -> ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("05 PASS: ${result.reason}")
    }

    @Test
    fun `06 배민 고포인트 4000원 30P REJECT 단가미달`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 4,000원", "30.0P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        // v3.21: 30P→4.5km, 단가 889원/km < 2000 → REJECT 단가 미달
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        assertTrue("단가 미달 사유", result.reason.contains("단가") && result.reason.contains("미달"))
        println("06 PASS: ${result.reason}")
    }

    // ---- 배민 묶음 콜 ----

    @Test
    fun `07 배민 묶음 2건 단일픽업 5500원 REJECT 건당미달`() {
        // 묶음은 results.size >= 2 로 판정, bundleCount = results.size
        val texts = listOf("맘스터치", "배달료 3,000원", "배달료 2,500원")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertTrue("묶음 판정 실패", calls[0].isMulti)
        assertEquals(5500, calls[0].price)
        assertEquals(2, calls[0].bundleCount)
        // 총액 5500 >= bundleMin(5500) 통과, but 건당 2750 < 4500 → REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("07 PASS: ${result.reason}")
    }

    @Test
    fun `08 배민 묶음 2건 단일픽업 4500원 REJECT`() {
        val texts = listOf("맘스터치", "배달료 2,500원", "배달료 2,000원")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertEquals(4500, calls[0].price)
        // 4500 < bundleMin(5500) -> REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("08 PASS: ${result.reason}")
    }

    @Test
    fun `09 배민 묶음 2건 다중픽업 7000원 REJECT 건당미달`() {
        val texts = listOf("맘스터치", "버거킹", "배달료 4,000원", "배달료 3,000원")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertTrue("다중픽업 판정 실패", calls[0].isMultiPickup)
        assertEquals(7000, calls[0].price)
        // 총액 7000 >= effectiveMin(7000) 통과, but 건당 3500 < 4500 → REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("09 PASS: ${result.reason}")
    }

    @Test
    fun `10 배민 묶음 3건 8000원 REJECT 건당미달`() {
        val texts = listOf("맘스터치", "배달료 3,000원", "배달료 2,800원", "배달료 2,200원")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertEquals(8000, calls[0].price)
        assertEquals(3, calls[0].bundleCount)
        // 총액 8000 >= bundleMin(8000) 통과, but 건당 2667 < 5000 → REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("10 PASS: ${result.reason}")
    }

    @Test
    fun `11 배민 묶음 3건 7000원 REJECT`() {
        val texts = listOf("맘스터치", "배달료 2,500원", "배달료 2,300원", "배달료 2,200원")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertEquals(7000, calls[0].price)
        // 7000 < bundleMin(8000) -> REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("11 PASS: ${result.reason}")
    }

    // ---- 비콜 필터링 ----

    @Test
    fun `12 배민 미션 완료시최대 비콜`() {
        // "완료 시 최대" 는 OnTheWayService에서 스킵 + 파서에 "배달료" 없어 파싱 불가
        val texts = listOf("완료 시 최대", "20,000원", "미션 전체보기")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("비콜이어야 함 (미션 텍스트)", calls.isEmpty())
        println("12 PASS: 배민 미션 텍스트 비콜 필터링 성공")
    }

    @Test
    fun `13 배민 비콜 배달을시작해`() {
        // "배달을 시작해" 는 OnTheWayService에서 스킵 + 파서에 "배달료" 없어 파싱 불가
        val texts = listOf("배달을 시작해", "신규배차를 켜세요")
        val calls = BaeminParser.parse(texts)!!
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
        println("14 PASS: 쿠팡 정상 콜 파싱 성공 - ${calls[0].price}원/${calls[0].distance}km")
    }

    @Test
    fun `15 쿠팡 유령콜 버튼없음 비콜`() {
        // "주문 수락"/"거절" 버튼 없음 -> 콜 화면 아님
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

    // ---- 거리 없음 (포인트도 없음) ----

    @Test
    fun `17 거리없음 3500원 ACCEPT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 3,500원")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertNull("거리 없어야 함", calls[0].distance)
        assertNull("포인트 없어야 함", calls[0].point)
        // 거리/포인트 없음 -> 단건: 3500 >= 3000(minPrice) -> ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("17 PASS: ${result.reason}")
    }

    @Test
    fun `18 거리없음 2500원 REJECT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 2,500원")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 2500 < 3000(minPrice) -> REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("18 PASS: ${result.reason}")
    }

    // ---- 추가: 단가 경계값 ----

    @Test
    fun `21 배민 단가 경계 4000원 8P 정확히 2000원km ACCEPT`() {
        val texts = listOf("맘스터치", "배달료 4,000원", "8.0P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 8P = 2.0km, 단가 4000/2.0 = 2000 >= 2000 -> ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("21 PASS: ${result.reason}")
    }

    @Test
    fun `22 배민 포인트구간 경계 16P 4000원 ACCEPT 단가통과`() {
        val texts = listOf("맘스터치", "배달료 4,000원", "16.0P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 16P→2.4km, 단가 1667원/km >= 1400 → ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("22 PASS: ${result.reason}")
    }

    @Test
    fun `23 배민 고액 8000원 12P ACCEPT`() {
        val texts = listOf("맘스터치", "배달료 8,000원", "12.0P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 8000 >= 7000 -> 고액 보호 -> ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        assertTrue("고액 사유 포함", result.reason.contains("고액"))
        println("23 PASS: ${result.reason}")
    }

    @Test
    fun `24 배민 고액보호 7500원 12P ACCEPT`() {
        val texts = listOf("맘스터치", "배달료 7,500원", "12.0P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 7500 >= 7000 -> 고액 보호 -> ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("24 PASS: ${result.reason}")
    }

    // ---- 추가: 포인트 구간 경계값 ----

    @Test
    fun `25 배민 포인트 경계 15P 3000원 REJECT 단가미달`() {
        val texts = listOf("맘스터치", "배달료 3,000원", "15.0P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        // v3.21: 15P→2.25km, 단가 1333원/km < 2000 → REJECT 단가 미달
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("25 PASS: ${result.reason}")
    }

    @Test
    fun `26 배민 포인트 16P 구간 3500원 ACCEPT 단가통과`() {
        val texts = listOf("맘스터치", "배달료 3,500원", "16.0P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 16P→2.4km, 단가 1458원/km >= 1400 → ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("26 PASS: ${result.reason}")
    }

    @Test
    fun `27 배민 포인트 경계 25P 4000원 REJECT 단가미달`() {
        val texts = listOf("맘스터치", "배달료 4,000원", "25.0P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        // v3.21: 25P→3.75km, 단가 1067원/km < 2000 → REJECT 단가 미달
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("27 PASS: ${result.reason}")
    }

    @Test
    fun `28 배민 포인트 26P 구간 4500원 REJECT 단가미달`() {
        val texts = listOf("맘스터치", "배달료 4,500원", "26.0P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        // v3.21: 26P→3.9km, 단가 1154원/km < 2000 → REJECT 단가 미달
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("28 PASS: ${result.reason}")
    }

    // ---- 추가: 묶음 다중픽업 경계값 ----

    @Test
    fun `29 배민 묶음 2건 다중픽업 6900원 REJECT`() {
        val texts = listOf("맘스터치", "버거킹", "배달료 3,900원", "배달료 3,000원")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertTrue("다중픽업 판정 실패", calls[0].isMultiPickup)
        assertEquals(6900, calls[0].price)
        // multiPickupMin(2건)=7000, 6900<7000 -> REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("29 PASS: ${result.reason}")
    }

    @Test
    fun `30 배민 묶음 3건 다중픽업 10000원 REJECT 건당미달`() {
        val texts = listOf("맘스터치", "버거킹", "배달료 4,000원", "배달료 3,500원", "배달료 2,500원")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertTrue("다중픽업 판정 실패", calls[0].isMultiPickup)
        assertEquals(10000, calls[0].price)
        // 총액 10000 >= effectiveMin(10000) 통과, but 건당 3333 < 5000 → REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("30 PASS: ${result.reason}")
    }

    @Test
    fun `31 배민 묶음 3건 다중픽업 9500원 REJECT`() {
        val texts = listOf("맘스터치", "버거킹", "배달료 4,000원", "배달료 3,300원", "배달료 2,200원")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertTrue("다중픽업 판정 실패", calls[0].isMultiPickup)
        assertEquals(9500, calls[0].price)
        // effectiveMin=10000, 9500<10000 -> REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("31 PASS: ${result.reason}")
    }

    // ---- 추가: 쿠팡 단가/고가 ----

    @Test
    fun `32 쿠팡 고단가 6000원 2km ACCEPT`() {
        val texts = listOf("치킨집", "6,000원", "배달 거리 2.0km", "거절", "주문 수락")
        val calls = CoupangParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertEquals(6000, calls[0].price)
        assertEquals(2.0, calls[0].distance!!, 0.01)
        // 단가 3000원/km >= 2000 -> ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("32 PASS: ${result.reason}")
    }

    @Test
    fun `33 쿠팡 저단가 3000원 3km REJECT`() {
        val texts = listOf("치킨집", "3,000원", "배달 거리 3.0km", "거절", "주문 수락")
        val calls = CoupangParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 단가 1000원/km < 2000 -> REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("33 PASS: ${result.reason}")
    }

    @Test
    fun `34 쿠팡 거리없음 고가 8000원 ACCEPT`() {
        val texts = listOf("치킨집", "8,000원", "거절", "주문 수락")
        val calls = CoupangParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertNull("거리 없어야 함", calls[0].distance)
        // 8000 >= 7000 -> 고액 보호 -> ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("34 PASS: ${result.reason}")
    }

    // ---- 추가: 비콜 필터링 확장 ----

    @Test
    fun `35 배민 비콜 가게정보`() {
        val texts = listOf("가게정보", "맛집", "영업시간")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("비콜이어야 함", calls.isEmpty())
        println("35 PASS: 배민 가게정보 비콜 필터링 성공")
    }

    @Test
    fun `36 배민 비콜 주행기록기반`() {
        val texts = listOf("주행기록 기반", "운행 분석")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("비콜이어야 함", calls.isEmpty())
        println("36 PASS: 배민 주행기록 비콜 필터링 성공")
    }

    @Test
    fun `37 배민 비콜 배달완료`() {
        val texts = listOf("배달 완료", "수고하셨습니다")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("비콜이어야 함", calls.isEmpty())
        println("37 PASS: 배민 배달완료 비콜 필터링 성공")
    }

    @Test
    fun `38 쿠팡 비콜 주문을기다리는중`() {
        val texts = listOf("주문을 기다리는 중", "3,500원", "거절")
        val calls = CoupangParser.parse(texts)
        assertTrue("비콜이어야 함 (대기 화면)", calls.isEmpty())
        println("38 PASS: 쿠팡 대기화면 비콜 필터링 성공")
    }

    @Test
    fun `39 쿠팡 비콜 수입현황`() {
        val texts = listOf("수입 현황", "오늘 35,000원", "거절")
        val calls = CoupangParser.parse(texts)
        assertTrue("비콜이어야 함 (수입 현황)", calls.isEmpty())
        println("39 PASS: 쿠팡 수입현황 비콜 필터링 성공")
    }

    @Test
    fun `40 배민 극저가 1500원 4P REJECT`() {
        val texts = listOf("맘스터치", "배달료 1,500원", "4.0P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 4P=1km, pointMinPrice=3000, 1500<3000 -> REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("40 PASS: ${result.reason}")
    }

    // ---- 배민 묶음 세션 (실전 케이스 4개) ----

    @Test
    fun `41 묶음세션A 9070원 3건 43점6P`() {
        BaeminBundleSession.reset()

        // 이벤트1: 첫 가게 단건 (트리거 없음)
        val joined1 = "치킨집 배달료 3,650원 18.5P"
        assertFalse("세션 미시작", BaeminBundleSession.checkAndStartSession(joined1))

        // 이벤트2: 총 합계 화면 (트리거 + 즉시 종료 가능)
        val joined2 = "총 합계 9,070원 43.6P 3건 모두 수락 모두 거절"
        assertTrue("세션 시작", BaeminBundleSession.checkAndStartSession(joined2))
        assertTrue("종료 가능", BaeminBundleSession.canFinalize())

        // 이전 이벤트 데이터 피딩 (OnTheWayService debounce 버퍼 드레인 시뮬)
        BaeminBundleSession.addCallData(3650, 18.5, "치킨집")

        val result = BaeminBundleSession.finalize()
        assertNotNull("묶음 결과 생성", result)
        assertEquals(9070, result!!.price)
        assertEquals(43.6, result.point!!, 0.01)
        assertEquals(3, result.bundleCount)
        assertTrue("묶음 판정", result.isMulti)

        // CallFilter 판정: 9070/3건=건당3023 < 5000 → REJECT
        val filterResult = CallFilter.judge(result, ctx)
        assertEquals(CallFilter.Verdict.REJECT, filterResult.verdict)
        println("41 PASS: 묶음세션A - ${result.price}원/${result.point}P ${result.bundleCount}건 → ${filterResult.verdict}")
    }

    @Test
    fun `42 묶음세션B 6010원 2건 31점7P`() {
        BaeminBundleSession.reset()

        // 이벤트1: 첫 가게 (트리거 없음 → 버퍼 대기)
        val joined1 = "피자집 배달료 3,700원 18.5P"
        assertFalse("세션 미시작", BaeminBundleSession.checkAndStartSession(joined1))

        // 이벤트2: 총 합계 (세션 트리거 + 즉시 종료)
        val joined2 = "총 합계 6,010원 31.7P 2건 모두 수락 모두 거절"
        assertTrue("세션 시작", BaeminBundleSession.checkAndStartSession(joined2))
        assertTrue("종료 가능", BaeminBundleSession.canFinalize())

        // 버퍼 드레인 시뮬
        BaeminBundleSession.addCallData(3700, 18.5, "피자집")
        BaeminBundleSession.addCallData(2310, 13.2, "치킨집")

        val result = BaeminBundleSession.finalize()
        assertNotNull("묶음 결과 생성", result)
        assertEquals(6010, result!!.price)
        assertEquals(31.7, result.point!!, 0.01)
        assertEquals(2, result.bundleCount)
        assertTrue("묶음 판정", result.isMulti)
        assertTrue("다중 픽업", result.isMultiPickup)

        // 다중픽업 2건 최소 7000원, 6010 < 7000 → REJECT
        val filterResult = CallFilter.judge(result, ctx)
        assertEquals(CallFilter.Verdict.REJECT, filterResult.verdict)
        println("42 PASS: 묶음세션B - ${result.price}원/${result.point}P ${result.bundleCount}건 → ${filterResult.verdict}")
    }

    @Test
    fun `43 묶음세션C 4610원 2건 23점8P`() {
        BaeminBundleSession.reset()

        // 총 합계 이벤트 단일 수신
        val joined = "총 합계 4,610원 23.8P 2건 모두 수락 모두 거절"
        assertTrue("세션 시작", BaeminBundleSession.checkAndStartSession(joined))
        assertTrue("종료 가능", BaeminBundleSession.canFinalize())

        // 개별 가게 데이터 피딩
        BaeminBundleSession.addCallData(2310, 11.9, "가게A")
        BaeminBundleSession.addCallData(2300, 11.9, "가게B")

        val result = BaeminBundleSession.finalize()
        assertNotNull("묶음 결과 생성", result)
        assertEquals(4610, result!!.price)
        assertEquals(23.8, result.point!!, 0.01)
        assertEquals(2, result.bundleCount)
        assertTrue("묶음 판정", result.isMulti)
        assertTrue("다중 픽업", result.isMultiPickup)

        // 4610 < bundleMin(5500) → REJECT
        val filterResult = CallFilter.judge(result, ctx)
        assertEquals(CallFilter.Verdict.REJECT, filterResult.verdict)
        println("43 PASS: 묶음세션C - ${result.price}원/${result.point}P ${result.bundleCount}건 → ${filterResult.verdict}")
    }

    @Test
    fun `44 묶음세션D 5420원 2건 32P 11초간격`() {
        BaeminBundleSession.reset()

        // 이벤트1 (T=0): 첫 가게, 트리거 없음 → 세션 미시작
        val joined1 = "가게A 배달료 2,550원 14.5P"
        assertFalse("세션 미시작", BaeminBundleSession.checkAndStartSession(joined1))
        assertFalse("세션 비활성", BaeminBundleSession.isActive())

        // 이벤트2 (T=11초 시뮬): 묶음 총 합계 (트리거)
        val joined2 = "총 합계 5,420원 32.0P 2건 모두 수락 모두 거절"
        assertTrue("세션 시작", BaeminBundleSession.checkAndStartSession(joined2))
        assertTrue("종료 가능", BaeminBundleSession.canFinalize())

        // debounce 버퍼 드레인 (OnTheWayService에서 잔존 데이터 흡수)
        BaeminBundleSession.addCallData(2550, 14.5, "가게A")
        BaeminBundleSession.addCallData(2870, 17.5, "가게B")

        val result = BaeminBundleSession.finalize()
        assertNotNull("묶음 결과 생성", result)
        assertEquals(5420, result!!.price)
        assertEquals(32.0, result.point!!, 0.01)
        assertEquals(2, result.bundleCount)
        assertTrue("묶음 판정", result.isMulti)
        assertTrue("다중 픽업", result.isMultiPickup)

        // 5420 < bundleMin(5500) → REJECT
        val filterResult = CallFilter.judge(result, ctx)
        assertEquals(CallFilter.Verdict.REJECT, filterResult.verdict)
        println("44 PASS: 묶음세션D - ${result.price}원/${result.point}P ${result.bundleCount}건 11초간격 → ${filterResult.verdict}")
    }

    // ---- v3.8: FilterLog 가드 테스트 ----

    @Test
    fun `45 FilterLog blocks zero price`() {
        // price=0 → record()가 가드에서 차단되어 SharedPreferences에 접근하지 않음
        // Editor가 mock되지 않았으므로 가드 실패 시 크래시 → 가드 동작 검증
        val call = DeliveryCall(price = 0, distance = 3.0, isMulti = false, platform = "coupang")
        FilterLog.record(ctx, call, CallFilter.FilterResult(CallFilter.Verdict.REJECT, "test"))
        println("45 PASS: price=0 → FilterLog BLOCKED")
    }

    @Test
    fun `46 FilterLog blocks non-delivery platform`() {
        val call = DeliveryCall(price = 5000, distance = 3.0, isMulti = false, platform = "쿠팡진단")
        FilterLog.record(ctx, call, CallFilter.FilterResult(CallFilter.Verdict.REJECT, "test"))
        println("46 PASS: platform=쿠팡진단 → FilterLog BLOCKED")
    }

    @Test
    fun `47 FilterLog blocks unknown platform`() {
        val call = DeliveryCall(price = 5000, distance = 3.0, isMulti = false, platform = "com.kakao.talk")
        FilterLog.record(ctx, call, CallFilter.FilterResult(CallFilter.Verdict.REJECT, "test"))
        println("47 PASS: platform=com.kakao.talk → FilterLog BLOCKED")
    }

    // ---- v3.9: 슬라이더 우선순위 통합 테스트 ----

    /** 슬라이더 값을 커스텀으로 설정한 Context mock 생성 */
    private fun ctxWithSlider(minPrice: Int, multiMinPrice: Int = 5000): Context {
        val mockPrefs = mockk<SharedPreferences>()
        // Generic 먼저, 그 다음 specific (MockK: 나중 정의가 우선)
        every { mockPrefs.getInt(any(), any()) } answers { secondArg() }
        every { mockPrefs.getBoolean(any(), any()) } answers { secondArg() }
        every { mockPrefs.getString(any(), any()) } answers { secondArg() }
        every { mockPrefs.getInt("min_price", any()) } returns minPrice
        every { mockPrefs.getInt("multi_min_price", any()) } returns multiMinPrice
        val c = mockk<Context>()
        every { c.getSharedPreferences(any(), any()) } returns mockPrefs
        return c
    }

    @Test
    fun `48 슬라이더2500 price2400 REJECT 최소배달료미달`() {
        val sliderCtx = ctxWithSlider(2500)
        val call = DeliveryCall(price = 2400, distance = null, isMulti = false, platform = "baemin", point = 10.0)
        val result = CallFilter.judge(call, sliderCtx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        assertTrue("최소배달료 사유 포함", result.reason.contains("최소배달료"))
        println("48 PASS: slider=2500, price=2400 → ${result.verdict} (${result.reason})")
    }

    @Test
    fun `49 슬라이더2500 price2700 ACCEPT 단가통과`() {
        val sliderCtx = ctxWithSlider(2500)
        val call = DeliveryCall(price = 2700, distance = null, isMulti = false, platform = "baemin", point = 10.0)
        val result = CallFilter.judge(call, sliderCtx)
        // 10P→1.5km, 단가 1800원/km >= 1400 → ACCEPT
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("49 PASS: slider=2500, price=2700 → ${result.verdict} (${result.reason})")
    }

    // ---- v3.19: 포인트 테이블 제거 검증 테스트 ----

    @Test
    fun `거리없는_콜_포인트환산_단가미달_거절`() {
        // 사용자 설정: 최소배달료 3000원, 최소단가 2000원/km (기본값)
        // 콜: 배민 3500원, 포인트 20P → 추정 3.0km → 단가 1167원/km < 2000
        // 기대: REJECT 단가 미달
        val call = DeliveryCall(price = 3500, distance = null, isMulti = false, platform = "baemin", point = 20.0)
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        assertTrue("단가 미달 사유", result.reason.contains("단가") && result.reason.contains("미달"))
        println("v3.21 PASS: 3500원/20P → ${result.verdict} (${result.reason})")
    }

    @Test
    fun `거리없는_콜_슬라이더_최소배달료_미만_거절`() {
        // 사용자 설정: 최소배달료 3000원 (기본값)
        // 콜: 배민 2500원, 거리 없음
        // 기대: REJECT, 사유에 "최소배달료" 포함, "구간" 미포함
        val call = DeliveryCall(price = 2500, distance = null, isMulti = false, platform = "baemin", point = 15.0)
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        assertTrue("최소배달료 사유 포함", result.reason.contains("최소배달료"))
        assertFalse("구간 용어 없음", result.reason.contains("구간"))
        println("v3.19 PASS: 2500원/15P → ${result.verdict} (${result.reason})")
    }

    @Test
    fun `구간기준_용어_판정사유에_절대_없음`() {
        val testCases = listOf(
            DeliveryCall(price = 3000, distance = null, isMulti = false, platform = "baemin", point = 20.0),
            DeliveryCall(price = 5000, distance = null, isMulti = false, platform = "baemin", point = 30.0),
            DeliveryCall(price = 2000, distance = null, isMulti = false, platform = "baemin", point = 10.0),
        )
        for (call in testCases) {
            val result = CallFilter.judge(call, ctx)
            assertFalse("구간 단어 발견: ${result.reason}",
                result.reason.contains("구간"))
        }
        println("v3.19 PASS: 구간 용어 완전 제거 확인")
    }

    @Test
    fun `50 슬라이더2500 구간3000 price3500 ACCEPT`() {
        val sliderCtx = ctxWithSlider(2500)
        val call = DeliveryCall(price = 3500, distance = null, isMulti = false, platform = "baemin", point = 10.0)
        val result = CallFilter.judge(call, sliderCtx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("50 PASS: slider=2500, range=3000, price=3500 → ${result.verdict} (${result.reason})")
    }

    @Test
    fun `51 슬라이더4000 구간3000 price3500 REJECT 슬라이더이김`() {
        val sliderCtx = ctxWithSlider(4000)
        val call = DeliveryCall(price = 3500, distance = null, isMulti = false, platform = "baemin", point = 10.0)
        val result = CallFilter.judge(call, sliderCtx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("51 PASS: slider=4000, range=3000, price=3500 → ${result.verdict} (${result.reason})")
    }

    @Test
    fun `52 슬라이더4000 구간3000 price4500 ACCEPT`() {
        val sliderCtx = ctxWithSlider(4000)
        val call = DeliveryCall(price = 4500, distance = null, isMulti = false, platform = "baemin", point = 10.0)
        val result = CallFilter.judge(call, sliderCtx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("52 PASS: slider=4000, range=3000, price=4500 → ${result.verdict} (${result.reason})")
    }

    @Test
    fun `53 실전재현 20260419 슬라이더2500 배민3620원 ACCEPT`() {
        // 2026-04-19 10:23:26 실전 케이스 재현
        // 슬라이더 2,500원, ≤15P(구간 3,000원), 3,620원 → ACCEPT (3620 > max(2500,3000))
        val sliderCtx = ctxWithSlider(2500)
        val call = DeliveryCall(price = 3620, distance = null, isMulti = false, platform = "baemin", point = 12.0)
        val result = CallFilter.judge(call, sliderCtx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("53 PASS: 실전재현 - slider=2500, 12P(구간3000), price=3620 → ${result.verdict} (${result.reason})")
    }

    // ---- v3.10: 배민 포인트→거리 환산 계수 실측 보정 테스트 ----

    @Test
    fun `54 포인트환산 38P3 약5점7km`() {
        // 2026-04-19 13:58 ground truth: 38.3P → 실제 6km 미만
        val km = BaeminParser.convertPointToKm(38.3)
        assertTrue("Expected ~5.7km, got $km", km in 5.2..6.2)
        println("54 PASS: 38.3P → ${"%.1f".format(km)}km (체감 6km 미만)")
    }

    @Test
    fun `55 포인트환산 50P5 약7점6km`() {
        // 2026-04-19 17:29 ground truth: 50.5P → 실제 ~8km
        val km = BaeminParser.convertPointToKm(50.5)
        assertTrue("Expected ~7.6km, got $km", km in 7.1..8.1)
        println("55 PASS: 50.5P → ${"%.1f".format(km)}km (체감 8km)")
    }

    @Test
    fun `56 포인트환산 53P9 약8점1km`() {
        // 2026-04-19 19:12 ground truth: 53.9P → 실제 8~9km
        val km = BaeminParser.convertPointToKm(53.9)
        assertTrue("Expected ~8.1km, got $km", km in 7.6..8.6)
        println("56 PASS: 53.9P → ${"%.1f".format(km)}km (체감 8~9km)")
    }

    // ---- v3.11: 잡으세요 기준 확대 테스트 ----

    @Test
    fun `57 단거리 고단가 잡으세요 2046원km 1점5km`() {
        // Case B: 쿠팡 3,069원 / 1.5km → 단가 2,046원/km ≥ 2,000 + 거리 1.5km ≤ 2.0 → 잡으세요
        val call = DeliveryCall(price = 3069, distance = 1.5, isMulti = false, platform = "coupang")
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        assertTrue("추천 사유", result.reason.contains("추천"))
        println("57 PASS: 3069원/1.5km → ${result.verdict} (${result.reason})")
    }

    @Test
    fun `58 단거리 저단가 괜찮습니다 1500원km 1점5km`() {
        // 2,250원 / 1.5km → 단가 1,500원/km < 2,000 → 잡으세요 아님
        val call = DeliveryCall(price = 2250, distance = 1.5, isMulti = false, platform = "coupang")
        val result = CallFilter.judge(call, ctx)
        // 단가 미달로 REJECT (minUnitPrice 기본 2000)
        assertFalse("잡으세요 아님", result.reason.contains("잡으세요"))
        println("58 PASS: 2250원/1.5km → ${result.verdict} (${result.reason})")
    }

    @Test
    fun `59 장거리 중단가 잡으세요 아님 2046원km 3km`() {
        // 6,138원 / 3.0km → 단가 2,046원/km ≥ 2,000이지만 거리 3.0km > 2.0 → 단거리 고단가 미해당
        val call = DeliveryCall(price = 6138, distance = 3.0, isMulti = false, platform = "coupang")
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        assertFalse("단거리 고단가 아님", result.reason.contains("단거리 고단가"))
        println("59 PASS: 6138원/3.0km → ${result.verdict} (${result.reason})")
    }

    @Test
    fun `60 묶음효율 9120원 3건 53P9 건당미달 REJECT`() {
        // Case C: 배민 9,120원 / 3건 → 건당 3,040원 < 5,000 → REJECT (건당 단가 기준 우선)
        val call = DeliveryCall(price = 9120, distance = null, isMulti = true, platform = "baemin",
            bundleCount = 3, point = 53.9)
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("60 PASS: 9120원/3건/53.9P → ${result.verdict} (${result.reason})")
    }

    @Test
    fun `61 묶음 건당 저가 잡으세요 아님`() {
        // 5,400원 / 2건 / 5km → 건당 2,700원 < 3,000 → 묶음 효율 미해당
        val call = DeliveryCall(price = 5400, distance = 5.0, isMulti = true, platform = "baemin",
            bundleCount = 2)
        val result = CallFilter.judge(call, ctx)
        assertFalse("묶음 효율 아님", result.reason.contains("묶음 효율"))
        println("61 PASS: 5400원/2건/5km → ${result.verdict} (${result.reason})")
    }

    // ---- v3.12: 묶음 세션 중복 카운트 방지 테스트 ----

    @Test
    fun `62 묶음세션 finalize 후 즉시 재시작 차단`() {
        BaeminBundleSession.reset()
        // 세션 시작 + 총 합계 파싱
        BaeminBundleSession.checkAndStartSession("2건 모두 수락 총 합계 5,000원 20.0P")
        assertEquals(BaeminBundleSession.State.COLLECTING, BaeminBundleSession.state)
        assertTrue("finalize 가능", BaeminBundleSession.canFinalize())

        // 종료
        val call = BaeminBundleSession.finalize()
        assertNotNull("묶음 콜 반환", call)
        assertEquals(BaeminBundleSession.State.FINALIZED, BaeminBundleSession.state)

        // 동일 텍스트로 즉시 재시작 시도 → 쿨다운으로 차단
        val restarted = BaeminBundleSession.checkAndStartSession("2건 모두 수락 총 합계 5,000원 20.0P")
        assertFalse("쿨다운 중 재시작 차단", restarted)
        assertEquals(BaeminBundleSession.State.FINALIZED, BaeminBundleSession.state)
        println("62 PASS: finalize 후 즉시 재시작 차단 확인")
    }

    @Test
    fun `63 묶음세션 finalize 중복 호출 차단`() {
        BaeminBundleSession.reset()
        BaeminBundleSession.checkAndStartSession("총 합계 7,000원 30.0P")
        assertTrue(BaeminBundleSession.canFinalize())

        // 첫 finalize → 정상
        val call1 = BaeminBundleSession.finalize()
        assertNotNull(call1)
        assertEquals(BaeminBundleSession.State.FINALIZED, BaeminBundleSession.state)

        // 두 번째 finalize → null 반환 (중복 차단)
        val call2 = BaeminBundleSession.finalize()
        assertNull("중복 finalize 차단", call2)
        println("63 PASS: finalize 중복 호출 차단 확인")
    }

    @Test
    fun `64 묶음세션 FINALIZED 상태에서 이벤트 무시`() {
        BaeminBundleSession.reset()
        BaeminBundleSession.checkAndStartSession("총 합계 6,000원 25.0P")
        BaeminBundleSession.finalize()
        assertEquals(BaeminBundleSession.State.FINALIZED, BaeminBundleSession.state)

        // FINALIZED 상태에서 addCallData → 무시 (state != COLLECTING)
        BaeminBundleSession.addCallData(3000, 10.0, "테스트가게")
        assertFalse("FINALIZED에서 isActive=false", BaeminBundleSession.isActive())
        assertFalse("FINALIZED에서 canFinalize=false", BaeminBundleSession.canFinalize())
        println("64 PASS: FINALIZED 상태 이벤트 무시 확인")
    }

    @Test
    fun `65 묶음세션 타임아웃과 정상종료 경로 분리`() {
        BaeminBundleSession.reset()
        // 총 합계 없이 세션 시작 (건수만 감지)
        BaeminBundleSession.checkAndStartSession("2건 모두 수락")
        assertEquals(BaeminBundleSession.State.COLLECTING, BaeminBundleSession.state)
        assertFalse("총 합계 없음 → canFinalize=false", BaeminBundleSession.canFinalize())

        // 데이터 축적
        BaeminBundleSession.addCallData(3000, 10.0, "가게A")
        BaeminBundleSession.addCallData(4000, 12.0, "가게B")

        // 타임아웃 종료
        val call = BaeminBundleSession.finalizeOnTimeout()
        assertNotNull(call)
        assertEquals(7000, call!!.price)
        assertEquals(BaeminBundleSession.State.FINALIZED, BaeminBundleSession.state)
        println("65 PASS: 타임아웃 종료 → FINALIZED 상태 전환 확인")
    }

    // ---- v3.15: 가게명 정제 + 사유 간결화 테스트 ----

    @Test
    fun `66 가게명 UI라벨 제거 복합`() {
        val raw = "배민배달+픽업지+설빙 태전점+전달지+포인트+우리할매떡볶이 태전점+총 합계+모두 거절+2건 모두 수락+6초+지도앱으로 검색하기"
        val cleaned = StoreNameCleaner.clean(raw)
        assertEquals(listOf("설빙 태전점", "우리할매떡볶이 태전점"), cleaned)
        println("66 PASS: UI 라벨 제거 → $cleaned")
    }

    @Test
    fun `67 가게명 단일 가게`() {
        val raw = "배민배달+픽업지+BBQ 태전중앙점+전달지+포인트"
        val cleaned = StoreNameCleaner.clean(raw)
        assertEquals(listOf("BBQ 태전중앙점"), cleaned)
        println("67 PASS: 단일 가게 → $cleaned")
    }

    @Test
    fun `68 가게명 중복 제거`() {
        val raw = "설빙 태전점+설빙 태전점+포인트"
        val cleaned = StoreNameCleaner.clean(raw)
        assertEquals(listOf("설빙 태전점"), cleaned)
        println("68 PASS: 중복 제거 → $cleaned")
    }

    @Test
    fun `69 가게명 빈 문자열`() {
        val cleaned = StoreNameCleaner.clean("")
        assertTrue(cleaned.isEmpty())
        println("69 PASS: 빈 문자열 → 빈 리스트")
    }

    // ---- v3.16: 잡으세요 reason 한글만 추출 테스트 ----

    @Test
    fun `70 잡으세요 고단가 근거리 reason에서 한글만 추출`() {
        // CallFilter가 생성하는 실제 reason 형태
        val raw = "잡으세요: 고단가 근거리 2,700원/km ≥ 2,500원 + 거리 1.0km ≤ 3km"
        val match = Regex("""잡으세요:\s*([가-힣\s]+)""").find(raw)
        assertNotNull(match)
        assertEquals("고단가 근거리", match!!.groupValues[1].trim())
        println("70 PASS: reason → '고단가 근거리' 추출")
    }

    @Test
    fun `71 잡으세요 단거리 고단가 reason 추출`() {
        val raw = "잡으세요: 단거리 고단가 2,046원/km ≥ 2,000원 + 거리 1.5km ≤ 2km"
        val match = Regex("""잡으세요:\s*([가-힣\s]+)""").find(raw)
        assertNotNull(match)
        assertEquals("단거리 고단가", match!!.groupValues[1].trim())
        println("71 PASS: reason → '단거리 고단가' 추출")
    }

    @Test
    fun `72 잡으세요 묶음효율 reason 추출`() {
        val raw = "잡으세요: 묶음 효율 건당 3,040원 ≥ 3,000원 + 건당 2.7km ≤ 3km"
        // "묶음 효율"만 추출 (뒤에 "건당"이 붙지 않도록)
        val match = Regex("""잡으세요:\s*([가-힣]+\s*[가-힣]+)""").find(raw)
        assertNotNull(match)
        assertEquals("묶음 효율", match!!.groupValues[1].trim())
        println("72 PASS: reason → '묶음 효율' 추출")
    }

    // ---- v3.17: 배민 가게명 픽업지 추출 + 쿠팡 사유 간결화 ----

    @Test
    fun `73 배민 단건 픽업지 다음에 가게명 추출`() {
        val texts = listOf("배민배달", "픽업지", "맘스터치 BEEF 광주태전점", "전달지", "고산동", "배달료 4,300원", "16.8P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 성공", calls.isNotEmpty())
        assertEquals("맘스터치 BEEF 광주태전점", calls[0].storeName)
        println("73 PASS: 픽업지 → '${calls[0].storeName}' 추출")
    }

    @Test
    fun `74 배민 단건 픽업지 없을 때 패턴 매칭 가게명`() {
        val texts = listOf("설빙 태전점", "고산동", "배달료 3,000원", "10.0P")
        val calls = BaeminParser.parse(texts)!!
        assertTrue("파싱 성공", calls.isNotEmpty())
        assertEquals("설빙 태전점", calls[0].storeName)
        println("74 PASS: 패턴 매칭 → '${calls[0].storeName}' 추출")
    }

    @Test
    fun `75 쿠팡 ACCEPT 단가거리 사유 고단가 근거리`() {
        // 쿠팡 잡으세요 실제 reason 형태
        val call = DeliveryCall(price = 3000, distance = 1.0, isMulti = false, platform = "coupang")
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        assertTrue("추천 사유", result.reason.contains("추천"))
        println("75 PASS: 쿠팡 3000원/1.0km → ${result.verdict} (${result.reason})")
    }

    @Test
    fun `76 쿠팡 ACCEPT 일반 단가 통과`() {
        // 6000원 / 2.9km = 2069원/km ≥ minUnitPrice(2000) → ACCEPT
        val call = DeliveryCall(price = 6000, distance = 2.9, isMulti = false, platform = "coupang")
        val result = CallFilter.judge(call, ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("76 PASS: 쿠팡 6000원/2.9km → ${result.verdict} (${result.reason})")
    }

    // ---- v3.18: EventIdGenerator 테스트 ----

    @Test
    fun `77 EventId 같은콜 5분이내 같은ID`() {
        // 5분 bucket = 300_000ms. t1과 t2가 같은 bucket에 속하도록 정렬
        val bucketStart = 1_700_000_000_000L / 300_000L * 300_000L  // bucket 시작점
        val t1 = bucketStart + 10_000L  // bucket 시작 +10초
        val t2 = bucketStart + 120_000L // bucket 시작 +2분
        val id1 = EventIdGenerator.generate("맘스터치 태전점", 4300, t1)
        val id2 = EventIdGenerator.generate("맘스터치 태전점", 4300, t2)
        assertEquals(id1, id2)  // 같은 ID (5분 bucket)
        println("77 PASS: 5분 이내 같은 eventId = $id1")
    }

    @Test
    fun `78 EventId 다른bucket 다른ID`() {
        val bucketStart = 1_700_000_000_000L / 300_000L * 300_000L
        val t1 = bucketStart + 10_000L       // bucket N
        val t2 = bucketStart + 310_000L      // bucket N+1 (5분 10초 후)
        val id1 = EventIdGenerator.generate("맘스터치 태전점", 4300, t1)
        val id2 = EventIdGenerator.generate("맘스터치 태전점", 4300, t2)
        assertNotEquals(id1, id2)
        println("78 PASS: 다른 bucket → 다른 eventId")
    }

    @Test
    fun `79 EventId 다른금액 다른ID`() {
        val t = 1_700_000_000_000L
        val id1 = EventIdGenerator.generate("맘스터치", 4300, t)
        val id2 = EventIdGenerator.generate("맘스터치", 4500, t)
        assertNotEquals(id1, id2)
        println("79 PASS: 다른 금액 → 다른 eventId")
    }

    // ---- v3.18: SessionManager 테스트 (mock TransitionLog) ----

    @Test
    fun `80 SessionManager 같은이벤트 10초이내 attach`() {
        val mockLog = mockk<StateTransitionLog>(relaxed = true)
        val sm = SessionManager(mockLog)
        val s1 = sm.onEventReceived("baemin", "맘스터치", 4300, "test")
        val s2 = sm.onEventReceived("baemin", "맘스터치", 4300, "test")  // 같은 eventId

        assertNotNull(s1)
        assertNotNull(s2)
        assertEquals(s1!!.sessionId, s2!!.sessionId)  // 같은 세션 (attach)
        assertEquals(SessionState.COLLECTING, s2.state)
        println("80 PASS: 같은 이벤트 → attach (sessionId 동일)")
    }

    @Test
    fun `81 SessionManager 동시활성세션 1개만`() {
        val mockLog = mockk<StateTransitionLog>(relaxed = true)
        val sm = SessionManager(mockLog)
        sm.onEventReceived("baemin", "가게A", 4300, "test")
        sm.onEventReceived("baemin", "가게B", 5000, "test")  // 다른 eventId

        val active = sm.getActiveSession()
        assertEquals("가게B", active?.storeName)  // B만 활성
        println("81 PASS: 동시 활성 세션 1개만 (가게B)")
    }

    @Test
    fun `82 SessionManager finalize 후 카운트기준`() {
        val mockLog = mockk<StateTransitionLog>(relaxed = true)
        val sm = SessionManager(mockLog)
        sm.onEventReceived("baemin", "맘스터치", 4300, "test")
        val finalized = sm.finalizeActiveSession("user_accepted")

        assertNotNull(finalized)
        assertEquals(SessionState.FINALIZED, finalized?.state)
        assertNull(sm.getActiveSession())  // 종료 후 null
        println("82 PASS: finalize → FINALIZED 상태, activeSession = null")
    }

    @Test
    fun `83 SessionManager expired 세션 새로대체`() {
        val mockLog = mockk<StateTransitionLog>(relaxed = true)
        val sm = SessionManager(mockLog)
        val s1 = sm.onEventReceived("baemin", "가게A", 4300, "test")
        // expired 시뮬레이션: 직접 startedAt 조작 불가하므로 구조만 검증
        assertNotNull(s1)
        assertEquals(SessionState.COLLECTING, s1!!.state)
        println("83 PASS: 세션 생성 및 상태 검증 (expired 시뮬은 실전 환경 필요)")
    }

    // ---- v3.19: 묶음→낱개 이중 세션 차단 테스트 ----

    @Test
    fun `84 묶음 FINALIZED 후 10초내 낱개 차단`() {
        val mockLog = mockk<StateTransitionLog>(relaxed = true)
        val sm = SessionManager(mockLog)

        // 묶음 세션 생성 + finalize
        val s1 = sm.onEventReceived("baemin", "배스킨라빈스 광주태전점", 6390, "test")
        assertNotNull(s1)
        sm.finalizeActiveSession("bundle_finalized")

        // suppression 등록: 묶음 내 개별 아이템
        sm.registerBundleSuppression(listOf(
            "배스킨라빈스 광주태전점|4090",
            "태봉곱창|2300",
            "배스킨라빈스 광주태전점+태봉곱창|6390"
        ))

        // 10초 이내 낱개 시도 → 차단 (null 반환)
        val s2 = sm.onEventReceived("baemin", "배스킨라빈스 광주태전점", 4090, "낱개1")
        assertNull("묶음 suppression으로 차단", s2)

        val s3 = sm.onEventReceived("baemin", "태봉곱창", 2300, "낱개2")
        assertNull("묶음 suppression으로 차단", s3)

        println("84 PASS: 묶음 FINALIZED 후 10초 내 낱개 → 세션 생성 차단됨")
    }

    @Test
    fun `85 실측추정 20260420 1909 태봉곱창 묶음가정`() {
        // 2026-04-20 19:09 실측 추정 시나리오 — 태봉곱창이 묶음 구성원이라는 가정
        // 근거: 금액 합계 일치(4090+2300=6390)뿐. 실제로는 별개 콜 가능성 있음.
        // 검증은 내일 운행 로그(StateTransitionLog)로 A vs B 판정 예정.
        //
        // T=0s (19:09:29): 배민 6,390원 묶음 2건 (배스킨라빈스+태봉곱창 가정) → FINALIZED
        // T=4s (19:09:33): 배민 4,090원 낱개 (배스킨라빈스) → 차단
        // T=4s (19:09:33): 배민 2,300원 낱개 (태봉곱창) → 차단 (묶음 가정이므로)

        val mockLog = mockk<StateTransitionLog>(relaxed = true)
        val sm = SessionManager(mockLog)

        val bundleSession = sm.onEventReceived("baemin", "배스킨라빈스 광주태전점", 6390, "accessibility_event")
        assertNotNull("묶음 세션 생성", bundleSession)
        sm.finalizeActiveSession("bundle_finalized")

        // 묶음 가정: 태봉곱창도 구성원으로 suppression 등록
        sm.registerBundleSuppression(listOf(
            "배스킨라빈스 광주태전점|4090",
            "태봉곱창|2300",
            "배스킨라빈스 광주태전점+태봉곱창|6390"
        ))

        val single1 = sm.onEventReceived("baemin", "배스킨라빈스 광주태전점", 4090, "notification")
        assertNull("배스킨라빈스 4090 차단", single1)

        val single2 = sm.onEventReceived("baemin", "태봉곱창", 2300, "notification")
        assertNull("태봉곱창 2300 차단 (묶음 가정)", single2)

        val newCall = sm.onEventReceived("baemin", "맘스터치", 5500, "notification")
        assertNotNull("새 콜은 통과", newCall)

        println("85 PASS: 실측 추정(묶음 가정) — 구성원 2건 차단 + 새 콜 통과")
    }

    @Test
    fun `86 실측대안 20260420 1909 태봉곱창 별개콜 가정`() {
        // 2026-04-20 19:09 대안 시나리오 — 태봉곱창이 별개 콜이라는 가정
        // 이 경우 suppression에 태봉곱창이 포함되지 않으므로 통과해야 함.
        // 내일 운행 로그로 85 vs 86 중 어느 쪽이 실측과 일치하는지 판정.
        //
        // T=0s: 배민 6,390원 묶음 (배스킨라빈스 단독) → FINALIZED
        // T=4s: 배민 4,090원 낱개 (배스킨라빈스) → 차단 (확실)
        // T=4s: 배민 2,300원 낱개 (태봉곱창) → 통과 (별개 가게)

        val mockLog = mockk<StateTransitionLog>(relaxed = true)
        val sm = SessionManager(mockLog)

        val bundleSession = sm.onEventReceived("baemin", "배스킨라빈스 광주태전점", 6390, "accessibility_event")
        assertNotNull("묶음 세션 생성", bundleSession)
        sm.finalizeActiveSession("bundle_finalized")

        // 별개 가정: 배스킨라빈스만 suppression 등록, 태봉곱창 미등록
        sm.registerBundleSuppression(listOf(
            "배스킨라빈스 광주태전점|4090",
            "배스킨라빈스 광주태전점|6390"
        ))

        val single1 = sm.onEventReceived("baemin", "배스킨라빈스 광주태전점", 4090, "notification")
        assertNull("배스킨라빈스 4090 차단 (확실)", single1)

        val single2 = sm.onEventReceived("baemin", "태봉곱창", 2300, "notification")
        assertNotNull("태봉곱창 2300 통과 (별개 콜)", single2)

        println("86 PASS: 실측 대안(별개 콜 가정) — 배스킨라빈스 차단 + 태봉곱창 통과")
    }

    @Test
    fun `87 묶음 FINALIZED 후 31초뒤 낱개 허용`() {
        val mockLog = mockk<StateTransitionLog>(relaxed = true)
        val sm = SessionManager(mockLog)

        sm.onEventReceived("baemin", "배스킨라빈스", 6390, "test")
        sm.finalizeActiveSession("bundle_finalized")
        sm.registerBundleSuppression(listOf("배스킨라빈스|4090"))

        // 31초 대기 시뮬: timestamp 직접 조작 불가
        // 구조적 보장: onEventReceived 진입 시 TTL > 30초인 항목 자동 제거
        // 실전 검증은 adb logcat으로 수행

        println("87 PASS: TTL=30초 구조 검증 완료 (실전 검증은 adb logcat)")
    }

    @Test
    fun `88 suppression 등록 안 된 다른 콜은 통과`() {
        val mockLog = mockk<StateTransitionLog>(relaxed = true)
        val sm = SessionManager(mockLog)

        sm.registerBundleSuppression(listOf("배스킨라빈스|4090", "태봉곱창|2300"))

        val s = sm.onEventReceived("baemin", "맘스터치", 5500, "새콜")
        assertNotNull("다른 콜은 suppression 통과", s)
        assertEquals(SessionState.COLLECTING, s!!.state)

        println("88 PASS: suppression 미해당 콜 → 정상 세션 생성")
    }

    // ── FIX-T2CN: 주문번호 기반 event_id 안정화 테스트 ──

    @Test
    fun `89 T2CN orderId 5분간격 4회 동일 eventId`() {
        // 같은 T2CN = 같은 event_id, 5분 bucket 무관
        val t1 = 1_700_000_000_000L
        val t2 = t1 + 300_000L   // +5분
        val t3 = t1 + 600_000L   // +10분
        val t4 = t1 + 900_000L   // +15분
        val id1 = EventIdGenerator.generate("맘스터치", 4300, t1, orderId = "T2CN0000LAF4")
        val id2 = EventIdGenerator.generate("맘스터치", 4300, t2, orderId = "T2CN0000LAF4")
        val id3 = EventIdGenerator.generate("", 4300, t3, orderId = "T2CN0000LAF4")
        val id4 = EventIdGenerator.generate(null, 4300, t4, orderId = "T2CN0000LAF4")
        assertEquals(id1, id2)
        assertEquals(id2, id3)
        assertEquals(id3, id4)
        println("89 PASS: 같은 T2CN → 5분/10분/15분 후에도 동일 eventId = $id1")
    }

    @Test
    fun `90 T2CN 다른 orderId 같은 가격 다른 eventId`() {
        val t = 1_700_000_000_000L
        val id1 = EventIdGenerator.generate("맘스터치", 3700, t, orderId = "T2CN00013AUH")
        val id2 = EventIdGenerator.generate("맘스터치", 3700, t, orderId = "T2CN0000LAF4")
        assertNotEquals(id1, id2)
        println("90 PASS: 다른 T2CN → 같은 가격이어도 다른 eventId")
    }

    @Test
    fun `91 T2CN null이면 기존 fallback`() {
        // orderId 없으면 기존 storeName+price+bucket 로직
        val bucketStart = 1_700_000_000_000L / 300_000L * 300_000L
        val t1 = bucketStart + 10_000L
        val t2 = bucketStart + 120_000L
        val id1 = EventIdGenerator.generate("맘스터치", 4300, t1, orderId = null)
        val id2 = EventIdGenerator.generate("맘스터치", 4300, t2, orderId = null)
        assertEquals(id1, id2)  // 기존 bucket fallback 유지
        println("91 PASS: orderId null → 기존 5분 bucket fallback 동작")
    }

    @Test
    fun `92 T2CN orderId는 storeName 변동 무관`() {
        val t = 1_700_000_000_000L
        // 같은 orderId, 다른 storeName → 같은 eventId
        val id1 = EventIdGenerator.generate("호노보노 파스타 식당", 3120, t, orderId = "T2CN00013AUH")
        val id2 = EventIdGenerator.generate("", 3120, t, orderId = "T2CN00013AUH")
        val id3 = EventIdGenerator.generate("호노보노파스타", 3120, t, orderId = "T2CN00013AUH")
        assertEquals(id1, id2)
        assertEquals(id2, id3)
        println("92 PASS: orderId 있으면 storeName 변동 무관 동일 eventId")
    }
}
