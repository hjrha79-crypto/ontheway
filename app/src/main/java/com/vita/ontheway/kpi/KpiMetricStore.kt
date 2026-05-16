package com.vita.ontheway.kpi

import android.content.ContentValues
import android.content.Context

/**
 * KPI 데이터 저장/조회.
 */
object KpiMetricStore {

    /** 실측 데이터 입력 (MANUAL / DAILY_REPORT / OCR) */
    fun insertGroundTruth(
        ctx: Context, date: String, platform: String?,
        actualRevenue: Int?, actualDriveMinutes: Int?,
        actualAcceptResult: String?,
        source: String = "MANUAL", callId: String? = null
    ) {
        val db = KpiMetricsDb.get(ctx).writableDatabase
        val cv = ContentValues().apply {
            put("date", date)
            if (callId != null) put("call_id", callId)
            if (platform != null) put("platform", platform)
            if (actualRevenue != null) put("actual_revenue", actualRevenue)
            if (actualDriveMinutes != null) put("actual_drive_minutes", actualDriveMinutes)
            if (actualAcceptResult != null) put("actual_accept_result", actualAcceptResult)
            put("source", source)
            put("created_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(KpiMetricsDb.TABLE_GROUND_TRUTH, null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 일일 KPI 요약 저장 (upsert) */
    fun saveDailySummary(ctx: Context, summary: KpiDailySummary) {
        val db = KpiMetricsDb.get(ctx).writableDatabase
        val cv = ContentValues().apply {
            put("date", summary.date)
            put("detected_calls", summary.detectedCalls)
            put("raw_nls_calls", summary.rawNlsCalls)
            put("confirmed_count", summary.confirmedCount)
            put("rejected_false_count", summary.rejectedFalseCount)
            put("unconfirmed_count", summary.unconfirmedCount)
            put("false_accept_rate", summary.falseAcceptRate)
            put("revenue_bubble_rate", summary.revenueBubbleRate)
            put("drive_time_bubble_rate", summary.driveTimeBubbleRate)
            put("missing_call_rate", summary.missingCallRate)
            put("coupang_accept_detection_rate", summary.coupangAcceptDetectionRate)
            put("recommendation_coverage", summary.recommendationCoverage)
            put("recommendation_latency_ms_p50", summary.recommendationLatencyP50)
            put("unknown_rate", summary.unknownRate)
            put("sample_size", summary.sampleSize)
            put("gate_pass", if (summary.gatePass) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(KpiMetricsDb.TABLE_DAILY_SUMMARY, null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 특정 날짜 ground truth 합산 */
    fun getGroundTruth(ctx: Context, date: String): GroundTruthSummary {
        val db = KpiMetricsDb.get(ctx).readableDatabase
        val cursor = db.rawQuery(
            "SELECT SUM(actual_revenue), SUM(actual_drive_minutes), COUNT(*) " +
            "FROM ${KpiMetricsDb.TABLE_GROUND_TRUTH} WHERE date = ?",
            arrayOf(date)
        )
        return cursor.use {
            if (it.moveToFirst()) {
                GroundTruthSummary(
                    totalRevenue = it.getInt(0),
                    totalDriveMinutes = it.getInt(1),
                    entryCount = it.getInt(2)
                )
            } else {
                GroundTruthSummary()
            }
        }
    }

    /** 특정 날짜 일일 요약 조회 */
    fun getDailySummary(ctx: Context, date: String): KpiDailySummary? {
        val db = KpiMetricsDb.get(ctx).readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM ${KpiMetricsDb.TABLE_DAILY_SUMMARY} WHERE date = ?",
            arrayOf(date)
        )
        return cursor.use {
            if (it.moveToFirst()) parseDailySummary(it) else null
        }
    }

    /** 최근 N일 요약 목록 */
    fun getRecentSummaries(ctx: Context, days: Int = 7): List<KpiDailySummary> {
        val db = KpiMetricsDb.get(ctx).readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM ${KpiMetricsDb.TABLE_DAILY_SUMMARY} ORDER BY date DESC LIMIT ?",
            arrayOf(days.toString())
        )
        val result = mutableListOf<KpiDailySummary>()
        cursor.use {
            while (it.moveToNext()) {
                result.add(parseDailySummary(it))
            }
        }
        return result
    }

    private fun parseDailySummary(c: android.database.Cursor): KpiDailySummary {
        fun col(name: String) = c.getColumnIndex(name)
        return KpiDailySummary(
            date = c.getString(col("date")),
            detectedCalls = c.getInt(col("detected_calls")),
            rawNlsCalls = c.getInt(col("raw_nls_calls")),
            confirmedCount = c.getInt(col("confirmed_count")),
            rejectedFalseCount = c.getInt(col("rejected_false_count")),
            unconfirmedCount = c.getInt(col("unconfirmed_count")),
            falseAcceptRate = if (c.isNull(col("false_accept_rate"))) null else c.getDouble(col("false_accept_rate")),
            revenueBubbleRate = if (c.isNull(col("revenue_bubble_rate"))) null else c.getDouble(col("revenue_bubble_rate")),
            driveTimeBubbleRate = if (c.isNull(col("drive_time_bubble_rate"))) null else c.getDouble(col("drive_time_bubble_rate")),
            missingCallRate = if (c.isNull(col("missing_call_rate"))) null else c.getDouble(col("missing_call_rate")),
            coupangAcceptDetectionRate = if (c.isNull(col("coupang_accept_detection_rate"))) null else c.getDouble(col("coupang_accept_detection_rate")),
            recommendationCoverage = if (c.isNull(col("recommendation_coverage"))) null else c.getDouble(col("recommendation_coverage")),
            recommendationLatencyP50 = if (c.isNull(col("recommendation_latency_ms_p50"))) null else c.getInt(col("recommendation_latency_ms_p50")),
            unknownRate = if (c.isNull(col("unknown_rate"))) null else c.getDouble(col("unknown_rate")),
            sampleSize = c.getInt(col("sample_size")),
            gatePass = c.getInt(col("gate_pass")) == 1
        )
    }

    data class GroundTruthSummary(
        val totalRevenue: Int = 0,
        val totalDriveMinutes: Int = 0,
        val entryCount: Int = 0
    )
}
