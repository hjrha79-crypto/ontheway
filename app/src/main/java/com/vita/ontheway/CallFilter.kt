package com.vita.ontheway

import android.content.Context

object CallFilter {

    private const val PREFS = "delivery_filter"
    private const val KEY_MIN_PRICE = "min_price"
    private const val KEY_MIN_UNIT_PRICE = "min_unit_price"
    private const val KEY_MULTI_MIN_PRICE = "multi_min_price"
    private const val KEY_OK_VOICE_ENABLED = "ok_voice_enabled"
    private const val KEY_MAX_PICKUP_KM = "max_pickup_km"

    enum class Verdict { ACCEPT, REJECT }

    data class FilterResult(
        val verdict: Verdict,
        val reason: String
    )

    fun judge(call: DeliveryCall, ctx: Context): FilterResult {
        val minPrice = getMinPrice(ctx)
        val minUnitPrice = getMinUnitPrice(ctx)
        val multiMinPrice = getMultiMinPrice(ctx)

        // 최소 배달료 미달
        if (call.price < minPrice) {
            return FilterResult(Verdict.REJECT, "최소배달료 미달 (${call.price} < $minPrice)")
        }

        // 멀티 최소금액 미달
        if (call.isMulti && call.price < multiMinPrice) {
            return FilterResult(Verdict.REJECT, "멀티 최소금액 미달 (${call.price} < $multiMinPrice)")
        }

        // 단가 미달 (거리 정보가 있을 때만)
        if (call.distance != null && call.distance > 0) {
            val unitPrice = (call.price / call.distance).toInt()
            if (unitPrice < minUnitPrice) {
                return FilterResult(Verdict.REJECT, "단가 미달 (${unitPrice}원/km < ${minUnitPrice})")
            }
        }

        return FilterResult(Verdict.ACCEPT, "통과")
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
}
