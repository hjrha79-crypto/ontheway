package com.vita.ontheway

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DrivingModeManager {
    private const val PREFS = "advanced_prefs"
    private const val KEY_MODE = "driving_mode"
    private const val KEY_STARTED_AT = "driving_started_at"
    private const val KEY_STARTED_DATE = "driving_started_date"
    private const val KEY_TOTAL_TODAY = "driving_total_today_ms"
    private const val KEY_TOTAL_DATE = "driving_total_today_date"
    private const val TAG = "OTW_DRIVING_MODE"

    fun getMode(ctx: Context): DrivingMode {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_MODE, DrivingMode.IDLE.name)
        return try {
            DrivingMode.valueOf(name ?: DrivingMode.IDLE.name)
        } catch (_: Exception) {
            DrivingMode.IDLE
        }
    }

    fun setMode(ctx: Context, mode: DrivingMode) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val previousMode = getMode(ctx)

        // 날짜 변경 체크 (운행 중 자정 넘김 대응)
        checkAndResetDate(ctx)

        if (previousMode == mode) {
            Log.d(TAG, "already $mode")
            return
        }

        when (mode) {
            DrivingMode.DRIVING -> {
                prefs.edit()
                    .putString(KEY_MODE, mode.name)
                    .putLong(KEY_STARTED_AT, now)
                    .putString(KEY_STARTED_DATE, todayStr())
                    .apply()
                Log.d(TAG, "DRIVING ON at $now")
                LocationTracker.startTracking(ctx)
            }
            DrivingMode.IDLE -> {
                val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
                if (startedAt > 0L) {
                    // 오늘 자�� 이후 부분만 오늘 누적에 반영
                    val todayMidnight = todayMidnightMs()
                    val effectiveStart = maxOf(startedAt, todayMidnight)
                    val duration = now - effectiveStart
                    if (duration > 0) {
                        accumulateDrivingTime(ctx, duration)
                    }
                    Log.d(TAG, "DRIVING OFF, duration: ${duration / 1000}s (effectiveStart clamped to midnight)")
                }
                prefs.edit()
                    .putString(KEY_MODE, mode.name)
                    .remove(KEY_STARTED_AT)
                    .remove(KEY_STARTED_DATE)
                    .apply()
                LocationTracker.stopTracking()
            }
        }
    }

    /**
     * 날짜 변경 감지 + 자동 리셋.
     * 운행 중 자정을 넘겼으면:
     * - 오늘 누적 시간 0으로 리셋
     * - startedAt을 현재 시각으로 갱신 (팬텀 시간 방지)
     */
    fun checkAndResetDate(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStr()

        // 누적 시간 날짜 리셋 (날짜가 다르거나, 날짜 미기록인데 누적값이 남아있는 경우)
        val savedDate = prefs.getString(KEY_TOTAL_DATE, "") ?: ""
        if (savedDate != today) {
            val oldTotal = prefs.getLong(KEY_TOTAL_TODAY, 0L)
            if (savedDate.isNotEmpty() || oldTotal > 0) {
                prefs.edit()
                    .putLong(KEY_TOTAL_TODAY, 0L)
                    .putString(KEY_TOTAL_DATE, today)
                    .apply()
                Log.d(TAG, "날짜 변경 감지: \"$savedDate\" → $today, 누적 시간 리셋 (이전 ${oldTotal / 1000}s)")
                OtwFileLogger.log(TAG, "날짜 리셋: \"$savedDate\" → $today (이전 ${oldTotal / 1000}s)")
            }
        }

        // 운행 중이��� startedAt이 오늘이 아니면 → 자정으로 갱신
        if (getMode(ctx) == DrivingMode.DRIVING) {
            val startedDate = prefs.getString(KEY_STARTED_DATE, "")
            if (startedDate != null && startedDate.isNotEmpty() && startedDate != today) {
                val now = System.currentTimeMillis()
                prefs.edit()
                    .putLong(KEY_STARTED_AT, now)
                    .putString(KEY_STARTED_DATE, today)
                    .apply()
                Log.d(TAG, "운행 중 날짜 변경: startedAt → 현재 시각 ($now)")
                OtwFileLogger.log(TAG, "운행 중 날짜 변경: startedAt → 현재 시각")
            }
        }
    }

    private fun accumulateDrivingTime(ctx: Context, durationMs: Long) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStr()
        val savedDate = prefs.getString(KEY_TOTAL_DATE, "")

        val current = if (today == savedDate) {
            prefs.getLong(KEY_TOTAL_TODAY, 0L)
        } else {
            0L
        }

        prefs.edit()
            .putLong(KEY_TOTAL_TODAY, current + durationMs)
            .putString(KEY_TOTAL_DATE, today)
            .apply()
    }

    fun getTodayDrivingTimeMs(ctx: Context): Long {
        // 날짜 변경 체크 (UI 갱신 시마다 호출됨)
        checkAndResetDate(ctx)

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStr()
        val savedDate = prefs.getString(KEY_TOTAL_DATE, "")

        val accumulated = if (today == savedDate) {
            prefs.getLong(KEY_TOTAL_TODAY, 0L)
        } else 0L

        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        val ongoing = if (startedAt > 0L && getMode(ctx) == DrivingMode.DRIVING) {
            val now = System.currentTimeMillis()
            val todayMidnight = todayMidnightMs()
            val effectiveStart = maxOf(startedAt, todayMidnight)
            now - effectiveStart
        } else 0L

        return accumulated + ongoing
    }

    /** 테스트용: 날짜 문자열 반환 */
    internal fun todayStr() =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    internal fun todayMidnightMs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
