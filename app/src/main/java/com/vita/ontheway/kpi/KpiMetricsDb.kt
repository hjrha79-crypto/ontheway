package com.vita.ontheway.kpi

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * KPI-1: 거품률 측정 DB — kpi_metrics.db
 * 별도 DB 파일로 운영 데이터와 완전 분리.
 */
class KpiMetricsDb(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "kpi_metrics.db"
        const val DB_VERSION = 1
        const val TABLE_GROUND_TRUTH = "kpi_ground_truth"
        const val TABLE_DAILY_SUMMARY = "kpi_daily_summary"

        private var instance: KpiMetricsDb? = null
        fun get(ctx: Context): KpiMetricsDb {
            if (instance == null) instance = KpiMetricsDb(ctx.applicationContext)
            return instance!!
        }

        fun resetForTest() { instance = null }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_GROUND_TRUTH (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                call_id TEXT,
                date TEXT NOT NULL,
                platform TEXT,
                actual_revenue INTEGER,
                actual_drive_minutes INTEGER,
                actual_accept_result TEXT,
                source TEXT NOT NULL DEFAULT 'MANUAL',
                created_at INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX idx_gt_date ON $TABLE_GROUND_TRUTH(date)")

        db.execSQL("""
            CREATE TABLE $TABLE_DAILY_SUMMARY (
                date TEXT PRIMARY KEY,
                detected_calls INTEGER DEFAULT 0,
                raw_nls_calls INTEGER DEFAULT 0,
                confirmed_count INTEGER DEFAULT 0,
                rejected_false_count INTEGER DEFAULT 0,
                unconfirmed_count INTEGER DEFAULT 0,
                false_accept_rate REAL,
                revenue_bubble_rate REAL,
                drive_time_bubble_rate REAL,
                missing_call_rate REAL,
                coupang_accept_detection_rate REAL,
                recommendation_coverage REAL,
                recommendation_latency_ms_p50 INTEGER,
                unknown_rate REAL,
                sample_size INTEGER DEFAULT 0,
                gate_pass INTEGER DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        Log.d("KpiMetricsDb", "onUpgrade $old → $new")
    }
}
