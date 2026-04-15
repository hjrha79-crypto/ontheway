package com.vita.ontheway

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * v3.0 일별 리포트: 마지막 콜 감지 후 30분간 새 콜 없으면 알림 생성
 */
object DailyReport {

    private const val TAG = "DailyReport"
    private const val CHANNEL_ID = "daily_report"
    private const val NOTIFICATION_ID = 9001
    private const val IDLE_THRESHOLD_MS = 30 * 60 * 1000L  // 30분

    private var reportSentToday = false
    private var lastCheckDate = ""
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null

    /** 콜 감지 시마다 호출 - 타이머 리셋 */
    fun onCallDetected(ctx: Context) {
        if (!AdvancedPrefs.isDailyReportEnabled(ctx)) return

        val today = todayStr()
        if (lastCheckDate != today) {
            lastCheckDate = today
            reportSentToday = false
        }
        if (reportSentToday) return

        // 기존 타이머 취소 후 30분 타이머 재시작
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = Runnable { generateReport(ctx) }
        handler.postDelayed(pendingRunnable!!, IDLE_THRESHOLD_MS)
    }

    private fun generateReport(ctx: Context) {
        if (reportSentToday) return
        reportSentToday = true

        val detail = FilterLog.getTodayDetail(ctx)
        val earnings = EarningsTracker.getToday(ctx)
        val fmt = java.text.NumberFormat.getNumberInstance()

        // 아쉬운 콜: 넘긴 콜 중 단가 가장 높은 상위 3건
        val regrettable = getRegrettableCalls(ctx, 3)
        val regretStr = if (regrettable.isNotEmpty()) {
            regrettable.joinToString(", ") { "${fmt.format(it)}원" }
        } else "없음"

        val hourlyStr = if (earnings.hourlyRate > 0) "${fmt.format(earnings.hourlyRate)}원/h" else "측정 불가"

        val message = "오늘 ${detail.total}건 감지, ${earnings.acceptedCount}건 수락\n" +
            "매출 ${fmt.format(earnings.totalRevenue)}원, 시급 $hourlyStr\n" +
            "넘긴 콜 중 아쉬운 콜: $regretStr"

        showNotification(ctx, "오늘 요약", message)
        Log.d(TAG, "일별 리포트 전송: $message")
    }

    /** 넘긴 콜(REJECT) 중 단가 높은 상위 N건의 금액 반환 */
    private fun getRegrettableCalls(ctx: Context, count: Int): List<Int> {
        val entries = FilterLog.getAll(ctx)
        val todayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }.timeInMillis

        val rejected = mutableListOf<Pair<Int, Int>>()  // price, unitPrice
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            if (e.getLong("ts") < todayStart) continue
            if (e.optString("verdict") != "REJECT") continue
            rejected.add(e.optInt("price", 0) to e.optInt("unitPrice", 0))
        }

        // 단가가 높은 순으로 정렬, 단가 없으면 금액 순
        return rejected.sortedByDescending { it.second.takeIf { u -> u > 0 } ?: it.first }
            .take(count)
            .map { it.first }
    }

    private fun showNotification(ctx: Context, title: String, message: String) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "일별 리포트", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "OnTheWay 일별 요약 알림"
            }
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun todayStr() = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
        .format(java.util.Date())
}
