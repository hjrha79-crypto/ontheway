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
    }

    // ---- 배민 단일 콜 ----

    @Test
    fun `01 배민 저가 3000원 8P REJECT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 3,000원", "8.0P")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertEquals(3000, calls[0].price)
        assertEquals(8.0, calls[0].point!!, 0.01)
        // 8P = 2km, 단가 1500원/km < 2000 -> REJECT
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
        // 10P = 2.5km, 단가 2200원/km >= 2000 -> ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("02 PASS: ${result.reason}")
    }

    @Test
    fun `03 배민 고가 10000원 16P ACCEPT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 10,000원", "16.0P")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 10000 >= 7000 -> 고액 보호 -> ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("03 PASS: ${result.reason}")
    }

    @Test
    fun `04 배민 고액보호 7500원 20P ACCEPT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 7,500원", "20.0P")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 7500 >= 7000 -> 고액 보호 -> ACCEPT
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
        // 6P = 1.5km, 단가 2333원/km >= 2000 -> ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("05 PASS: ${result.reason}")
    }

    @Test
    fun `06 배민 고포인트 4000원 30P REJECT`() {
        val texts = listOf("맘스터치", "역삼동", "배달료 4,000원", "30.0P")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 30P > 25 -> 포인트구간 최소 5000원, 4000 < 5000 -> REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("06 PASS: ${result.reason}")
    }

    // ---- 배민 묶음 콜 ----

    @Test
    fun `07 배민 묶음 2건 단일픽업 5500원 ACCEPT`() {
        // 묶음은 results.size >= 2 로 판정, bundleCount = results.size
        val texts = listOf("맘스터치", "배달료 3,000원", "배달료 2,500원")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertTrue("묶음 판정 실패", calls[0].isMulti)
        assertEquals(5500, calls[0].price)
        assertEquals(2, calls[0].bundleCount)
        // bundleMin = 3000+(2-1)*2500 = 5500, 5500 >= 5500 -> ACCEPT
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
        // 4500 < bundleMin(5500) -> REJECT
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
        // multiPickupMin(2건) = 7000, effectiveMin = max(5500, 7000) = 7000 -> ACCEPT
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
        // bundleMin = 3000+(3-1)*2500 = 8000 -> ACCEPT
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
        val calls = BaeminParser.parse(texts)
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
        val calls = BaeminParser.parse(texts)
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
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 8P = 2.0km, 단가 4000/2.0 = 2000 >= 2000 -> ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("21 PASS: ${result.reason}")
    }

    @Test
    fun `22 배민 포인트구간 경계 16P 4000원 REJECT`() {
        val texts = listOf("맘스터치", "배달료 4,000원", "16.0P")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 16P → 16~25P 구간, 최소 4000원, 4000 ≤ 4000 → REJECT (초과만 ACCEPT)
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("22 PASS: ${result.reason}")
    }

    @Test
    fun `23 배민 고액 8000원 12P ACCEPT`() {
        val texts = listOf("맘스터치", "배달료 8,000원", "12.0P")
        val calls = BaeminParser.parse(texts)
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
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 7500 >= 7000 -> 고액 보호 -> ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("24 PASS: ${result.reason}")
    }

    // ---- 추가: 포인트 구간 경계값 ----

    @Test
    fun `25 배민 포인트 경계 15P 3000원 REJECT`() {
        val texts = listOf("맘스터치", "배달료 3,000원", "15.0P")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 15P=3.75km, pointMinPrice=3000, 3000>=3000 OK
        // 환산단가 3000/3.75=800 < 2000 -> REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("25 PASS: ${result.reason}")
    }

    @Test
    fun `26 배민 포인트 16P 구간 3500원 REJECT`() {
        val texts = listOf("맘스터치", "배달료 3,500원", "16.0P")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 16P=4km, 15<16<=25 -> pointMinPrice=4000, 3500<4000 -> REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("26 PASS: ${result.reason}")
    }

    @Test
    fun `27 배민 포인트 경계 25P 4000원 REJECT`() {
        val texts = listOf("맘스터치", "배달료 4,000원", "25.0P")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 25P=6.25km, pointMinPrice=4000, 4000>=4000 OK
        // 환산단가 4000/6.25=640 < 2000 -> REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("27 PASS: ${result.reason}")
    }

    @Test
    fun `28 배민 포인트 26P 구간 4500원 REJECT`() {
        val texts = listOf("맘스터치", "배달료 4,500원", "26.0P")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        // 26P>25 -> pointMinPrice=5000, 4500<5000 -> REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("28 PASS: ${result.reason}")
    }

    // ---- 추가: 묶음 다중픽업 경계값 ----

    @Test
    fun `29 배민 묶음 2건 다중픽업 6900원 REJECT`() {
        val texts = listOf("맘스터치", "버거킹", "배달료 3,900원", "배달료 3,000원")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertTrue("다중픽업 판정 실패", calls[0].isMultiPickup)
        assertEquals(6900, calls[0].price)
        // multiPickupMin(2건)=7000, 6900<7000 -> REJECT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.REJECT, result.verdict)
        println("29 PASS: ${result.reason}")
    }

    @Test
    fun `30 배민 묶음 3건 다중픽업 10000원 ACCEPT`() {
        val texts = listOf("맘스터치", "버거킹", "배달료 4,000원", "배달료 3,500원", "배달료 2,500원")
        val calls = BaeminParser.parse(texts)
        assertTrue("파싱 실패", calls.isNotEmpty())
        assertTrue("다중픽업 판정 실패", calls[0].isMultiPickup)
        assertEquals(10000, calls[0].price)
        // multiPickupMin(3건)=10000, effectiveMin=max(8000,10000)=10000 -> ACCEPT
        val result = CallFilter.judge(calls[0], ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, result.verdict)
        println("30 PASS: ${result.reason}")
    }

    @Test
    fun `31 배민 묶음 3건 다중픽업 9500원 REJECT`() {
        val texts = listOf("맘스터치", "버거킹", "배달료 4,000원", "배달료 3,300원", "배달료 2,200원")
        val calls = BaeminParser.parse(texts)
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
        val calls = BaeminParser.parse(texts)
        assertTrue("비콜이어야 함", calls.isEmpty())
        println("35 PASS: 배민 가게정보 비콜 필터링 성공")
    }

    @Test
    fun `36 배민 비콜 주행기록기반`() {
        val texts = listOf("주행기록 기반", "운행 분석")
        val calls = BaeminParser.parse(texts)
        assertTrue("비콜이어야 함", calls.isEmpty())
        println("36 PASS: 배민 주행기록 비콜 필터링 성공")
    }

    @Test
    fun `37 배민 비콜 배달완료`() {
        val texts = listOf("배달 완료", "수고하셨습니다")
        val calls = BaeminParser.parse(texts)
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
        val calls = BaeminParser.parse(texts)
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

        // CallFilter 판정
        val filterResult = CallFilter.judge(result, ctx)
        assertEquals(CallFilter.Verdict.ACCEPT, filterResult.verdict)
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
}
