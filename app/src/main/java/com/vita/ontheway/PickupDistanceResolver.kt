package com.vita.ontheway

import android.content.Context
import android.util.Log

/**
 * Fix X v1.3: 픽업 거리 resolve 통합 헬퍼.
 *
 * 기존 OnTheWayService / DeliveryNotificationService에 흩어진
 * 거리 계산 + 검증 + reason 추적을 단일 진입점으로 통합.
 *
 * 결과는 Success / Pending / Rejected 3종.
 */
object PickupDistanceResolver {

    private const val TAG = "PickupDistResolver"
    private const val ROAD_DISTANCE_FACTOR = OnTheWayService.ROAD_DISTANCE_FACTOR
    private const val MIN_ROAD_KM = 0.05
    private const val MAX_ROAD_KM = 10.0
    /** 위치 유효 기간 (10분) */
    const val LOCATION_MAX_AGE_MS = 10L * 60 * 1000

    enum class PendingReason {
        NO_LOCATION,
        BLANK_ADDRESS,
        NO_KAKAO_KEY,
        API_TIMEOUT,
        API_NO_RESULT,
        FALLBACK_NO_RESULT,
        VALIDATION_REJECTED
    }

    sealed class Result {
        data class Success(
            val km: Double,
            val source: String,
            val confidence: Double
        ) : Result()

        data class Pending(val reason: PendingReason) : Result()

        data class Rejected(val reason: PendingReason, val rawKm: Double = 0.0) : Result()
    }

    /**
     * hasUsableLocation: gpsActive 단독 의존 제거.
     * currentLat/Lng != 0 && locationAge < 10분이면 사용 가능.
     */
    fun hasUsableLocation(lat: Double, lng: Double, lastLocationTimeMs: Long): Boolean {
        if (lat == 0.0 && lng == 0.0) return false
        if (lastLocationTimeMs <= 0) return false
        val age = System.currentTimeMillis() - lastLocationTimeMs
        return age < LOCATION_MAX_AGE_MS
    }

    /**
     * 동기 resolve: 캐시/API(maxWaitMs) → fallback → pending reason.
     *
     * @param storeName 가게명 (우선)
     * @param destination 주소 (storeName 없을 때 fallback)
     * @return Success / Pending / Rejected
     */
    fun resolveSyncOrPending(
        ctx: Context,
        lat: Double,
        lng: Double,
        lastLocationTimeMs: Long,
        storeName: String,
        destination: String,
        maxWaitMs: Long = 500
    ): Result {
        // 1. 위치 확인
        if (!hasUsableLocation(lat, lng, lastLocationTimeMs)) {
            OtwFileLogger.log(TAG, "pending: NO_LOCATION lat=$lat lng=$lng age=${System.currentTimeMillis() - lastLocationTimeMs}ms")
            return Result.Pending(PendingReason.NO_LOCATION)
        }

        // 2. 주소 확인
        val addr = storeName.ifEmpty { destination }
        if (addr.isBlank()) {
            OtwFileLogger.log(TAG, "pending: BLANK_ADDRESS")
            return Result.Pending(PendingReason.BLANK_ADDRESS)
        }

        // 3. Kakao API 키 확인
        if (!KakaoGeocoder.isEnabled(ctx)) {
            // fallback만 시도
            val fallback = LocationTable.distanceTo(lat, lng, addr)
            if (fallback != null) {
                val road = fallback * ROAD_DISTANCE_FACTOR
                if (road in MIN_ROAD_KM..MAX_ROAD_KM) {
                    OtwFileLogger.log(TAG, "fallback(no key): $addr → ${"%.2f".format(road)}km")
                    return Result.Success(road, KakaoGeocoder.DistanceResult.SOURCE_FALLBACK, 0.1)
                }
                return Result.Rejected(PendingReason.VALIDATION_REJECTED, road)
            }
            OtwFileLogger.log(TAG, "pending: NO_KAKAO_KEY + no fallback for $addr")
            return Result.Pending(PendingReason.NO_KAKAO_KEY)
        }

        // 4. distanceToSync (캐시 + API 대기 + LocationTable fallback)
        val distResult = KakaoGeocoder.distanceToSync(ctx, lat, lng, addr, maxWaitMs)
        if (distResult != null) {
            val road = distResult.km * ROAD_DISTANCE_FACTOR
            if (road in MIN_ROAD_KM..MAX_ROAD_KM) {
                return Result.Success(road, distResult.source, distResult.confidence)
            }
            OtwFileLogger.log(TAG, "rejected: $addr → ${"%.2f".format(road)}km (source=${distResult.source})")
            return Result.Rejected(PendingReason.VALIDATION_REJECTED, road)
        }

        // 5. distanceToSync가 null 반환 — reason 세분화
        // distanceToSync는 API 타임아웃 / no result / fallback 실패 모두 null 반환
        // isEnabled=true이므로 API 시도는 했음
        OtwFileLogger.log(TAG, "pending: API+fallback 모두 실패 for $addr")
        return Result.Pending(PendingReason.API_NO_RESULT)
    }

    /**
     * 비동기 resolve: sync 실패 시 async callback으로 late update.
     * 호출자가 Success가 아닐 때 사용.
     */
    fun resolveAsync(
        ctx: Context,
        lat: Double,
        lng: Double,
        storeName: String,
        destination: String,
        callback: (Result) -> Unit
    ) {
        val addr = storeName.ifEmpty { destination }
        if (addr.isBlank()) return

        KakaoGeocoder.distanceToAsync(ctx, lat, lng, addr) { asyncResult ->
            val road = asyncResult.km * ROAD_DISTANCE_FACTOR
            if (road in MIN_ROAD_KM..MAX_ROAD_KM) {
                callback(Result.Success(road, asyncResult.source, asyncResult.confidence))
            } else {
                callback(Result.Rejected(PendingReason.VALIDATION_REJECTED, road))
            }
        }
    }
}
