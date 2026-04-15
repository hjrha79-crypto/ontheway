package com.vita.ontheway

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** v3.1 홈 화면 위젯: 오늘 건수, 매출, 시급 표시 */
class OnTheWayWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateWidget(ctx, mgr, id)
    }

    companion object {
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, OnTheWayWidget::class.java))
            for (id in ids) updateWidget(ctx, mgr, id)
        }

        private fun updateWidget(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_layout)
            val fmt = java.text.NumberFormat.getNumberInstance()

            val detail = FilterLog.getTodayDetail(ctx)
            val earnings = EarningsTracker.getToday(ctx)

            val count = if (earnings.acceptedCount > 0) earnings.acceptedCount else detail.total
            val revenue = earnings.totalRevenue
            val hourly = earnings.hourlyRate

            views.setTextViewText(R.id.widget_count, "오늘 ${count}건")
            views.setTextViewText(R.id.widget_revenue, "${fmt.format(revenue)}원")
            views.setTextViewText(R.id.widget_hourly, if (hourly > 0) "${fmt.format(hourly)}원/h" else "0원/h")

            // 탭하면 앱 실행
            val intent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            mgr.updateAppWidget(id, views)
        }
    }
}
