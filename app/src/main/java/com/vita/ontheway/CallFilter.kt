package com.vita.ontheway

import android.content.Context
import android.util.Log

object CallFilter {

    private const val PREFS = "delivery_filter"
    private const val KEY_MIN_PRICE = "min_price"
    private const val KEY_MIN_UNIT_PRICE = "min_unit_price"
    private const val KEY_MULTI_MIN_PRICE = "multi_min_price"
    private const val KEY_OK_VOICE_ENABLED = "ok_voice_enabled"
    private const val KEY_MAX_PICKUP_KM = "max_pickup_km"
    private const val KEY_VOICE_ACCEPT_ENABLED = "voice_accept_enabled"

    // 묶음 건당 최소 단가 (이하이면 REJECT)
    // 실데이터 기반: 2건 4,315원 MISMATCH, 3건 2,803원 MISMATCH
    private const val BUNDLE_PER_ITEM_MIN_2 = 4500   // 2건 묶음: 건당 4,500원 이상
    private const val BUNDLE_PER_ITEM_MIN_3 = 5000   // 3건+ 묶음: 건당 5,000원 이상

    // v3.6: 연속 REJECT 자동 기준 하향
    private var consecutiveRejectCount: Int = 0
    private var priceReduction: Int = 0  // 현재 하향된 금액
    private const val REDUCTION_STEP = 500
    private const val MIN_PRICE_FLOOR = 1500  // 최저 기준

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
        // v3.6: 연속 REJECT 기준 하향 적용
        if (priceReduction > 0) {
            minPrice = (minPrice - priceReduction).coerceAtLeast(MIN_PRICE_FLOOR)
        }
        val minUnitPrice = getMinUnitPrice(ctx)
        val highPriceThreshold = TtsPrefs.getHighPriceThreshold(ctx)
        val fmt = java.text.NumberFormat.getNumberInstance()

        Log.d("JudgeLogic", "==== 판정 시작 ====")
        Log.d("JudgeLogic", "settings: minPrice=$minPrice, minUnitPrice=$minUnitPrice, highPrice=$highPriceThreshold, multiMin=${getMultiMinPrice(ctx)}")
        Log.d("JudgeLogic", "call: platform=${call.platform}, price=${call.price}, distance=${call.distance}, point=${call.point}, isMulti=${call.isMulti}, storeName=${call.storeName}")

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

        // ── 묶음배달 건수별 판정 (v2 2.0, v3.9 슬라이더 우선순위 통합) ──
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
            // v3.9: 설정 묶음 최소금액과 내장 기준 중 더 엄격한 쪽 적용
            val settingMultiMin = getMultiMinPrice(ctx)
            val effectiveMin = maxOf(bundleMin, multiPickupMin, settingMultiMin)
            val multiPickupTag = if (call.isMultiPickup) ", 다중 픽업" else ""

            // 고액 묶음 보호
            if (call.price >= bundleHigh) {
                return FilterResult(Verdict.ACCEPT,
                    "고액 묶음 ${fmt.format(call.price)}원 ≥ ${fmt.format(bundleHigh)}원 ($bundleTag$multiPickupTag)")
            }

            // v3.9: 설정 묶음 최소금액 미달 시 명확한 사유
            if (call.price < settingMultiMin) {
                return FilterResult(Verdict.REJECT,
                    "묶음 최소 ${fmt.format(settingMultiMin)}원 미달 ${fmt.format(call.price)}원 ($bundleTag$multiPickupTag)")
            }

            // 최소금액 미달
            if (call.price < effectiveMin) {
                return FilterResult(Verdict.REJECT,
                    "묶음 금액 ${fmt.format(call.price)}원 < 기준 ${fmt.format(effectiveMin)}원 미달 ($bundleTag$multiPickupTag)")
            }

            // v3.21: 건당 최소 단가 미달 (실데이터 기반 — 8,000원대 묶음 MISMATCH 방지)
            val perItemMin = if (bundleCount >= 3) BUNDLE_PER_ITEM_MIN_3 else BUNDLE_PER_ITEM_MIN_2
            if (perPrice < perItemMin) {
                OtwFileLogger.log("DeliveryFilter", "묶음 건당단가 REJECT: ${fmt.format(call.price)}원, 건당 ${perPriceStr}원 < 기준 ${fmt.format(perItemMin)}원 ($bundleTag)")
                return FilterResult(Verdict.REJECT,
                    "묶음 건당 ${perPriceStr}원 < 기준 ${fmt.format(perItemMin)}원 미달 ($bundleTag$multiPickupTag)")
            }

