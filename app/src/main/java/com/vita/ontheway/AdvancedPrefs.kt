package com.vita.ontheway

import android.content.Context

/** v3.0 고급 기능 토글 관리. 모든 기본값 OFF */
object AdvancedPrefs {

    private const val PREFS = "advanced_prefs"

    // ── 토글 키 ──
    private const val KEY_EARNINGS_TRACKING = "earnings_tracking"
    private const val KEY_NAVI_AUTO_LAUNCH = "navi_auto_launch"
    private const val KEY_NAVI_APP = "navi_app"  // "kakao_navi", "tmap", "kakao_map"
    private const val KEY_AUTO_ACCEPT = "auto_accept"
    private const val KEY_DIRECTION_FILTER = "direction_filter"
    private const val KEY_HOME_DIRECTION = "home_direction"
    private const val KEY_DAILY_REPORT = "daily_report"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // 수익 트래킹
    fun isEarningsTrackingEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_EARNINGS_TRACKING, false)
    fun setEarningsTracking(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_EARNINGS_TRACKING, v).apply()

    // 네비 자동실행
    fun isNaviAutoLaunchEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_NAVI_AUTO_LAUNCH, false)
    fun setNaviAutoLaunch(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_NAVI_AUTO_LAUNCH, v).apply()

    fun getNaviApp(ctx: Context): String = prefs(ctx).getString(KEY_NAVI_APP, "kakao_navi") ?: "kakao_navi"
    fun setNaviApp(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_NAVI_APP, v).apply()

    // 자동 수락
    fun isAutoAcceptEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_AUTO_ACCEPT, false)
    fun setAutoAccept(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO_ACCEPT, v).apply()

    // 귀가 방향 필터
    fun isDirectionFilterEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_DIRECTION_FILTER, false)
    fun setDirectionFilter(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_DIRECTION_FILTER, v).apply()

    fun getHomeDirection(ctx: Context): String = prefs(ctx).getString(KEY_HOME_DIRECTION, "") ?: ""
    fun setHomeDirection(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_HOME_DIRECTION, v).apply()

    // 일별 리포트
    fun isDailyReportEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_DAILY_REPORT, false)
    fun setDailyReport(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_DAILY_REPORT, v).apply()

    // v3.3: 연속 넘김 경고 (기본 ON)
    fun isRejectWarningEnabled(ctx: Context) = prefs(ctx).getBoolean("reject_warning", true)
    fun setRejectWarning(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("reject_warning", v).apply()

    // v3.3: 배달 완료 안내 (기본 OFF)
    fun isDeliveryCompleteEnabled(ctx: Context) = prefs(ctx).getBoolean("delivery_complete", false)
    fun setDeliveryComplete(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("delivery_complete", v).apply()

    // 쿠팡 진단 모드 (기본 OFF)
    fun isCoupangDebugEnabled(ctx: Context) = prefs(ctx).getBoolean("coupang_debug_mode", false)
    fun setCoupangDebug(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("coupang_debug_mode", v).apply()

    // v3.5: 배터리 절약 모드 (기본 ON)
    fun isBatterySaverEnabled(ctx: Context) = prefs(ctx).getBoolean("battery_saver", true)
    fun setBatterySaver(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("battery_saver", v).apply()

    // v3.4: GPS 위치 사용 (기본 OFF)
    fun isGpsEnabled(ctx: Context) = prefs(ctx).getBoolean("gps_enabled", false)
    fun setGpsEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("gps_enabled", v).apply()

    // v3.3: 콜 알림음 (기본 OFF)
    fun isCallSoundEnabled(ctx: Context) = prefs(ctx).getBoolean("call_sound", false)
    fun setCallSound(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("call_sound", v).apply()

    // 알림음 타이밍: "before" or "after" TTS
    fun getCallSoundTiming(ctx: Context): String = prefs(ctx).getString("call_sound_timing", "before") ?: "before"
    fun setCallSoundTiming(ctx: Context, v: String) = prefs(ctx).edit().putString("call_sound_timing", v).apply()
}
