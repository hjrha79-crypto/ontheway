package com.vita.ontheway

import org.junit.Assert.*
import org.junit.Test

/**
 * COUPANG-NOTIFICATION-FIRST: 가격 3분리 테스트.
 */
class CoupangPriceFieldsTest {

    @Test
    fun `DeliveryCall 가격 3분리 필드 존재`() {
        val call = DeliveryCall(
            price = 3450, distance = 2.0, isMulti = false, platform = "coupang",
            offeredPrice = 3450, acceptedPrice = 3450, settledPrice = null
        )
        assertEquals(3450, call.offeredPrice)
        assertEquals(3450, call.acceptedPrice)
        assertNull(call.settledPrice)
    }

    @Test
    fun `offered = accepted = settled (현재)`() {
        val call = DeliveryCall(
            price = 5000, distance = 1.5, isMulti = false, platform = "coupang",
            offeredPrice = 5000, acceptedPrice = 5000, settledPrice = 5000
        )
        assertEquals(call.offeredPrice, call.acceptedPrice)
        assertEquals(call.acceptedPrice, call.settledPrice)
    }

    @Test
    fun `offered 다름 settled (할증 적용)`() {
        val call = DeliveryCall(
            price = 3000, distance = 2.0, isMulti = false, platform = "coupang",
            offeredPrice = 3000, acceptedPrice = 3000, settledPrice = 3500
        )
        assertEquals(3000, call.offeredPrice)
        assertEquals(3500, call.settledPrice)
        assertNotEquals(call.offeredPrice, call.settledPrice)
    }

    @Test
    fun `identity 필드`() {
        val call = DeliveryCall(
            price = 4000, distance = null, isMulti = false, platform = "coupang",
            identityKey = "coupang:sbn-1:1234567890",
            identityKeyType = "PRIMARY",
            notificationKey = "sbn-1"
        )
        assertEquals("coupang:sbn-1:1234567890", call.identityKey)
        assertEquals("PRIMARY", call.identityKeyType)
        assertEquals("sbn-1", call.notificationKey)
    }

    @Test
    fun `기존 필드 호환 — 신규 필드 default null`() {
        val call = DeliveryCall(
            price = 3000, distance = 1.0, isMulti = false, platform = "baemin"
        )
        assertNull(call.offeredPrice)
        assertNull(call.acceptedPrice)
        assertNull(call.settledPrice)
        assertNull(call.identityKey)
        assertNull(call.notificationKey)
    }

    @Test
    fun `effectivePrice — acceptedPrice 우선 offeredPrice fallback`() {
        val call1 = DeliveryCall(
            price = 3000, distance = 1.0, isMulti = false, platform = "coupang",
            offeredPrice = 3000, acceptedPrice = 3200
        )
        // 비즈니스 로직: acceptedPrice 있으면 사용
        val effective1 = call1.acceptedPrice ?: call1.offeredPrice ?: call1.price
        assertEquals(3200, effective1)

        val call2 = DeliveryCall(
            price = 3000, distance = 1.0, isMulti = false, platform = "coupang",
            offeredPrice = 3000, acceptedPrice = null
        )
        val effective2 = call2.acceptedPrice ?: call2.offeredPrice ?: call2.price
        assertEquals(3000, effective2)
    }
}
