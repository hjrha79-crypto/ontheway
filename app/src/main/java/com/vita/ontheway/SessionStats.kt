package com.vita.ontheway

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 배민 묶음 세션 통계 (오늘 기준).
 * SharedPreferences에 영속화, 자정 자동 초기화.
 * v3.12: 중복 호출 가드 추가
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

    // v3.16: loaded 날짜를 기록하여 자정 이후 자동 리셋
    private var loadedDate: String = ""

    // v3.12: 중복 호출 방지
    private var lastBundleCallAt: Long = 0
    private const val BUNDLE_DEDUP_MS = 1000L  // 1초 이내 재호출 차단

    /** SharedPreferences에서 로드 (자정 초기화 포함, v3.17: commit 동기 리셋 + 진단 로그) */
    fun ensureLoaded(ctx: Context) {
        val today = todayStr()
        if (loadedDate == today) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastReset = prefs.getString(KEY_LAST_RESET, "") ?: ""
        if (lastReset != today) {
            // 날짜 변경 → 초기화 (commit으로 동기 쓰기 보장)
            val oldBundle = prefs.getInt(KEY_BUNDLE, 0)
            Log.w("SessionStats", "날짜 리셋: $lastReset → $today, 이전 묶음=${oldBundle}건 → 0")
            prefs.edit()
                .putInt(KEY_BUNDLE, 0).putInt(KEY_SINGLE, 0)
                .putInt(KEY_TIMEOUT, 0).putInt(KEY_FINALIZED, 0)
                .putInt(KEY_APP_CHECK, 0)
                .putString(KEY_LAST_RESET, today)
                .commit()  // v3.17: apply→commit (비동기→동기, 리셋 보장)
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
            Log.d("SessionStats", "로드: 묶음=${bundleDetectedCount}, 단건=${singleDetectedCount}, 날짜=$today")
        }
        loadedDate = today
    }

    @Synchronized
    fun onBundleFinalized(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastBundleCallAt < BUNDLE_DEDUP_MS) {
            Log.w("SessionStats", "onBundleFinalized 중복 호출 차단 (${now - lastBundleCallAt}ms)")
            return
        }
        lastBundleCallAt = now
        ensureLoaded(ctx)
        bundleDetectedCount++
        sessionFinalizedCount++
        Log.d("SessionStats", "onBundleFinalized: bundleCount=$bundleDetectedCount (finalized=$sessionFinalizedCount)")
        save(ctx)
    }

    @Synchronized
    fun onBundleTimeout(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastBundleCallAt < BUNDLE_DEDUP_MS) {
            Log.w("SessionStats", "onBundleTimeout 중복 호출 차단 (${now - lastBundleCallAt}ms)")
            return
        }
        lastBundleCallAt = now
        ensureLoaded(ctx)
        bundleDetectedCount++
        sessionTimeoutCount++
        Log.d("SessionStats", "onBundleTimeout: bundleCount=$bundleDetectedCount (timeout=$sessionTimeoutCount)")
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

    /** v3.18: 쿼리 기반 묶음 카운트 (eventId DISTINCT, FINALIZED 상태만) */
    fun getBundleCountForToday(ctx: Context): Int {
        val entries = FilterLog.getAll(ctx)
        val todayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }.timeInMillis
        val seen = mutableSetOf<String>()
        var count = 0
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            if (e.optLong("ts", 0) < todayStart) continue
            val eid = e.optString("eventId", "")
            val state = e.optString("state", "")
            val bc = e.optInt("bundleCount", 0)
            if (state == "FINALIZED" && bc >= 2 && eid.isNotEmpty() && seen.add(eid)) {
                count++
            }
        }
        return count
    }

    /** v3.18: 쿼리 기반 단건 카운트 (eventId DISTINCT, FINALIZED 상태만) */
    fun getSingleCountForToday(ctx: Context): Int {
        val entries = FilterLog.getAll(ctx)
        val todayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }.timeInMillis
        val seen = mutableSetOf<String>()
        var count = 0
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            if (e.optLong("ts", 0) < todayStart) continue
            val eid = e.optString("eventId", "")
            val state = e.optString("state", "")
            val bc = e.optInt("bundleCount", 0)
            if (state == "FINALIZED" && bc < 2 && eid.isNotEmpty() && seen.add(eid)) {
                count++
            }
        }
        return count
    }

    private fun todayStr(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }
}
