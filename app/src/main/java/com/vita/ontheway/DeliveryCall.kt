package com.vita.ontheway

data class DeliveryCall(
    val price: Int,
    val distance: Double?,
    val isMulti: Boolean,
    val platform: String  // "coupang", "baemin", "kakaot"
)
