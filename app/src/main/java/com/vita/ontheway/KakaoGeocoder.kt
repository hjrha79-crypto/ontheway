package com.vita.ontheway

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 카카오 로컬 API 지오코딩 — 주소 → 좌표 변환.
 *
 * 특징:
 * - SharedPrefs 캐싱 (30일 TTL)
 * - Fallback: API 실패 시 LocationTable 사용
 * - 비활성화 상태에서는 LocationTable만 사용
 *
 * 활성화: AdvancedPrefs에 kakao_api_key 설정 필요.
 */
object KakaoGeocoder {

    private const val TAG = "KakaoGeocoder"
    private const val CACHE_PREFS = "kakao_geocode_cache"
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000  // 30일
    private const val API_URL = "https://dapi.kakao.com/v2/local/search/address.json"

    data class LatLng(val lat: Double, val lng: Double)

    /** API 키 조회 (AdvancedPrefs에서) */
    private fun getApiKey(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences("advanced_prefs", Context.MODE_PRIVATE)
        val key = prefs.getString("kakao_api_key", null)
        return if (key.isNullOrBlank()) null else key
    }

    /** 활성화 여부 (API 키 존재 시 활성화) */
    fun isEnabled(ctx: Context): Boolean = getApiKey(ctx) != null

    /**
     * 주소 → 좌표. sync 호출 (네트워크 I/O 포함).
     * 호출자는 백그라운드 스레드에서 호출해야 함.
     *
     * 우선순위:
     * 1. 캐시 hit → 즉시 반환
     * 2. API 호출 → 캐시 저장 → 반환
     * 3. 실패 → null (호출자가 LocationTable fallback)
     */
    fun geocode(ctx: Context, address: String): LatLng? {
        if (address.isBlank()) return null

        // 1. 캐시 확인
        val cached = getFromCache(ctx, address)
        if (cached != null) return cached

        // 2. API 키 확인
        val apiKey = getApiKey(ctx) ?: return null

        // 3. API 호출
        return try {
            val encoded = URLEncoder.encode(address, "UTF-8")
            val url = URL("$API_URL?query=$encoded")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "KakaoAK $apiKey")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val docs = json.optJSONArray("documents")
                if (docs != null && docs.length() > 0) {
                    val first = docs.getJSONObject(0)
                    val lng = first.optDouble("x", 0.0)
                    val lat = first.optDouble("y", 0.0)
                    if (lat != 0.0 && lng != 0.0) {
                        val result = LatLng(lat, lng)
                        saveToCache(ctx, address, result)
                        Log.d(TAG, "API 성공: \"$address\" → ($lat, $lng)")
                        result
                    } else null
                } else null
            } else {
                Log.w(TAG, "API 실패: ${conn.responseCode} for \"$address\"")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "API 예외: ${e.message} for \"$address\"")
            null
        }
    }

    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "KakaoGeo-BG").apply { isDaemon = true }
    }

    /**
     * LocationTable 통합 거리 계산 (메인 스레드 안전).
     * 캐시 hit → 정확 좌표. 캐시 miss → LocationTable fallback + 백그라운드 API 사전 로딩.
     */
    fun distanceTo(ctx: Context, currentLat: Double, currentLng: Double, address: String): Double? {
        if (address.isBlank()) return null
        val enabled = isEnabled(ctx)
        if (enabled) {
            // 캐시 hit → 정확 좌표 즉시 반환
            val cached = getFromCache(ctx, address)
            if (cached != null) {
                val dist = LocationTable.haversineKm(currentLat, currentLng, cached.lat, cached.lng)
                OtwFileLogger.log(TAG, "캐시 hit: \"$address\" → ${"%.2f".format(dist)}km")
                return dist
            }
            // 캐시 miss → 백그라운드에서 API 호출 (다음 요청 시 캐시 hit)
            OtwFileLogger.log(TAG, "캐시 miss → 백그라운드 API 예약: \"$address\"")
            val appCtx = ctx.applicationContext
            bgExecutor.execute {
                try { geocode(appCtx, address) } catch (_: Exception) {}
            }
        }
        // Fallback: LocationTable (동 중심점)
        val fallback = LocationTable.distanceTo(currentLat, currentLng, address)
        OtwFileLogger.log(TAG, "fallback: \"$address\" → ${if (fallback != null) "${"%.2f".format(fallback)}km" else "null"} (enabled=$enabled)")
        return fallback
    }

    /**
     * findCoord 통합 (CallFilter 방향 판별용).
     * 캐시 hit → 정확 좌표. miss → LocationTable fallback.
     */
    fun findCoord(ctx: Context, address: String): LocationTable.AreaCoord? {
        if (address.isBlank()) return null
        val enabled = isEnabled(ctx)
        if (enabled) {
            val cached = getFromCache(ctx, address)
            if (cached != null) {
                return LocationTable.AreaCoord(address, cached.lat, cached.lng)
            }
            val appCtx = ctx.applicationContext
            bgExecutor.execute {
                try { geocode(appCtx, address) } catch (_: Exception) {}
            }
        }
        return LocationTable.findCoord(address)
    }

    // ── 캐시 관리 ──

    private fun cacheKey(address: String): String = "geo_${address.hashCode()}"
    private fun tsKey(address: String): String = "ts_${address.hashCode()}"

    private fun getFromCache(ctx: Context, address: String): LatLng? {
        val prefs = ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val ts = prefs.getLong(tsKey(address), 0)
        if (System.currentTimeMillis() - ts > TTL_MS) return null
        val stored = prefs.getString(cacheKey(address), null) ?: return null
        return try {
            val parts = stored.split(",")
            if (parts.size == 2) LatLng(parts[0].toDouble(), parts[1].toDouble()) else null
        } catch (_: Exception) { null }
    }

    private fun saveToCache(ctx: Context, address: String, coord: LatLng) {
        ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
            .putString(cacheKey(address), "${coord.lat},${coord.lng}")
            .putLong(tsKey(address), System.currentTimeMillis())
            .apply()
    }

    /** 캐시 통계 (디버그용) */
    fun getCacheSize(ctx: Context): Int {
        val prefs = ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        return prefs.all.count { it.key.startsWith("geo_") }
    }
}
