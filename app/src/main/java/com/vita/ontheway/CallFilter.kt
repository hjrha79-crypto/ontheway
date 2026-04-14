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

    private const val HIGH_PRICE_THRESHOLD = 7000

    fun judge(call: DeliveryCall, ctx: Context): FilterResult {
        val minPrice = getMinPrice(ctx)
        val minUnitPrice = getMinUnitPrice(ctx)
        val multiMinPrice = getMultiMinPrice(ctx)
        val fmt = java.text.NumberFormat.getNumberInstance()

        val hasDist = call.distance != null && call.distance > 0
        val unitPrice = if (hasDist) (call.price / call.distance!!).toInt() else 0

        // 고액 콜 보호: 7,000원 이상이면 단가 무관 통과
        if (call.price >= HIGH_PRICE_THRESHOLD) {
            return FilterResult(Verdict.ACCEPT,
                "고액 콜 ${fmt.format(call.price)}원 ≥ ${fmt.format(HIGH_PRICE_THRESHOLD)}원 (단가 무관 통과)")
        }

        // 최소 배달료 미달
        if (call.price < minPrice) {
            return FilterResult(Verdict.REJECT,
                "금액 ${fmt.format(call.price)}원 < 최소기준 ${fmt.format(minPrice)}원 미달")
        }

        // 멀티 최소금액 미달
        if (call.isMulti && call.price < multiMinPrice) {
            return FilterResult(Verdict.REJECT,
                "묶음 금액 ${fmt.format(call.price)}원 < 기준 ${fmt.format(multiMinPrice)}원 미달")
        }

        // 단가 미달 (거리 정보가 있을 때만)
        if (hasDist && unitPrice < minUnitPrice) {
            return FilterResult(Verdict.REJECT,
                "단가 ${fmt.format(unitPrice)}원/km < ${fmt.format(minUnitPrice)}원 기준 미달")
        }

        // ACCEPT 사유
        return if (hasDist && unitPrice >= 2500 && call.distance!! <= 3.0) {
            // 잡으세요 판정
            FilterResult(Verdict.ACCEPT,
                "단가 ${fmt.format(unitPrice)}원/km ≥ 2,500원 + 거리 ${"%.1f".format(call.distance)}km ≤ 3km")
        } else if (hasDist) {
            FilterResult(Verdict.ACCEPT,
                "금액 ${fmt.format(call.price)}원, 거리 ${"%.1f".format(call.distance)}km, 단가 ${fmt.format(unitPrice)}원/km ≥ ${fmt.format(minUnitPrice)}원")
        } else {
            FilterResult(Verdict.ACCEPT,
                "금액 ${fmt.format(call.price)}원 ≥ 최소기준 ${fmt.format(minPrice)}원")
        }
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
