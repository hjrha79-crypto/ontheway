package com.vita.ontheway

import android.content.Context

/**
 * Route Mini v0.2: 경로 상태 SharedPreferences 저장/복원.
 */
object RouteStateStore {

    private const val PREFS = "route_mini"
    private const val KEY_STOPS = "stops"
    private const val KEY_RAW_TEXT = "raw_text"
    private const val KEY_AUTO_NEXT_NAVI = "auto_next_navi"
    private const val KEY_RETURN_ADDRESS = "return_address"

    fun saveStops(ctx: Context, stops: List<RouteStop>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STOPS, RouteStop.listToJson(stops)).apply()
    }

    fun loadStops(ctx: Context): List<RouteStop> {
        val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STOPS, "") ?: ""
        return RouteStop.listFromJson(json)
    }

    fun saveRawText(ctx: Context, text: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RAW_TEXT, text).apply()
    }

    fun loadRawText(ctx: Context): String {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RAW_TEXT, "") ?: ""
    }

    fun setAutoNextNavi(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_NEXT_NAVI, enabled).apply()
    }

    fun isAutoNextNavi(ctx: Context): Boolean {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_NEXT_NAVI, false)
    }

    fun saveReturnAddress(ctx: Context, address: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RETURN_ADDRESS, address).apply()
    }

    fun loadReturnAddress(ctx: Context): String {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RETURN_ADDRESS, "") ?: ""
    }

    fun clear(ctx: Context) {
        // 복귀지는 clear에서 제외 (재사용)
        val returnAddr = loadReturnAddress(ctx)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        if (returnAddr.isNotBlank()) saveReturnAddress(ctx, returnAddr)
    }
}
