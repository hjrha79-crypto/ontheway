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

    fun judge(call: DeliveryCall, ctx: Context): FilterResult {
        val minPrice = getMinPrice(ctx)
        val minUnitPrice = getMinUnitPrice(ctx)
        val multiMinPrice = getMultiMinPrice(ctx)
        val fmt = java.text.NumberFormat.getNumberInstance()

        val distInfo = if (call.distance != null && call.distance > 0)
            " | 거리 ${"%.1f".format(call.distance)}km" else ""
        val unitPrice = if (call.distance != null && call.distance > 0)
            (call.price / call.distance).toInt() else 0

        // 최소 배달료 미달
        if (call.price < minPrice) {
            return FilterResult(Verdict.REJECT,
                "금액 ${fmt.format(call.price)}원 < 기준 ${fmt.format(minPrice)}원$distInfo")
        }

        // 멀티 최소금액 미달
        if (call.isMulti && call.price < multiMinPrice) {
            return FilterResult(Verdict.REJECT,
                "묶음 금액 ${fmt.format(call.price)}원 < 기준 ${fmt.format(multiMinPrice)}원$distInfo")
        }

        // 단가 미달 (거리 정보가 있을 때만)
        if (call.distance != null && call.distance > 0) {
            if (unitPrice < minUnitPrice) {
                return FilterResult(Verdict.REJECT,
                    "단가 ${fmt.format(unitPrice)}원/km < 기준 ${fmt.format(minUnitPrice)}원$distInfo")
            }
        }

        // ACCEPT 사유 구성
        val reasonParts = mutableListOf<String>()
        reasonParts.add("금액 ${fmt.format(call.price)}원 ≥ 기준 ${fmt.format(minPrice)}원")
        if (call.isMulti) reasonParts.add("묶음 ${fmt.format(call.price)}원")
        if (unitPrice > 0) reasonParts.add("단가 ${fmt.format(unitPrice)}원/km")
        if (distInfo.isNotEmpty()) reasonParts.add("거리 ${"%.1f".format(call.distance)}km")

        return FilterResult(Verdict.ACCEPT, reasonParts.joinToString(" | "))
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
