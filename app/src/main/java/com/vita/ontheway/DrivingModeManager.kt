package com.vita.ontheway

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DrivingModeManager {
    private const val PREFS = "advanced_prefs"
    private const val KEY_MODE = "driving_mode"
    private const val KEY_STARTED_AT = "driving_started_at"
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

        if (previousMode == mode) {
            Log.d(TAG, "already $mode")
            return
        }

        when (mode) {
            DrivingMode.DRIVING -> {
                prefs.edit()
                    .putString(KEY_MODE, mode.name)
                    .putLong(KEY_STARTED_AT, now)
                    .apply()
                Log.d(TAG, "DRIVING ON at $now")
                LocationTracker.startTracking(ctx)
            }
            DrivingMode.IDLE -> {
                val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
                if (startedAt > 0L) {
                    val duration = now - startedAt
                    accumulateDrivingTime(ctx, duration)
                    Log.d(TAG, "DRIVING OFF, duration: ${duration / 1000}s")
                }
                prefs.edit()
                    .putString(KEY_MODE, mode.name)
                    .remove(KEY_STARTED_AT)
                    .apply()
                LocationTracker.stopTracking()
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
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayStr()
        val savedDate = prefs.getString(KEY_TOTAL_DATE, "")

        val accumulated = if (today == savedDate) {
            prefs.getLong(KEY_TOTAL_TODAY, 0L)
        } else 0L

        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        val ongoing = if (startedAt > 0L && getMode(ctx) == DrivingMode.DRIVING) {
            val now = System.currentTimeMillis()
            // 자정 이전 시작이면 오늘 자정부터만 계산
            val todayMidnight = todayMidnightMs()
            val effectiveStart = maxOf(startedAt, todayMidnight)
            now - effectiveStart
        } else 0L

        return accumulated + ongoing
    }

    private fun todayStr() =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun todayMidnightMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
