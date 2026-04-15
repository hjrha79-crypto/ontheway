package com.vita.ontheway

import android.content.Context

object CallFilter {

    private const val PREFS = "delivery_filter"
    private const val KEY_MIN_PRICE = "min_price"
    private const val KEY_MIN_UNIT_PRICE = "min_unit_price"
    private const val KEY_MULTI_MIN_PRICE = "multi_min_price"
    private const val KEY_OK_VOICE_ENABLED = "ok_voice_enabled"
    private const val KEY_MAX_PICKUP_KM = "max_pickup_km"
    private const val KEY_VOICE_ACCEPT_ENABLED = "voice_accept_enabled"

    enum class Verdict { ACCEPT, REJECT }

    data class FilterResult(
        val verdict: Verdict,
        val reason: String
    )

    /** 묶음 건수별 최소금액: 3000 + (건수-1)*2500 */
    fun bundleMinPrice(count: Int): Int = 3000 + (count - 1) * 2500

    /** 묶음 건수별 고액 기준: 7000 + (건수-1)*3000 */
    fun bundleHighPrice(count: Int): Int = 7000 + (count - 1) * 3000

    fun judge(call: DeliveryCall, ctx: Context): FilterResult {
        var minPrice = getMinPrice(ctx)
        val minUnitPrice = getMinUnitPrice(ctx)
        val fmt = java.text.NumberFormat.getNumberInstance()

        val hasDist = call.distance != null && call.distance > 0
        val unitPrice = if (hasDist) (call.price / call.distance!!).toInt() else 0

        // v3.4: 총거리 판정 (픽업 + 배달)
        val pickupKm = call.pickupDistanceKm ?: 0.0
        val deliveryKm = call.distance ?: 0.0
        val totalKm = pickupKm + deliveryKm
        val totalUnitPrice = if (totalKm > 0) (call.price / totalKm).toInt() else 0
        val gpsTag = if (pickupKm > 0) ", 픽업 ${"%.1f".format(pickupKm)}km + 배달 ${"%.1f".format(deliveryKm)}km = 총 ${"%.1f".format(totalKm)}km, 총단가 ${fmt.format(totalUnitPrice)}원/km" else ""

        // v3.4: 자동 방향 판별
        var autoDirectionTag = ""
        if (AdvancedPrefs.isGpsEnabled(ctx) && OnTheWayService.currentLat != 0.0 && call.destination.isNotEmpty()) {
            val destCoord = LocationTable.findCoord(call.destination)
            if (destCoord != null) {
                val dir = LocationTable.getDirection(OnTheWayService.currentLat, OnTheWayService.currentLng, destCoord.lat, destCoord.lng)
                val homeDir = AdvancedPrefs.getHomeDirection(ctx)
                if (homeDir.isNotEmpty()) {
                    val homeCoord = LocationTable.findCoord(homeDir)
                    if (homeCoord != null) {
                        val homeDirStr = LocationTable.getDirection(OnTheWayService.currentLat, OnTheWayService.currentLng, homeCoord.lat, homeCoord.lng)
                        if (dir == homeDirStr) {
                            autoDirectionTag = ", 귀가 방향 일치($dir)"
                            minPrice = (minPrice - 500).coerceAtLeast(1000)
                        } else {
                            autoDirectionTag = ", 역방향($dir vs 귀가$homeDirStr)"
                        }
                    }
                }
            }
        }

        // v3.3: 블랙리스트 체크
        if (call.storeName.isNotEmpty() && StoreManager.isBlacklisted(ctx, call.storeName)) {
            return FilterResult(Verdict.REJECT,
                "블랙리스트 거부: ${call.storeName}")
        }

        // v3.3: 즐겨찾기 보너스
        var storeTag = ""
        if (call.storeName.isNotEmpty() && StoreManager.isFavorite(ctx, call.storeName)) {
            minPrice = (minPrice - 1000).coerceAtLeast(1000)
            storeTag = ", 즐겨찾기 보너스"
        }

        // v3.3: 피크 시간 자동 조절
        val peakStatus = PeakDetector.getCurrentStatus(ctx)
        if (peakStatus.adjustment != 0) {
            minPrice = (minPrice + peakStatus.adjustment).coerceAtLeast(1000)
        }
        val peakTag = if (peakStatus.adjustment > 0) ", 피크 +500" else if (peakStatus.adjustment < 0) ", 비피크 -500" else ""

        // v3.0: 귀가 방향 필터
        var directionTag = ""
        if (AdvancedPrefs.isDirectionFilterEnabled(ctx)) {
            val homeDir = AdvancedPrefs.getHomeDirection(ctx)
            if (homeDir.isNotEmpty() && call.destination.isNotEmpty()) {
                // 동/읍/면 이름 추출
                val homeTokens = extractAreaTokens(homeDir)
                val destTokens = extractAreaTokens(call.destination)
                val isHomeDirection = homeTokens.any { ht -> destTokens.any { it.contains(ht) || ht.contains(it) } }
                if (isHomeDirection) {
                    minPrice = (minPrice - 500).coerceAtLeast(1000)
                    directionTag = ", 귀가 방향 보너스"
                } else if (homeTokens.isNotEmpty() && destTokens.isNotEmpty()) {
                    minPrice += 500
                    directionTag = ", 역방향 페널티"
                }
            }
        }

        // ── 묶음배달 건수별 판정 (v2 2.0) ──
        if (call.isMulti) {
            val bundleCount = call.bundleCount.coerceAtLeast(2)
            val bundleMin = bundleMinPrice(bundleCount)
            val bundleHigh = bundleHighPrice(bundleCount)
            val perPrice = call.price / bundleCount
            val perPriceStr = fmt.format(perPrice)
            val bundleTag = "묶음 ${bundleCount}건, 건당 ${perPriceStr}원"

            // 다중 픽업 추가 기준
            val multiPickupMin = if (call.isMultiPickup) when {
                bundleCount >= 3 -> 10000
                bundleCount >= 2 -> 7000
                else -> 0
            } else 0
            val effectiveMin = maxOf(bundleMin, multiPickupMin)
            val multiPickupTag = if (call.isMultiPickup) ", 다중 픽업" else ""

            // 고액 묶음 보호
            if (call.price >= bundleHigh) {
                return FilterResult(Verdict.ACCEPT,
                    "고액 묶음 ${fmt.format(call.price)}원 ≥ ${fmt.format(bundleHigh)}원 ($bundleTag$multiPickupTag)")
            }

            // 최소금액 미달
            if (call.price < effectiveMin) {
                return FilterResult(Verdict.REJECT,
                    "묶음 금액 ${fmt.format(call.price)}원 < 기준 ${fmt.format(effectiveMin)}원 미달 ($bundleTag$multiPickupTag)")
            }

            // 단가 미달 (거리 정보가 있을 때만)
            if (hasDist && unitPrice < minUnitPrice) {
                return FilterResult(Verdict.REJECT,
                    "단가 ${fmt.format(unitPrice)}원/km < ${fmt.format(minUnitPrice)}원 기준 미달 ($bundleTag$multiPickupTag)")
            }

            return FilterResult(Verdict.ACCEPT,
                "묶음 통과 ${fmt.format(call.price)}원 ($bundleTag$multiPickupTag)")
        }

        // ── 포인트 기반 거리 환산 판정 (배민, v2 2.0) ──
        if (call.platform == "baemin" && call.point != null && call.point > 0) {
            val pointKm = call.point * 0.25
            val pointUnitPrice = (call.price / pointKm).toInt()
            val pointTag = "포인트 ${"%.1f".format(call.point)}P (환산 ${"%.1f".format(pointKm)}km), 단가 ${fmt.format(pointUnitPrice)}원/km"

            // 포인트 구간별 최소금액
            val pointMinPrice = when {
                call.point <= 15.0 -> 3000
                call.point <= 25.0 -> 4000
                else -> 5000
            }

            // 고액 보호 (기본 7000원)
            if (call.price >= 7000) {
                return FilterResult(Verdict.ACCEPT,
                    "고액 콜 ${fmt.format(call.price)}원 ≥ 7,000원 ($pointTag)")
            }

            // 포인트 구간 최소금액 미달
            if (call.price < pointMinPrice) {
                return FilterResult(Verdict.REJECT,
                    "금액 ${fmt.format(call.price)}원 < 포인트구간 ${fmt.format(pointMinPrice)}원 미달 ($pointTag)")
            }

            // 환산 단가 미달
            if (pointUnitPrice < minUnitPrice) {
                return FilterResult(Verdict.REJECT,
                    "환산단가 ${fmt.format(pointUnitPrice)}원/km < ${fmt.format(minUnitPrice)}원 미달 ($pointTag)")
            }

            return FilterResult(Verdict.ACCEPT,
                "통과 ${fmt.format(call.price)}원 ($pointTag)")
        }

        // ── 단건 판정 (기존 로직) ──

        // 고액 콜 보호: 7,000원 이상이면 단가 무관 통과
        if (call.price >= 7000) {
            return FilterResult(Verdict.ACCEPT,
                "고액 콜 ${fmt.format(call.price)}원 ≥ 7,000원 (단가 무관 통과)$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag")
        }

        // 최소 배달료 미달
        if (call.price < minPrice) {
            return FilterResult(Verdict.REJECT,
                "금액 ${fmt.format(call.price)}원 < 최소기준 ${fmt.format(minPrice)}원 미달$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag")
        }

        // 단가 미달 (거리 정보가 있을 때만)
        if (hasDist && unitPrice < minUnitPrice) {
            return FilterResult(Verdict.REJECT,
                "단가 ${fmt.format(unitPrice)}원/km < ${fmt.format(minUnitPrice)}원 기준 미달$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag")
        }

        // ACCEPT 사유
        return if (hasDist && unitPrice >= 2500 && call.distance!! <= 3.0) {
            FilterResult(Verdict.ACCEPT,
                "단가 ${fmt.format(unitPrice)}원/km ≥ 2,500원 + 거리 ${"%.1f".format(call.distance)}km ≤ 3km$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag")
        } else if (hasDist) {
            FilterResult(Verdict.ACCEPT,
                "금액 ${fmt.format(call.price)}원, 거리 ${"%.1f".format(call.distance)}km, 단가 ${fmt.format(unitPrice)}원/km ≥ ${fmt.format(minUnitPrice)}원$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag")
        } else {
            FilterResult(Verdict.ACCEPT,
                "금액 ${fmt.format(call.price)}원 ≥ 최소기준 ${fmt.format(minPrice)}원$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag")
        }
    }

