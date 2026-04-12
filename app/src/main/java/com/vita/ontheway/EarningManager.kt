package com.vita.ontheway

import android.content.Context

object EarningManager {

    private const val PREF = "earning"

    fun getTodayEarning(context: Context): Int {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val savedDate = prefs.getString("date", "")
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        return if (savedDate == today) prefs.getInt("earning", 0) else 0
    }

    fun addEarning(context: Context, amount: Int): Int {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        val current = getTodayEarning(context)
        val newTotal = current + amount
        val currentCalls = getTodayCallCount(context)
        prefs.edit()
            .putString("date", today)
            .putInt("earning", newTotal)
            .putInt("call_count", currentCalls + 1)
            .apply()
        return newTotal
    }

    fun getTodayCallCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val savedDate = prefs.getString("date", "")
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        return if (savedDate == today) prefs.getInt("call_count", 0) else 0
    }

    fun getGoal(context: Context): Int {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("goal", 100000)
    }

    fun setGoal(context: Context, goal: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("goal", goal).apply()
    }

    fun getVehicleType(context: Context): String {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("vehicle", "\uC624\uD1A0\uBC14\uC774") ?: "\uC624\uD1A0\uBC14\uC774"
    }

    fun setVehicleType(context: Context, type: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("vehicle", type).apply()
    }

    fun markStartTime(context: Context) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        if (prefs.getString("start_date", "") != today) {
            prefs.edit()
                .putString("start_date", today)
                .putLong("start_time", System.currentTimeMillis())
                .apply()
        }
    }

    /** 시간당 수익 (원/시간). 아직 시작 안 했으면 0 */
    fun getEarningPace(context: Context): Int {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        if (prefs.getString("start_date", "") != today) return 0
        val startTime = prefs.getLong("start_time", 0L)
        if (startTime == 0L) return 0
        val elapsedHours = (System.currentTimeMillis() - startTime) / 3600000.0
        if (elapsedHours < 0.1) return 0
        return (getTodayEarning(context) / elapsedHours).toInt()
    }

    /** 목표 달성 예상 시간 (분). -1이면 계산 불가 */
    fun getMinutesToGoal(context: Context): Int {
        val pace = getEarningPace(context)
        if (pace <= 0) return -1
        val remaining = (getGoal(context) - getTodayEarning(context)).coerceAtLeast(0)
        if (remaining == 0) return 0
        return (remaining * 60.0 / pace).toInt()
    }

    fun getFuelCost(context: Context, km: Double): Int {
        return when (getVehicleType(context)) {
            "\uC624\uD1A0\uBC14\uC774" -> (km * 60).toInt()
            "\uC2B9\uC6A9\uCC28" -> (km * 120).toInt()
            "\uC2B9\uD569\uCC28" -> (km * 180).toInt()
            else -> (km * 60).toInt()
        }
    }
}
