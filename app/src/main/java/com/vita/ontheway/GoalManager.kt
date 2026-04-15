package com.vita.ontheway

import android.content.Context

/** v3.3 오늘의 목표 설정 + 달성 추적 */
object GoalManager {
    private const val PREFS = "goal_manager"

    // 목표 금액 (기본 100,000원)
    fun getGoalAmount(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("goal_amount", 100000)
    fun setGoalAmount(ctx: Context, v: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("goal_amount", v).apply()

    // 목표 건수 (기��� 30건)
    fun getGoalCount(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("goal_count", 30)
    fun setGoalCount(ctx: Context, v: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("goal_count", v).apply()

    // 목표 시급 (기본 15,000원/h)
    fun getGoalHourly(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("goal_hourly", 15000)
    fun setGoalHourly(ctx: Context, v: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("goal_hourly", v).apply()

    // 목표 알림 토글 (기본 OFF)
    fun isGoalAlertEnabled(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("goal_alert", false)
    fun setGoalAlert(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("goal_alert", v).apply()

    // 오늘 50%/100% 달성 알림 이미 보냈는지
    fun wasHalfAlerted(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("alerted_date_half", "") == todayStr()
    }
    fun markHalfAlerted(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("alerted_date_half", todayStr()).apply()

    fun wasFullAlerted(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("alerted_date_full", "") == todayStr()
    }
    fun markFullAlerted(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("alerted_date_full", todayStr()).apply()

    /** 달성률 (0.0 ~ 1.0+) */
    fun getProgress(ctx: Context): Float {
        val earnings = EarningsTracker.getToday(ctx)
        val goal = getGoalAmount(ctx)
        return if (goal > 0) earnings.totalRevenue.toFloat() / goal else 0f
    }

    private fun todayStr() = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
        .format(java.util.Date())
}
