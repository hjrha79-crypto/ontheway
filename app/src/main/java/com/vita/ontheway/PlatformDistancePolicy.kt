package com.vita.ontheway

/**
 * 플랫폼별 거리 정책.
 *
 * 배민 화면 거리 = 총거리 (내 위치 → 가게 → 배달지, 픽업 이미 포함)
 * 쿠팡 알림 거리 = 배달거리만 (가게 → 배달지, 픽업 미포함)
 *
 * Phase 1 (v70.9.2): 쿠팡 픽업 1.0km 고정 추정
 * Phase 2 (v70.10): GPS/학습 기반 실측
 */
object PlatformDistancePolicy {

    /** Phase 1 쿠팡 픽업 추정 거리 (고정) */
    const val ESTIMATED_PICKUP_KM = 1.0

    data class DistanceCalculation(
        val platform: String,
        val pickupKm: Double?,
        val deliveryKm: Double?,
        val totalKm: Double?,
        val source: String,
        val confidence: Double
    )

    /**
     * 플랫폼별 단가 계산용 유효 거리(km).
     *
     * Fix X v1.1: Baemin 이중계산 차단.
     *
     * BAEMIN: 화면 거리(deliveryKm) 그대로 = 이미 총거리 (내 위치 → 가게 → 배달지).
     *         pickupKm은 표시/검증용으로만 저장, 단가 계산에 합산하지 않음.
     * COUPANG: deliveryKm + pickupKm (또는 추정 1.0km).
     *          알림 거리 = 가게→배달지만이므로 픽업 추가 필요.
     */
    fun effectiveDistanceKm(
        platform: String,
        deliveryKm: Double?,
        pickupKm: Double?
    ): Double? {
        return when (platform) {
            "baemin" -> {
                // 화면 거리 = 총거리. pickupKm은 단가 계산에 포함 X.
                deliveryKm
            }
            "coupang" -> {
                if (deliveryKm == null) return null
                val pickup = pickupKm ?: ESTIMATED_PICKUP_KM
                pickup + deliveryKm
            }
            else -> deliveryKm
        }
    }

    /**
     * 플랫폼별 총거리 계산.
     */
    fun calculateTotalKm(platform: String, deliveryKm: Double, pickupKm: Double? = null): Double {
        return when (platform) {
            "baemin" -> deliveryKm  // 화면 거리 = 이미 총거리
            "coupang" -> (pickupKm ?: ESTIMATED_PICKUP_KM) + deliveryKm
            else -> deliveryKm
        }
    }

    /**
     * 플랫폼별 픽업거리 추출.
     * BAEMIN: null (총거리에 포함, 분리 불가)
     * COUPANG: 실측 또는 추정
     */
    fun calculatePickupKm(platform: String, pickupKm: Double? = null): Double? {
        return when (platform) {
            "baemin" -> pickupKm  // GPS 실측 있으면 반환, 없으면 null
            "coupang" -> pickupKm ?: ESTIMATED_PICKUP_KM
            else -> pickupKm
        }
    }

    /**
     * 픽업거리 source.
     */
    fun pickupDistanceSource(platform: String, pickupKm: Double?): String? {
        return when (platform) {
            "coupang" -> if (pickupKm != null) "gps_calculated" else "estimated"
            "baemin" -> if (pickupKm != null) "gps_calculated" else null
            else -> null
        }
    }

    fun calculate(
        platform: String,
        deliveryKm: Double?,
        pickupKm: Double?,
        source: String,
        confidence: Double
    ): DistanceCalculation {
        val totalKm = effectiveDistanceKm(platform, deliveryKm, pickupKm)
        val effectivePickup = calculatePickupKm(platform, pickupKm)
        return DistanceCalculation(
            platform = platform,
            pickupKm = effectivePickup,
            deliveryKm = deliveryKm,
            totalKm = totalKm,
            source = source,
            confidence = confidence
        )
    }

    /**
     * 단가 계산 (원/km) — bundleCount 반영.
     *
     * 단일 진실 원칙: 모든 unitPrice 계산은 이 함수를 거쳐야 한다.
     * 묶음 콜은 건당 가격(price / bundleCount)으로 단가 산출.
     *
     * @param price 총 금액
     * @param platform "baemin" | "coupang"
     * @param deliveryKm 배달 거리 (가게 → 배달지)
     * @param pickupKm 픽업 거리 (라이더 → 가게)
     * @param bundleCount 묶음 건수 (1 이상)
     */
    fun unitPrice(
        price: Int,
        platform: String,
        deliveryKm: Double?,
        pickupKm: Double?,
        bundleCount: Int = 1
    ): Int {
        val safeBundle = bundleCount.coerceAtLeast(1)
        val dist = effectiveDistanceKm(platform, deliveryKm, pickupKm) ?: return 0
        if (dist <= 0 || price <= 0) return 0
        val perCallPrice = price.toDouble() / safeBundle
        return (perCallPrice / dist).toInt()
    }
}
