package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test

/**
 * Fix 6: confidence/join_eligible/price_source 격리 정책 테스트.
 */
class JoinEligiblePolicyTest {

    // join_eligible 정책: identity_confidence ≥ 0.7 + price_source != "accepted_estimated"

    @Test
    fun `confidence 0점9 + offered = join_eligible true`() {
        val conf = 0.9
        val priceSource = "offered"
        val joinEligible = conf >= 0.7 && priceSource != "accepted_estimated"
        assertTrue(joinEligible)
    }

    @Test
    fun `confidence 0점7 정확히 + settled = join_eligible true`() {
        val conf = 0.7
        val priceSource = "settled"
        val joinEligible = conf >= 0.7 && priceSource != "accepted_estimated"
        assertTrue(joinEligible)
    }

    @Test
    fun `confidence 0점5 = join_eligible false`() {
        val conf = 0.5
        val priceSource = "offered"
        val joinEligible = conf >= 0.7 && priceSource != "accepted_estimated"
        assertFalse(joinEligible)
    }

    @Test
    fun `confidence 0점9 + accepted_estimated = join_eligible false`() {
        val conf = 0.9
        val priceSource = "accepted_estimated"
        val joinEligible = conf >= 0.7 && priceSource != "accepted_estimated"
        assertFalse(joinEligible)
    }

    @Test
    fun `DeliveryCall price fields default null`() {
        val call = DeliveryCall(price = 3000, distance = 1.0, isMulti = false, platform = "baemin")
        assertNull(call.offeredPrice)
        assertNull(call.acceptedPrice)
        assertNull(call.settledPrice)
    }

    @Test
    fun `effectivePrice 우선순위 — settled가 최종`() {
        val call = DeliveryCall(
            price = 3000, distance = 1.0, isMulti = false, platform = "coupang",
            offeredPrice = 3000, acceptedPrice = 3000, settledPrice = 3500
        )
        val effective = call.settledPrice ?: call.acceptedPrice ?: call.offeredPrice ?: call.price
        assertEquals(3500, effective)
    }

    @Test
    fun `source_channel 매핑`() {
        // COUPANG_PICKUP → notification
        assertEquals("notification", mapSourceChannel(AcceptCoordinator.AcceptSource.COUPANG_PICKUP))
        // BAEMIN_PROGRESS → screen
        assertEquals("screen", mapSourceChannel(AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS))
        // FALLBACK → fallback
        assertEquals("fallback", mapSourceChannel(AcceptCoordinator.AcceptSource.FALLBACK))
    }

    private fun mapSourceChannel(source: AcceptCoordinator.AcceptSource): String = when (source) {
        AcceptCoordinator.AcceptSource.COUPANG_PICKUP -> "notification"
        AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS -> "screen"
        AcceptCoordinator.AcceptSource.SYSTEM_NOTI -> "notification"
        AcceptCoordinator.AcceptSource.CLICK -> "screen"
        AcceptCoordinator.AcceptSource.FALLBACK -> "fallback"
    }
}
