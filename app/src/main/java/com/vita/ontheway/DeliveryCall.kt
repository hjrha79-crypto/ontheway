package com.vita.ontheway

data class DeliveryCall(
    val price: Int,
    val distance: Double?,
    val isMulti: Boolean,
    val platform: String,  // "coupang", "baemin", "kakaot"
    val rawText: String = "",
    val storeName: String = "",
    val destination: String = "",
    val parseSuccess: Boolean = true
)
