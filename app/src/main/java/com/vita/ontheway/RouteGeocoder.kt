package com.vita.ontheway

import android.content.Context
import android.location.Geocoder
import android.util.Log
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Route Mini v0.2: 배송 정류장 일괄 지오코딩.
 *
 * Fallback 흐름:
 * 1. KakaoGeocoder (REST API) — isEnabled() = true 시
 * 2. Android Geocoder (시스템 기본)
 * 3. 둘 다 실패 → 좌표 없이 (입력 순서 유지)
 */
object RouteGeocoder {

    private const val TAG = "RouteGeocoder"
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RouteGeo-BG").apply { isDaemon = true }
    }

    /**
     * 정류장 리스트 일괄 지오코딩 (비동기).
     * 각 정류장의 좌표를 채운 새 리스트를 callback으로 반환.
     */
    /** 좌표 범위 검증 반경 (도 단위, ≈55km) */
    internal const val COORD_RANGE_DEG = 0.5

    fun geocodeAll(
        ctx: Context,
        stops: List<RouteStop>,
        callback: (List<RouteStop>) -> Unit,
        refLat: Double = 0.0,
        refLng: Double = 0.0
    ) {
        val appCtx = ctx.applicationContext
        executor.execute {
            val result = stops.map { stop ->
                if (stop.hasCoord()) return@map stop
                val coord = geocodeOne(appCtx, stop.address, refLat, refLng)
                if (coord != null) {
                    stop.copy(lat = coord.first, lng = coord.second)
                } else stop
            }
            callback(result)
        }
    }

    /**
     * 단일 주소 지오코딩 (sync, 호출자가 백그라운드에서 호출).
     *
     * Fallback 흐름:
     * 1. KakaoGeocoder.geocode (address + keyword search 내장)
     * 2. Android Geocoder (시스템 기본)
     * 3. 실패 → null
     *
     * 장소명("강남역", "도곡역 1번출구" 등)은 KakaoGeocoder의
     * keyword search가 자동 처리 (geocode → callKeywordApi → callApi).
     *
     * @return Pair(lat, lng) or null
     */
    /**
     * 지역 prefix 목록 (짧은 주소 보강용).
     * 원본 실패 시 prefix 추가하여 재시도.
     * 베타 기간: 경기 광주시 우선.
     */
    internal val REGION_PREFIXES = listOf("경기 광주시", "광주광역시")

    internal fun geocodeOne(ctx: Context, address: String, refLat: Double = 0.0, refLng: Double = 0.0): Pair<Double, Double>? {
        if (address.isBlank()) return null

        // 1. KakaoGeocoder (address + keyword search)
        if (KakaoGeocoder.isEnabled(ctx)) {
            val usePrefix = needsRegionPrefix(address)

            // 1a. 짧은 주소 → prefix 우선 시도 (경기 광주시 등)
            if (usePrefix) {
                for (prefix in REGION_PREFIXES) {
                    try {
                        val prefixed = "$prefix $address"
                        val coord = KakaoGeocoder.geocode(ctx, prefixed)
                        if (coord != null && isInRange(coord.lat, coord.lng, refLat, refLng)) {
                            Log.d(TAG, "Kakao prefix 성공: \"$prefixed\" → (${coord.lat}, ${coord.lng})")
                            return Pair(coord.lat, coord.lng)
                        }
                    } catch (_: Exception) {}
                }
            }

            // 1b. 원본 주소로 시도
            try {
                val coord = KakaoGeocoder.geocode(ctx, address)
                if (coord != null && isInRange(coord.lat, coord.lng, refLat, refLng)) {
                    Log.d(TAG, "Kakao 성공: \"$address\" → (${coord.lat}, ${coord.lng})")
                    return Pair(coord.lat, coord.lng)
                }
                if (coord != null) {
                    Log.d(TAG, "Kakao 범위 밖: \"$address\" → (${coord.lat}, ${coord.lng}) ref=($refLat,$refLng)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Kakao 실패: ${e.message}")
            }
            Log.d(TAG, "Kakao 결과 없음: \"$address\"")
        }

        // 2. Android Geocoder
        try {
            if (Geocoder.isPresent()) {
                @Suppress("DEPRECATION")
                val results = Geocoder(ctx, Locale.KOREA).getFromLocationName(address, 1)
                if (!results.isNullOrEmpty()) {
                    val loc = results[0]
                    Log.d(TAG, "Android Geocoder 성공: \"$address\" → (${loc.latitude}, ${loc.longitude})")
                    return Pair(loc.latitude, loc.longitude)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Android Geocoder 실패: ${e.message}")
        }

        Log.d(TAG, "지오코딩 실패: \"$address\"")
        return null
    }

    /**
     * 짧은 주소 = 지역 prefix 필요 판정.
     * 시/도/구 없는 동+번지 또는 도로명만 있는 경우.
     */
    internal fun needsRegionPrefix(address: String): Boolean {
        // 이미 시/도/광역시 포함 → 불필요
        val hasRegion = Regex("(?:서울|경기|인천|부산|대구|대전|광주광역|울산|세종|강원|충북|충남|전북|전남|경북|경남|제주)")
            .containsMatchIn(address)
        if (hasRegion) return false
        // 시/구 포함 → 불필요
        val hasSiGu = Regex("[가-힣]{1,5}(?:시|구)\\s").containsMatchIn(address)
        if (hasSiGu) return false
        // 짧은 주소 (동+번지, 도로명 등) → prefix 필요
        return true
    }

    /**
     * v0.2.9: 좌표 범위 검증.
     * 기준 좌표(refLat/refLng)가 있으면, 결과가 ±0.5도(≈55km) 이내인지 확인.
     * 기준 좌표 없으면(0,0) 항상 허용.
     */
    internal fun isInRange(lat: Double, lng: Double, refLat: Double, refLng: Double): Boolean {
        if (refLat == 0.0 && refLng == 0.0) return true // 기준 없음 → 허용
        return Math.abs(lat - refLat) <= COORD_RANGE_DEG &&
               Math.abs(lng - refLng) <= COORD_RANGE_DEG
    }

    /** Haversine 거리 (km) */
    fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
}
