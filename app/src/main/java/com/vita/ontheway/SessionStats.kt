package com.vita.ontheway

import android.content.Context

/**
 * 배민 묶음 세션 통계 (오늘 기준).
 * SharedPreferences에 영속화, 자정 자동 초기화.
 */
object SessionStats {

    private const val PREFS = "session_stats"
    private const val KEY_BUNDLE = "bundle_count"
    private const val KEY_SINGLE = "single_count"
    private const val KEY_TIMEOUT = "timeout_count"
    private const val KEY_FINALIZED = "finalized_count"
    private const val KEY_LAST_RESET = "last_reset_date"
    private const val KEY_APP_CHECK = "app_check_count"

    var bundleDetectedCount: Int = 0
        private set
    var singleDetectedCount: Int = 0
        private set
    var sessionTimeoutCount: Int = 0
        private set
    var sessionFinalizedCount: Int = 0
        private set
    var appCheckCount: Int = 0
        private set

    private var loaded = false

    /** SharedPreferences에서 로드 (자정 초기화 포함) */
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStr()
        val lastReset = prefs.getString(KEY_LAST_RESET, "") ?: ""
        if (lastReset != today) {
            // 날짜 변경 → 초기화
            prefs.edit()
                .putInt(KEY_BUNDLE, 0).putInt(KEY_SINGLE, 0)
                .putInt(KEY_TIMEOUT, 0).putInt(KEY_FINALIZED, 0)
                .putInt(KEY_APP_CHECK, 0)
                .putString(KEY_LAST_RESET, today)
                .apply()
            bundleDetectedCount = 0
            singleDetectedCount = 0
            sessionTimeoutCount = 0
            sessionFinalizedCount = 0
            appCheckCount = 0
        } else {
            bundleDetectedCount = prefs.getInt(KEY_BUNDLE, 0)
            singleDetectedCount = prefs.getInt(KEY_SINGLE, 0)
            sessionTimeoutCount = prefs.getInt(KEY_TIMEOUT, 0)
            sessionFinalizedCount = prefs.getInt(KEY_FINALIZED, 0)
            appCheckCount = prefs.getInt(KEY_APP_CHECK, 0)
        }
        loaded = true
    }

    fun onBundleFinalized(ctx: Context) {
        ensureLoaded(ctx)
        bundleDetectedCount++
        sessionFinalizedCount++
        save(ctx)
    }

    fun onBundleTimeout(ctx: Context) {
        ensureLoaded(ctx)
        bundleDetectedCount++
        sessionTimeoutCount++
        save(ctx)
    }

    fun onSingleDetected(ctx: Context) {
        ensureLoaded(ctx)
        singleDetectedCount++
        save(ctx)
    }

    fun onAppChecked(ctx: Context) {
        ensureLoaded(ctx)
        appCheckCount++
        save(ctx)
    }

    fun reset(ctx: Context) {
        bundleDetectedCount = 0
        singleDetectedCount = 0
        sessionTimeoutCount = 0
        sessionFinalizedCount = 0
        appCheckCount = 0
        save(ctx)
    }

    /** UI 표시용 요약 문자열 */
    fun getSummary(ctx: Context): String {
        ensureLoaded(ctx)
        return "오늘 묶음 ${bundleDetectedCount}건 (정상 $sessionFinalizedCount / 타임아웃 $sessionTimeoutCount) · 단건 ${singleDetectedCount}건"
    }

    private fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_BUNDLE, bundleDetectedCount)
            .putInt(KEY_SINGLE, singleDetectedCount)
            .putInt(KEY_TIMEOUT, sessionTimeoutCount)
            .putInt(KEY_FINALIZED, sessionFinalizedCount)
            .putInt(KEY_APP_CHECK, appCheckCount)
            .putString(KEY_LAST_RESET, todayStr())
            .apply()
    }

    private fun todayStr(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }
}
