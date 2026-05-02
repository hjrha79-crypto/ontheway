package com.vita.ontheway

/**
 * GPS 근접 감지: 픽업지/배달지 접근 시 이벤트 발생.
 * - 픽업지 300m 이내 -> PICKUP_NEAR
 * - 배달지 200m 이내 -> DELIVERY_NEAR
 * 같은 콜에 대해 각 이벤트 1회만 발화.
 */
object ProximityDetector {

    private const val TAG = "ProximityDetector"
    const val PICKUP_THRESHOLD_KM = 0.3    // 300m
    const val DELIVERY_THRESHOLD_KM = 0.2  // 200m

    enum class ProximityEvent {
        PICKUP_NEAR,
        DELIVERY_NEAR
    }

    data class Target(
        val callKey: String,
        val pickupLat: Double? = null,
        val pickupLng: Double? = null,
        val deliveryLat: Double? = null,
        val deliveryLng: Double? = null,
        val storeName: String = "",
        val customerRequest: String? = null,
        val nextStoreName: String? = null
    )

    var listener: ((ProximityEvent, Target) -> Unit)? = null

    private var currentTarget: Target? = null
    private val firedEvents = mutableSetOf<String>()

    fun setTarget(target: Target) {
        currentTarget = target
        OtwFileLogger.log(TAG, "타겟 설정: key=${target.callKey}, " +
            "pickup=(${target.pickupLat},${target.pickupLng}), " +
            "delivery=(${target.deliveryLat},${target.deliveryLng})")
    }

    fun clearTarget() {
        currentTarget = null
        OtwFileLogger.log(TAG, "타겟 클리어")
    }

    fun resetFiredEvents() {
        firedEvents.clear()
    }

    fun getTarget(): Target? = currentTarget

    /**
     * GPS 위치 업데이트 시 호출.
     * 근접 이벤트 감지 시 listener 콜백.
     */
    fun onLocationUpdate(lat: Double, lng: Double) {
        if (!FeatureFlags.proximityTTS) return
        val target = currentTarget ?: return

        checkAndFire(lat, lng, target)
    }

    /**
     * 근접 체크 + 이벤트 발화 (내부 로직).
     * 반환: 발생한 이벤트 목록 (테스트용).
     */
    internal fun checkAndFire(lat: Double, lng: Double, target: Target): List<ProximityEvent> {
        val events = mutableListOf<ProximityEvent>()

        // 픽업지 근접 체크
        if (target.pickupLat != null && target.pickupLng != null) {
            val distKm = LocationTable.haversineKm(lat, lng, target.pickupLat, target.pickupLng)
            if (distKm <= PICKUP_THRESHOLD_KM) {
                val eventKey = "${target.callKey}_PICKUP_NEAR"
                if (eventKey !in firedEvents) {
                    firedEvents.add(eventKey)
                    OtwFileLogger.log(TAG, "PICKUP_NEAR: ${"%.3f".format(distKm)}km (${target.storeName})")
                    listener?.invoke(ProximityEvent.PICKUP_NEAR, target)
                    events.add(ProximityEvent.PICKUP_NEAR)
                }
            }
        }

        // 배달지 근접 체크
        if (target.deliveryLat != null && target.deliveryLng != null) {
            val distKm = LocationTable.haversineKm(lat, lng, target.deliveryLat, target.deliveryLng)
            if (distKm <= DELIVERY_THRESHOLD_KM) {
                val eventKey = "${target.callKey}_DELIVERY_NEAR"
                if (eventKey !in firedEvents) {
                    firedEvents.add(eventKey)
                    OtwFileLogger.log(TAG, "DELIVERY_NEAR: ${"%.3f".format(distKm)}km (${target.storeName})")
                    listener?.invoke(ProximityEvent.DELIVERY_NEAR, target)
                    events.add(ProximityEvent.DELIVERY_NEAR)
                }
            }
        }

        return events
    }

    /** 테스트/외부용: 두 좌표 간 거리 (km) */
    fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double =
        LocationTable.haversineKm(lat1, lng1, lat2, lng2)

    /** 좌표가 임계값 이내인지 판정 */
    fun isWithinThreshold(
        currentLat: Double, currentLng: Double,
        targetLat: Double, targetLng: Double,
        thresholdKm: Double
    ): Boolean = LocationTable.haversineKm(currentLat, currentLng, targetLat, targetLng) <= thresholdKm
}
