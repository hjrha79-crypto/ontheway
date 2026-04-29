package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test

/**
 * 배민 묶음→단건 중복 방지 로직 단위 테스트.
 * OnTheWayService.processDeliveryCall 내 조건 로직을 순수 함수로 검증.
 */
class BundleDedupTest {

    /**
     * 묶음→단건 중복 판정 조건 (OnTheWayService에서 추출한 순수 로직).
     */
    private fun shouldDropAsBundleSingleDup(
        platform: String,
        isMulti: Boolean,
        bundleCount: Int,
        lastBundleTime: Long,
        lastBundlePrice: Int,
        now: Long
    ): Boolean {
        return platform == "baemin" && !isMulti && bundleCount <= 1
            && lastBundleTime > 0 && now - lastBundleTime < 10_000
    }

    @Test
    fun `묶음 5730원 후 10초 내 단건 2300원 → DROP`() {
        val bundleTime = 1000000L
        val singleTime = bundleTime + 5000  // 5초 후
        assertTrue(shouldDropAsBundleSingleDup(
            platform = "baemin",
            isMulti = false,
            bundleCount = 1,
            lastBundleTime = bundleTime,
            lastBundlePrice = 5730,
            now = singleTime
        ))
    }

    @Test
    fun `묶음 5730원 후 11초 후 단건 → 허용`() {
        val bundleTime = 1000000L
        val singleTime = bundleTime + 11000  // 11초 후
        assertFalse(shouldDropAsBundleSingleDup(
            platform = "baemin",
            isMulti = false,
            bundleCount = 1,
            lastBundleTime = bundleTime,
            lastBundlePrice = 5730,
            now = singleTime
        ))
    }

    @Test
    fun `묶음 시간 없을 때 단건 정상 처리`() {
        assertFalse(shouldDropAsBundleSingleDup(
            platform = "baemin",
            isMulti = false,
            bundleCount = 1,
            lastBundleTime = 0L,
            lastBundlePrice = 0,
            now = System.currentTimeMillis()
        ))
    }

    @Test
    fun `쿠팡 단건은 묶음 시간과 무관하게 허용`() {
        val bundleTime = 1000000L
        val singleTime = bundleTime + 3000
        assertFalse(shouldDropAsBundleSingleDup(
            platform = "coupang",
            isMulti = false,
            bundleCount = 1,
            lastBundleTime = bundleTime,
            lastBundlePrice = 5730,
            now = singleTime
        ))
    }

    @Test
    fun `묶음 콜 자체는 DROP 대상 아님`() {
        val bundleTime = 1000000L
        val singleTime = bundleTime + 3000
        assertFalse(shouldDropAsBundleSingleDup(
            platform = "baemin",
            isMulti = true,
            bundleCount = 2,
            lastBundleTime = bundleTime,
            lastBundlePrice = 5730,
            now = singleTime
        ))
    }
}
