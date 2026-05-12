package com.vita.ontheway

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    private const val ADDRESS_API_URL = "https://dapi.kakao.com/v2/local/search/address.json"
    private const val KEYWORD_API_URL = "https://dapi.kakao.com/v2/local/search/keyword.json"

    /** 메모리 캐시 최대 크기 */
    private const val MEM_CACHE_MAX_SIZE = 1000
    /** 메모리 캐시 TTL (1시간) */
    private const val MEM_TTL_MS = 1L * 60 * 60 * 1000

    data class LatLng(val lat: Double, val lng: Double)

    private data class MemCacheEntry(val coord: LatLng, val ts: Long)
    private val memCache = ConcurrentHashMap<String, MemCacheEntry>()

    /** 거리 계산 결과 (source 추적 포함) */
    data class DistanceResult(
        val km: Double,
        val source: String,       // "api_keyword", "api_address", "cache_mem", "cache_prefs", "fallback_location_table"
        val confidence: Double    // api=1.0, cache=0.8, fallback=0.1
    ) {
        companion object {
            const val SOURCE_API_KEYWORD = "api_keyword"
            const val SOURCE_API_ADDRESS = "api_address"
            const val SOURCE_CACHE_MEM = "cache_mem"
            const val SOURCE_CACHE_PREFS = "cache_prefs"
            const val SOURCE_FALLBACK = "fallback_location_table"
            const val SOURCE_NLS = "nls"

            // Legacy aliases for compatibility
            const val SOURCE_API = "api_keyword"
            const val SOURCE_CACHE = "cache_mem"

            fun apiKeyword(km: Double) = DistanceResult(km, SOURCE_API_KEYWORD, 1.0)
            fun apiAddress(km: Double) = DistanceResult(km, SOURCE_API_ADDRESS, 1.0)
            fun cacheMem(km: Double) = DistanceResult(km, SOURCE_CACHE_MEM, 0.8)
            fun cachePrefs(km: Double) = DistanceResult(km, SOURCE_CACHE_PREFS, 0.8)
            fun fallback(km: Double) = DistanceResult(km, SOURCE_FALLBACK, 0.1)
            fun nls(km: Double) = DistanceResult(km, SOURCE_NLS, 1.0)

            // Legacy factory methods
            fun api(km: Double) = apiKeyword(km)
            fun cache(km: Double) = cacheMem(km)
        }
    }

    /** API 키 조회 (AdvancedPrefs에서) */
    private fun getApiKey(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences("advanced_prefs", Context.MODE_PRIVATE)
        val key = prefs.getString("kakao_api_key", null)
        return if (key.isNullOrBlank()) null else key
    }

    /** 활성화 여부 (API 키 존재 시 활성화) */
    fun isEnabled(ctx: Context): Boolean = getApiKey(ctx) != null

    /** 캐시 키 정규화: trim + 공백 통일 + lowercase */
    internal fun normalizeKey(name: String): String =
        name.trim().replace(Regex("\\s+"), " ").lowercase()

    /**
     * 주소/가게명 → 좌표. sync 호출 (네트워크 I/O 포함).
     * 호출자는 백그라운드 스레드에서 호출해야 함.
     *
     * Fix X v1.1: keyword search에 좌표 + radius + sort 추가.
     *
     * 우선순위:
     * 1. 캐시 hit → 즉시 반환
     * 2. keyword search API (가게명에 적합, 좌표 기반 정렬)
     * 3. address search API (주소에 적합)
     * 4. 실패 → null (호출자가 LocationTable fallback)
     *
     * @param nearLat 현재 위치 위도 (keyword search 정렬용, 0.0이면 미사용)
     * @param nearLng 현재 위치 경도 (keyword search 정렬용, 0.0이면 미사용)
     * @return 지오코딩 결과 (source 포함)
     */
    data class GeocodeResult(val coord: LatLng, val source: String)

    fun geocode(ctx: Context, address: String, nearLat: Double = 0.0, nearLng: Double = 0.0): LatLng? {
        return geocodeWithSource(ctx, address, nearLat, nearLng)?.coord
    }

    fun geocodeWithSource(ctx: Context, address: String, nearLat: Double = 0.0, nearLng: Double = 0.0): GeocodeResult? {
        if (address.isBlank()) return null

        // 1. 캐시 확인
        val cacheSource = getFromCacheWithSource(ctx, address)
        if (cacheSource != null) return GeocodeResult(cacheSource.first, cacheSource.second)

        // 2. API 키 확인
        val apiKey = getApiKey(ctx) ?: return null

        // 3. keyword search 우선 (가게명에 더 적합, 좌표 기반 정렬)
        val keywordResult = callKeywordApi(address, apiKey, nearLat, nearLng)
        if (keywordResult != null) {
            saveToCache(ctx, address, keywordResult)
            Log.d(TAG, "keyword 성공: \"$address\" → (${keywordResult.lat}, ${keywordResult.lng})")
            return GeocodeResult(keywordResult, DistanceResult.SOURCE_API_KEYWORD)
        }

        // 4. address search fallback
        val addressResult = callApi(ADDRESS_API_URL, address, apiKey)
        if (addressResult != null) {
            saveToCache(ctx, address, addressResult)
            Log.d(TAG, "address 성공: \"$address\" → (${addressResult.lat}, ${addressResult.lng})")
            return GeocodeResult(addressResult, DistanceResult.SOURCE_API_ADDRESS)
        }

        Log.w(TAG, "API 실패: keyword+address 모두 실패 for \"$address\"")
        return null
    }

    /**
     * keyword API 호출 (좌표 + radius + sort=distance).
     * Fix X v1.1: 동음이의 가게 중 가까운 가게 우선 선택.
     */
    internal fun callKeywordApi(query: String, apiKey: String, nearLat: Double = 0.0, nearLng: Double = 0.0): LatLng? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val urlBuilder = StringBuilder("$KEYWORD_API_URL?query=$encoded&size=3")
            if (nearLat != 0.0 && nearLng != 0.0) {
                urlBuilder.append("&y=$nearLat&x=$nearLng&radius=20000&sort=distance")
            }
            val url = URL(urlBuilder.toString())
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "KakaoAK $apiKey")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000

            val code = conn.responseCode
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val result = parseFirstCoord(body)
                if (result != null) {
                    logKeywordResult(body, query)
                }
                result
            } else {
                logApiError(code, KEYWORD_API_URL, query)
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "keyword API 예외: ${e.message} for \"$query\"")
            null
        }
    }

    /** 카카오 Local API 공통 호출 (address search용) */
    internal fun callApi(apiUrl: String, query: String, apiKey: String): LatLng? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("$apiUrl?query=$encoded")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "KakaoAK $apiKey")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000

            val code = conn.responseCode
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                parseFirstCoord(body)
            } else {
                logApiError(code, apiUrl, query)
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "API 예외: ${e.message} for \"$query\"")
            null
        }
    }

    /** API error code별 분리 로그 */
    private fun logApiError(code: Int, apiUrl: String, query: String) {
        val msg = when (code) {
            401 -> "API 키 문제 (401)"
            403 -> "권한 문제 (403)"
            429 -> "한도 초과 (429)"
            in 500..599 -> "서버 일시 실패 ($code)"
            else -> "HTTP $code"
        }
        Log.w(TAG, "$msg: $apiUrl for \"$query\"")
    }

    /** keyword 결과 상세 로그 (검증용) */
    private fun logKeywordResult(body: String, query: String) {
        try {
            val json = JSONObject(body)
            val docs = json.optJSONArray("documents") ?: return
            if (docs.length() > 0) {
                val first = docs.getJSONObject(0)
                val placeName = first.optString("place_name", "")
                val addressName = first.optString("address_name", "")
                val distance = first.optString("distance", "")
                OtwFileLogger.log(TAG, "keyword hit: query=\"$query\" → place=\"$placeName\" addr=\"$addressName\" dist=${distance}m")
            }
        } catch (_: Exception) {}
    }

    /** JSON 응답에서 첫 번째 좌표 추출 */
    internal fun parseFirstCoord(responseBody: String): LatLng? {
        return try {
            val json = JSONObject(responseBody)
            val docs = json.optJSONArray("documents")
            if (docs != null && docs.length() > 0) {
                val first = docs.getJSONObject(0)
                val lng = first.optDouble("x", 0.0)
                val lat = first.optDouble("y", 0.0)
                if (lat != 0.0 && lng != 0.0) LatLng(lat, lng) else null
            } else null
        } catch (_: Exception) { null }
    }

    private val bgExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "KakaoGeo-BG").apply { isDaemon = true }
    }

    /** 1시간마다 만료 캐시 정리 */
    private val cleanupScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "KakaoGeo-Cleanup").apply { isDaemon = true }
    }.also { scheduler ->
        scheduler.scheduleAtFixedRate({
            cleanupMemCache()
        }, 1, 1, TimeUnit.HOURS)
    }

    /** 만료 항목 + size cap 정리 */
    internal fun cleanupMemCache() {
        val now = System.currentTimeMillis()
        val removed = memCache.entries.removeIf { now - it.value.ts > MEM_TTL_MS }
        // size cap: 오래된 항목부터 제거
        if (memCache.size > MEM_CACHE_MAX_SIZE) {
            val sorted = memCache.entries.sortedBy { it.value.ts }
            val toRemove = sorted.take(memCache.size - MEM_CACHE_MAX_SIZE)
            for (entry in toRemove) {
                memCache.remove(entry.key)
            }
            Log.d(TAG, "캐시 size cap 정리: ${toRemove.size}건 제거, 남은=${memCache.size}")
        }
    }

    /**
     * distanceToSync: 최대 maxWaitMs 대기 후 결과 반환 (실시간 판단용).
     * 캐시 hit → 즉시. 캐시 miss → API 호출 최대 대기. 실패 시 LocationTable fallback.
     */
    fun distanceToSync(
        ctx: Context, currentLat: Double, currentLng: Double, address: String,
        maxWaitMs: Long = 500
    ): DistanceResult? {
        if (address.isBlank()) return null
        val enabled = isEnabled(ctx)
        val startMs = System.currentTimeMillis()

        // Fix X v1.3: 진입 로그 (API 키 마스킹)
        OtwFileLogger.log(TAG, "SYNC_ENTER: addr=\"$address\" enabled=$enabled keyPresent=${getApiKey(ctx)?.let { "true,len=${it.length}" } ?: "false"}")

        if (enabled) {
            // 캐시 hit → 즉시 반환
            val cacheResult = getFromCacheWithSource(ctx, address)
            if (cacheResult != null) {
                val dist = LocationTable.haversineKm(currentLat, currentLng, cacheResult.first.lat, cacheResult.first.lng)
                val source = cacheResult.second
                OtwFileLogger.log(TAG, "SYNC_EXIT: addr=\"$address\" cacheHit=true km=${"%.2f".format(dist)} source=$source took=${System.currentTimeMillis() - startMs}ms")
                return DistanceResult(dist, source, if (source == DistanceResult.SOURCE_CACHE_MEM) 0.8 else 0.8)
            }

            // 캐시 miss → API 호출 대기 (maxWaitMs)
            OtwFileLogger.log(TAG, "SYNC_API_START: addr=\"$address\" maxWait=${maxWaitMs}ms")
            val resultRef = AtomicReference<GeocodeResult?>(null)
            val latch = CountDownLatch(1)
            val appCtx = ctx.applicationContext
            bgExecutor.execute {
                try {
                    resultRef.set(geocodeWithSource(appCtx, address, currentLat, currentLng))
                } catch (_: Exception) {}
                latch.countDown()
            }
            val waited = latch.await(maxWaitMs, TimeUnit.MILLISECONDS)
            if (waited) {
                val geo = resultRef.get()
                if (geo != null) {
                    val dist = LocationTable.haversineKm(currentLat, currentLng, geo.coord.lat, geo.coord.lng)
                    OtwFileLogger.log(TAG, "SYNC_EXIT: addr=\"$address\" apiSuccess=true km=${"%.2f".format(dist)} source=${geo.source} took=${System.currentTimeMillis() - startMs}ms")
                    return DistanceResult(dist, geo.source, 1.0)
                }
                OtwFileLogger.log(TAG, "SYNC_API_NO_RESULT: addr=\"$address\" waited=true took=${System.currentTimeMillis() - startMs}ms")
            } else {
                OtwFileLogger.log(TAG, "SYNC_API_TIMEOUT: addr=\"$address\" maxWait=${maxWaitMs}ms took=${System.currentTimeMillis() - startMs}ms")
            }
        }

        // Fallback: LocationTable
        val fallback = LocationTable.distanceTo(currentLat, currentLng, address)
        val fallbackHit = fallback != null
        OtwFileLogger.log(TAG, "SYNC_EXIT: addr=\"$address\" fallbackHit=$fallbackHit km=${if (fallback != null) "${"%.2f".format(fallback)}" else "null"} enabled=$enabled took=${System.currentTimeMillis() - startMs}ms")
        return if (fallback != null) DistanceResult.fallback(fallback) else null
    }

    /**
     * distanceToAsync: 비동기 호출 + 결과 시 callback (late update용).
     * 캐시 hit → 즉시 callback. 캐시 miss → 백그라운드 API 호출 후 callback.
     */
    fun distanceToAsync(
        ctx: Context, currentLat: Double, currentLng: Double, address: String,
        callback: (DistanceResult) -> Unit
    ) {
        if (address.isBlank()) return
        val enabled = isEnabled(ctx)

        if (enabled) {
            // 캐시 hit → 즉시 callback
            val cacheResult = getFromCacheWithSource(ctx, address)
            if (cacheResult != null) {
                val dist = LocationTable.haversineKm(currentLat, currentLng, cacheResult.first.lat, cacheResult.first.lng)
                val source = cacheResult.second
                callback(DistanceResult(dist, source, 0.8))
                return
            }
        }

        // 백그라운드 API 호출
        val appCtx = ctx.applicationContext
        bgExecutor.execute {
            try {
                if (enabled) {
                    val geo = geocodeWithSource(appCtx, address, currentLat, currentLng)
                    if (geo != null) {
                        val dist = LocationTable.haversineKm(currentLat, currentLng, geo.coord.lat, geo.coord.lng)
                        OtwFileLogger.log(TAG, "async API 성공: \"$address\" → ${"%.2f".format(dist)}km (${geo.source})")
                        callback(DistanceResult(dist, geo.source, 1.0))
                        return@execute
                    }
                }
                // Fallback
                val fallback = LocationTable.distanceTo(currentLat, currentLng, address)
                if (fallback != null) {
                    OtwFileLogger.log(TAG, "async fallback: \"$address\" → ${"%.2f".format(fallback)}km")
                    callback(DistanceResult.fallback(fallback))
                }
            } catch (e: Exception) {
                Log.w(TAG, "async 실패: ${e.message}")
            }
        }
    }

    /**
     * Legacy distanceTo — 기존 호출자 호환.
     * 캐시 hit → 정확 좌표 즉시 반환. miss → LocationTable fallback + 백그라운드 예열.
     */
    fun distanceTo(ctx: Context, currentLat: Double, currentLng: Double, address: String): DistanceResult? {
        if (address.isBlank()) return null
        val enabled = isEnabled(ctx)
        if (enabled) {
            val cacheResult = getFromCacheWithSource(ctx, address)
            if (cacheResult != null) {
                val dist = LocationTable.haversineKm(currentLat, currentLng, cacheResult.first.lat, cacheResult.first.lng)
                val source = cacheResult.second
                OtwFileLogger.log(TAG, "캐시 hit: \"$address\" → ${"%.2f".format(dist)}km ($source)")
                return DistanceResult(dist, source, 0.8)
            }
            OtwFileLogger.log(TAG, "캐시 miss → 백그라운드 API 예약: \"$address\"")
            val appCtx = ctx.applicationContext
            bgExecutor.execute {
                try { geocode(appCtx, address, currentLat, currentLng) } catch (_: Exception) {}
            }
        }
        val fallback = LocationTable.distanceTo(currentLat, currentLng, address)
        OtwFileLogger.log(TAG, "fallback: \"$address\" → ${if (fallback != null) "${"%.2f".format(fallback)}km" else "null"} (enabled=$enabled)")
        return if (fallback != null) DistanceResult.fallback(fallback) else null
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

    private fun cacheKey(address: String): String = "geo_${normalizeKey(address).hashCode()}"
    private fun tsKey(address: String): String = "ts_${normalizeKey(address).hashCode()}"

    private fun getFromCache(ctx: Context, address: String): LatLng? {
        return getFromCacheWithSource(ctx, address)?.first
    }

    private fun getFromCacheWithSource(ctx: Context, address: String): Pair<LatLng, String>? {
        val key = normalizeKey(address)
        // 1. 인메모리 캐시 (1시간)
        val mem = memCache[key]
        if (mem != null && System.currentTimeMillis() - mem.ts <= MEM_TTL_MS) {
            return mem.coord to DistanceResult.SOURCE_CACHE_MEM
        }

        // 2. SharedPrefs 캐시 (30일)
        val prefs = ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val ts = prefs.getLong(tsKey(address), 0)
        if (System.currentTimeMillis() - ts > TTL_MS) return null
        val stored = prefs.getString(cacheKey(address), null) ?: return null
        return try {
            val parts = stored.split(",")
            if (parts.size == 2) {
                val result = LatLng(parts[0].toDouble(), parts[1].toDouble())
                memCache[key] = MemCacheEntry(result, System.currentTimeMillis())  // 인메모리 승격
                result to DistanceResult.SOURCE_CACHE_PREFS
            } else null
        } catch (_: Exception) { null }
    }

    private fun saveToCache(ctx: Context, address: String, coord: LatLng) {
        val key = normalizeKey(address)
        memCache[key] = MemCacheEntry(coord, System.currentTimeMillis())
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

    /** 인메모리 캐시 건수 */
    fun getMemCacheSize(): Int = memCache.size

    /** 테스트용 리셋 */
    fun resetMemCache() { memCache.clear() }

    /** 테스트용: 인메모리 캐시 직접 주입 */
    internal fun putMemCache(address: String, coord: LatLng, ts: Long = System.currentTimeMillis()) {
        memCache[normalizeKey(address)] = MemCacheEntry(coord, ts)
    }
}