            // 단가 미달 (거리 정보가 있을 때만)
            if (hasDist && unitPrice < minUnitPrice) {
                return FilterResult(Verdict.REJECT,
                    "단가 ${fmt.format(unitPrice)}원/km < ${fmt.format(minUnitPrice)}원 기준 미달 ($bundleTag$multiPickupTag)")
            }

            // v3.11: 묶음 효율 판정 (건당 3,000원+ + 건당 거리 3km 이하 → 잡으세요)
            val bundleDist = if (hasDist) call.distance!!
                else if (call.point != null && call.point > 0) BaeminParser.convertPointToKm(call.point)
                else null
            if (bundleDist != null && bundleDist > 0) {
                val distPerItem = bundleDist / bundleCount
                if (perPrice >= 3000 && distPerItem <= 3.0) {
                    return FilterResult(Verdict.ACCEPT,
                        "잡으세요: 묶음 효율 건당 ${perPriceStr}원 ≥ 3,000원 + 건당 ${"%.1f".format(distPerItem)}km ≤ 3km ($bundleTag$multiPickupTag)")
                }
            }

            OtwFileLogger.log("DeliveryFilter", "묶음 ACCEPT: ${fmt.format(call.price)}원, 건당 ${perPriceStr}원 ≥ ${fmt.format(perItemMin)}원 ($bundleTag$multiPickupTag)")
            return FilterResult(Verdict.ACCEPT,
                "묶음 통과 ${fmt.format(call.price)}원 ($bundleTag$multiPickupTag)")
        }

        // ── 포인트 참고: Dialog 거리 필드에서 표시하므로 사유에서는 제거 ──
        val pointTag = ""

        // ── 배민 거리 없는 콜: minPrice + 포인트 환산 단가 판정 ──
        if (call.platform == "baemin" && !hasDist) {
            // 포인트 → 거리 환산 (보조 판정용, 부정확할 수 있음)
            val estimatedKm = if (call.point != null && call.point > 0) BaeminParser.convertPointToKm(call.point) else 0.0
            val estimatedUnitPrice = if (estimatedKm > 0) (call.price / estimatedKm).toInt() else 0
            val estimateTag = if (estimatedKm > 0) ", 추정거리 ${"%.1f".format(estimatedKm)}km, 추정단가 ${fmt.format(estimatedUnitPrice)}원/km" else ""

            Log.d("JudgeLogic", "baemin_no_dist: price=${call.price}, point=${call.point}, estimatedKm=${"%.1f".format(estimatedKm)}, estimatedUnitPrice=$estimatedUnitPrice, minUnitPrice=$minUnitPrice")

            // 고액 콜 자동 통과
            if (call.price >= highPriceThreshold) {
                return FilterResult(Verdict.ACCEPT,
                    "고액 콜 ${fmt.format(call.price)}원 ≥ ${fmt.format(highPriceThreshold)}원$estimateTag$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag$pointTag")
            }

            // 최소배달료 미달
            if (call.price < minPrice) {
                return FilterResult(Verdict.REJECT,
                    "최소배달료 ${fmt.format(call.price)}원 미달 (설정: ${fmt.format(minPrice)}원)$estimateTag$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag$pointTag")
            }

            // 단가 미달 (포인트 환산 성공 시만)
            if (estimatedKm > 0 && estimatedUnitPrice < minUnitPrice) {
                return FilterResult(Verdict.REJECT,
                    "단가 ${fmt.format(estimatedUnitPrice)}원/km < ${fmt.format(minUnitPrice)}원 기준 미달 (거리 추정)$estimateTag$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag$pointTag")
            }

            return FilterResult(Verdict.ACCEPT,
                "최소배달료 통과 (${fmt.format(call.price)}원 ≥ ${fmt.format(minPrice)}원)$estimateTag$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag$pointTag")
        }

        // ── 단건 판정 (거리 있음 또는 배민 외 플랫폼) ──

        // 고액 콜 자동 통과 (v3.20: 사용자 설정값 참조)
        if (call.price >= highPriceThreshold) {
            return FilterResult(Verdict.ACCEPT,
                "고액 콜 ${fmt.format(call.price)}원 ≥ ${fmt.format(highPriceThreshold)}원 (단가 무관 통과)$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag$pointTag")
        }

        // 최소 배달료 미달
        if (call.price < minPrice) {
            return FilterResult(Verdict.REJECT,
                "금액 ${fmt.format(call.price)}원 < 최소기준 ${fmt.format(minPrice)}원 미달$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag$pointTag")
        }

        // 단가 미달 (거리 정보가 있을 때만)
        if (hasDist && unitPrice < minUnitPrice) {
            return FilterResult(Verdict.REJECT,
                "단가 ${fmt.format(unitPrice)}원/km < ${fmt.format(minUnitPrice)}원 기준 미달$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag$pointTag")
        }

        // ACCEPT 사유
        return if (hasDist && unitPrice >= 2500 && call.distance!! <= 3.0) {
            FilterResult(Verdict.ACCEPT,
                "잡으세요: 고단가 근거리 ${fmt.format(unitPrice)}원/km ≥ 2,500원 + 거리 ${"%.1f".format(call.distance)}km ≤ 3km$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag$pointTag")
        } else if (hasDist && unitPrice >= 2000 && call.distance!! <= 2.0) {
            // v3.11: 단거리 고단가 확대 (2,000원/km + 2km 이하)
            FilterResult(Verdict.ACCEPT,
                "잡으세요: 단거리 고단가 ${fmt.format(unitPrice)}원/km ≥ 2,000원 + 거리 ${"%.1f".format(call.distance)}km ≤ 2km$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag$pointTag")
        } else if (hasDist) {
            FilterResult(Verdict.ACCEPT,
                "금액 ${fmt.format(call.price)}원, 거리 ${"%.1f".format(call.distance)}km, 단가 ${fmt.format(unitPrice)}원/km ≥ ${fmt.format(minUnitPrice)}원$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag$pointTag")
        } else {
            FilterResult(Verdict.ACCEPT,
                "금액 ${fmt.format(call.price)}원 ≥ 최소기준 ${fmt.format(minPrice)}원$storeTag$peakTag$directionTag$gpsTag$autoDirectionTag$pointTag")
        }
    }

    /**
     * v3.6: 판정 결과에 따라 연속 REJECT 카운터 업데이트
     * @return TTS 안내 메시지 (null이면 안내 불필요)
     */
    fun updateRejectStreak(verdict: Verdict, ctx: Context): String? {
        if (verdict == Verdict.REJECT) {
            consecutiveRejectCount++
            val newReduction = when {
                consecutiveRejectCount >= 30 -> REDUCTION_STEP * 3  // 1500원 하향
                consecutiveRejectCount >= 20 -> REDUCTION_STEP * 2  // 1000원 하향
                consecutiveRejectCount >= 10 -> REDUCTION_STEP      // 500원 하향
                else -> 0
            }
            if (newReduction > priceReduction) {
                priceReduction = newReduction
                val adjusted = (getMinPrice(ctx) - priceReduction).coerceAtLeast(MIN_PRICE_FLOOR)
                Log.d("CallFilter", "연속 REJECT ${consecutiveRejectCount}건 → 기준 하향: ${adjusted}원")
                return "기준을 낮췄습니다"
            }
        } else {
            if (priceReduction > 0) {
                priceReduction = 0
                consecutiveRejectCount = 0
                Log.d("CallFilter", "ACCEPT 발생 → 기준 복구: ${getMinPrice(ctx)}원")
                return "기준을 원래대로 돌렸습니다"
            }
            consecutiveRejectCount = 0
        }
        return null
    }

    /** 현재 적용 중인 최소금액 기준 (하향 반영) */
    fun getEffectiveMinPrice(ctx: Context): Int =
        (getMinPrice(ctx) - priceReduction).coerceAtLeast(MIN_PRICE_FLOOR)

    /** 현재 기준 하향 금액 */
    fun getCurrentReduction(): Int = priceReduction

    /** 연속 REJECT 수 */
    fun getConsecutiveRejectCount(): Int = consecutiveRejectCount

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
