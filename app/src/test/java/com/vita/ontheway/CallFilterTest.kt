package com.vita.ontheway

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * CallFilter 판정 로직 유닛테스트
 * - 배민 단건 단가 체크 복원 검증
 * - 묶음 사유에 "슬라이더" 단어 미포함 검증
 */
class CallFilterTest {

    private lateinit var ctx: Context

    /** 커스텀 설정값으로 Context mock 생성 */
    private fun mockContext(
        minPrice: Int = 2500,
        minUnitPrice: Int = 1200,
        multiMinPrice: Int = 5000,
        highPriceThreshold: Int = 7000,
        grabThreshold: Int = 10000
    ): Context {
        val mockPrefs = mockk<SharedPreferences>()
        every { mockPrefs.getInt("min_price", any()) } returns minPrice
        every { mockPrefs.getInt("min_unit_price", any()) } returns minUnitPrice
        every { mockPrefs.getInt("multi_min_price", any()) } returns multiMinPrice
        every { mockPrefs.getInt("high_price_threshold", any()) } returns highPriceThreshold
        every { mockPrefs.getInt("grab_threshold", any()) } returns grabThreshold
        // 기타 설정은 기본값 반환
        every { mockPrefs.getInt(not(match { it in listOf("min_price", "min_unit_price", "multi_min_price", "high_price_threshold", "grab_threshold") }), any()) } answers { secondArg() }
        every { mockPrefs.getBoolean(any(), any()) } answers { secondArg() }
        every { mockPrefs.getString(any(), any()) } answers { secondArg() }

        val c = mockk<Context>()
        every { c.getSharedPreferences(any(), any()) } returns mockPrefs
        return c
    }

    @Before
    fun setup() {
        ctx = mockContext()
    }

    // ---- 배민 단건 단가 체크 ----

    @Test
    fun `배민 단건 거리없음 5030원 29P ACCEPT 가격기준`() {
        // Fix IT-3: point*0.15 미사용 → 5030 >= 3000(minPrice) → ACCEPT
        val call = DeliveryCall(
            price = 5030, distance = null, isMulti = false,
            platform = "baemin", point = 29.1
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals("가격 기준 ACCEPT", CallFilter.Verdict.ACCEPT, result.verdict)
        assertTrue("최소배달료 통과 사유", result.reason.contains("최소배달료 통과"))
        assertFalse("추정거리 없어야 함", result.reason.contains("추정거리"))
        assertFalse("거리 추정 없어야 함", result.reason.contains("거리 추정"))
        println("PASS: ${result.reason}")
    }

    @Test
    fun `배민 단건 단가 통과 ACCEPT - 4960원 23P`() {
        // 4,960원 / 23.8P → 추정 3.57km → 단가 1,389원/km > 1,200원
        // minPrice=2500이므로 금액도 통과
        val call = DeliveryCall(
            price = 4960, distance = null, isMulti = false,
            platform = "baemin", point = 23.8
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals("단가 통과이어야 함", CallFilter.Verdict.ACCEPT, result.verdict)
        println("PASS: ${result.reason}")
    }

    @Test
    fun `배민 단건 최소배달료 미달 REJECT - minPrice 5000원`() {
        // minPrice=5000으로 설정 시, 4960원 < 5000원 → 최소배달료 미달 (단가 체크 전에 걸림)
        val highMinCtx = mockContext(minPrice = 5000)
        val call = DeliveryCall(
            price = 4960, distance = null, isMulti = false,
            platform = "baemin", point = 23.8
        )
        val result = CallFilter.judge(call, highMinCtx)
        assertEquals("최소배달료 미달이어야 함", CallFilter.Verdict.REJECT, result.verdict)
        assertTrue("사유에 '최소배달료' 포함", result.reason.contains("최소배달료"))
        println("PASS: ${result.reason}")
    }

    // ---- 묶음 사유 슬라이더 단어 제거 ----

    @Test
    fun `배민 묶음 5000원 미만 REJECT - 사유에 슬라이더 없음`() {
        val call = DeliveryCall(
            price = 4960, distance = null, isMulti = true,
            platform = "baemin", bundleCount = 2
        )
        val result = CallFilter.judge(call, ctx)
        assertEquals("묶음 미달 REJECT", CallFilter.Verdict.REJECT, result.verdict)
        assertFalse("사유에 '슬라이더' 미포함", result.reason.contains("슬라이더"))
        assertTrue("사유에 '묶음 최소' 포함", result.reason.contains("묶음 최소"))
        println("PASS: ${result.reason}")
    }
}
