package com.vita.ontheway

import android.content.Context
import java.text.NumberFormat
import java.util.*

/**
 * Phase 1 계산 엔진: 시간당 수익 / 총 이동거리 / 대기 시간.
 *
 * 데이터: CallLogDb (accepted=1, 오늘) + DrivingModeManager (운행 시간)
 */
object EarningsCalculator {

    private val fmt = NumberFormat.getNumberInstance()
    private const val AVG_DELIVERY_MIN = 15  // 평균 배달 시간 (분)

    /**
     * 시간당 수익 (원/h).
     * = 오늘 수락 콜 총액 ÷ 운행 시간(분) × 60
     * 운행 30분 미만이면 null (데이터 부족).
     */
    fun calculateHourlyRate(ctx: Context): Long? {
        val drivingMs = DrivingModeManager.getTodayDrivingTimeMs(ctx)
        val drivingMin = drivingMs / 60_000
        if (drivingMin < 30) return null

        val totalEarning = getTodayAcceptedTotal(ctx)
        if (totalEarning <= 0) return null

        return totalEarning.toLong() * 60 / drivingMin
    }

    /**
     * 시간당 수익 포맷 문자열.
     * "시간당 12,400원" 또는 null.
     */
    fun formatHourlyRate(ctx: Context): String? {
        val rate = calculateHourlyRate(ctx) ?: return null
        return "시간당 ${fmt.format(rate)}원"
    }

    /**
     * 오늘 총 이동 거리 (km).
     * = 오늘 수락 콜들의 distance 누적.
     */
    fun calculateTotalDistance(ctx: Context): Float {
        return try {
            val todayStart = todayStartMs()
            val db = CallLogDb.get(ctx).readableDatabase
            val cursor = db.rawQuery(
                "SELECT COALESCE(SUM(distance), 0) FROM ${CallLogDb.TABLE} WHERE accepted = 1 AND timestamp >= ? AND distance > 0",
                arrayOf(todayStart.toString())
            )
            cursor.use {
                if (it.moveToFirst()) it.getFloat(0) else 0f
            }
        } catch (_: Exception) { 0f }
    }

    /**
     * 총 이동 거리 포맷 문자열.
     * "총 4.6km" 또는 null (0이면).
     */
    fun formatTotalDistance(ctx: Context): String? {
        val km = calculateTotalDistance(ctx)
        if (km <= 0f) return null
        return "총 ${"%.1f".format(km)}km"
    }

    /**
     * 대기 시간 (분).
     * = 운행 시간 - (수락 콜 수 × 평균 배달 시간 15분)
     * 상한: 운행 시간 이하. 수락 0건이면 0 반환.
     */
    fun calculateWaitTime(ctx: Context): Int {
        val drivingMs = DrivingModeManager.getTodayDrivingTimeMs(ctx)
        val drivingMin = (drivingMs / 60_000).toInt()
        if (drivingMin <= 0) return 0

        val acceptedCount = getTodayAcceptedCount(ctx)
        if (acceptedCount <= 0) return 0

        val estimatedDeliveryMin = acceptedCount * AVG_DELIVERY_MIN
        return (drivingMin - estimatedDeliveryMin).coerceIn(0, drivingMin)
    }

    /**
     * 대기 시간 포맷 문자열.
     * "대기 8분" 또는 null (0이면).
     */
    fun formatWaitTime(ctx: Context): String? {
        val min = calculateWaitTime(ctx)
        if (min <= 0) return null
        return "대기 ${min}분"
    }

    /** 오늘 수락 콜 총액 */
    private fun getTodayAcceptedTotal(ctx: Context): Int {
        return try {
            val todayStart = todayStartMs()
            val db = CallLogDb.get(ctx).readableDatabase
            val cursor = db.rawQuery(
                "SELECT COALESCE(SUM(price), 0) FROM ${CallLogDb.TABLE} WHERE accepted = 1 AND timestamp >= ?",
                arrayOf(todayStart.toString())
            )
            cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (_: Exception) { 0 }
    }

    /** 오늘 수락 콜 건수 */
    private fun getTodayAcceptedCount(ctx: Context): Int {
        return try {
            val todayStart = todayStartMs()
            val db = CallLogDb.get(ctx).readableDatabase
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM ${CallLogDb.TABLE} WHERE accepted = 1 AND timestamp >= ?",
                arrayOf(todayStart.toString())
            )
            cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (_: Exception) { 0 }
    }

    private fun todayStartMs(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
