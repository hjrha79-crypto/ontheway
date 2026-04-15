package com.vita.ontheway

data class DeliveryCall(
    val price: Int,
    val distance: Double?,
    val isMulti: Boolean,
    val platform: String,  // "coupang", "baemin", "kakaot"
    val rawText: String = "",
    val storeName: String = "",
    val destination: String = "",
    val parseSuccess: Boolean = true,
    val bundleCount: Int = 1,          // 묶음 건수 (v2 2.0)
    val isMultiPickup: Boolean = false, // 다중 픽업 여부 (v2 2.0)
    val point: Double? = null           // 배민 포인트 (v2 2.0)
)
