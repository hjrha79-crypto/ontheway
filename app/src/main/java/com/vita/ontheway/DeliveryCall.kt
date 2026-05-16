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
    val point: Double? = null,          // 배민 포인트 (v2 2.0)
    val pickupDistanceKm: Double? = null, // v3.4: GPS 기반 픽업 거리
    val parsingMethod: String = V2Event.PARSING_UNKNOWN, // v2.2: 파싱 방식 라벨
    val orderId: String? = null,        // FIX-T2CN: 배민 주문번호 (T2CN...)
    val distanceSource: String = "",    // "api", "cache", "fallback", "nls"
    val distanceConfidence: Double = 0.0, // 0.0~1.0
    // COUPANG-NOTIFICATION-FIRST: 가격 3분리
    val offeredPrice: Int? = null,     // 알림 제안가
    val acceptedPrice: Int? = null,    // 수락 시점 가격 (현재 = offered)
    val settledPrice: Int? = null,     // 실제 정산가 (운행 완료 후 보강)
    // 쿠팡 identity
    val identityKey: String? = null,
    val identityKeyType: String? = null, // "PRIMARY" / "FALLBACK"
    val notificationKey: String? = null,  // sbn key
    // Fix 70.10.1: bundleCount provenance
    val bundleCountConfidence: Double = 1.0,  // 1.0=확정, 0.8=contentDesc, 0.5=추정
    val bundleCountSource: String = "",       // "reason", "contentDesc", "notification", ""
    // Memory M1: 요청사항 분류
    val requestClassification: String? = null, // REPEAT_CRITICAL / NAVIGATION_HINT / IGNORE
    // Repeat Critical v0.1: 조리 상태
    val cookingStatus: String = "UNKNOWN" // COOKING_DONE / UNKNOWN
)