    /** 주소에서 동/읍/면/구 이름 토큰 추출 */
    private fun extractAreaTokens(address: String): List<String> {
        val pattern = Regex("([가-힣]+)(동|읍|면|구|리)")
        return pattern.findAll(address).map { it.groupValues[1] }.toList()
    }

    // ── 설정값 getter/setter ──

    fun getMinPrice(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MIN_PRICE, 3000)

    fun setMinPrice(ctx: Context, value: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MIN_PRICE, value).apply()

    fun getMinUnitPrice(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MIN_UNIT_PRICE, 2000)

    fun setMinUnitPrice(ctx: Context, value: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MIN_UNIT_PRICE, value).apply()

    fun getMultiMinPrice(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MULTI_MIN_PRICE, 5000)

    fun setMultiMinPrice(ctx: Context, value: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MULTI_MIN_PRICE, value).apply()

    fun isOkVoiceEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OK_VOICE_ENABLED, true)

    fun setOkVoiceEnabled(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OK_VOICE_ENABLED, value).apply()

    fun getMaxPickupKm(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MAX_PICKUP_KM, 5)

    fun setMaxPickupKm(ctx: Context, value: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MAX_PICKUP_KM, value).apply()

    fun isVoiceAcceptEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VOICE_ACCEPT_ENABLED, false)

    fun setVoiceAcceptEnabled(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_VOICE_ACCEPT_ENABLED, value).apply()
}
