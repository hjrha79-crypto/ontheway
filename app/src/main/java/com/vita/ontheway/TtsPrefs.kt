package com.vita.ontheway

import android.content.Context

/** v3.1 TTS 커스터마이징 설정 */
object TtsPrefs {
    private const val PREFS = "tts_prefs"

    // 넘기세요만 안내 (ON이면 ACCEPT 콜은 음성 없이 로그만)
    fun isRejectOnlyEnabled(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("reject_only", false)
    fun setRejectOnly(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("reject_only", v).apply()

    // 잡으세요만 안내 (ON이면 잡으세요만 음성)
    fun isGrabOnlyEnabled(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("grab_only", false)
    fun setGrabOnly(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("grab_only", v).apply()

    // TTS 속도 (0.5 ~ 2.0, 기본 1.0)
    fun getSpeed(ctx: Context): Float =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("speed", 1.0f)
    fun setSpeed(ctx: Context, v: Float) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("speed", v).apply()

    // 볼륨 부스트 (ON이면 미디어 볼륨 최대)
    fun isVolBoostEnabled(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("vol_boost", false)
    fun setVolBoost(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("vol_boost", v).apply()

    // 금액 읽기 방식: "korean" vs "number"
    fun getPriceReadMode(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("price_read", "korean") ?: "korean"
    fun setPriceReadMode(ctx: Context, v: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("price_read", v).apply()

    // 잡으세요 기준 금액 (기본 10000)
    fun getGrabThreshold(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("grab_threshold", 10000)
    fun setGrabThreshold(ctx: Context, v: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("grab_threshold", v).apply()

    // 고액 콜 기준 (기본 7000)
    fun getHighPriceThreshold(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("high_price", 7000)
    fun setHighPriceThreshold(ctx: Context, v: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("high_price", v).apply()
}
