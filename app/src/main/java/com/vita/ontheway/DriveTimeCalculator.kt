package com.vita.ontheway

import android.content.Context
import android.util.Log

/**
 * GPS speed 기반 실제 이동 시간 계산.
 * location_trace의 speed_kmh를 5분 윈도우로 그룹화,
 * 시간가중 avg(speed_kmh) > 1.0 인 윈도우만 카운트.
 *
 * v1.1: accuracy 필터, gap 상한, 시간가중 평균, unknown 분리
 */
object DriveTimeCalculator {
    private const val TAG = "OTW_DRIVE_TIME"
    private const val WINDOW_MS = 5 * 60 * 1000L // 5분
    private const val SPEED_THRESHOLD_KMH = 1.0
    private const val GAP_THRESHOLD_MS = 120_000L // 2분

    data class DriveTimeBreakdown(
        val drivingMs: Long,
        val stationaryMs: Long,
        val unknownMs: Long,
        val gpsCoveragePct: Double
    )

    /**
     * startTs~endTs 구간의 실제 이동 시간(ms) 반환.
     * location_trace가 비어있으면 0 반환.
     */
    fun calculateDriveTimeMs(ctx: Context, startTs: Long, endTs: Long): Long {
        if (startTs >= endTs) return 0L
        return try {
            val breakdown = calculateBreakdownFromDb(ctx, startTs, endTs)
            Log.i(TAG, "driving=${breakdown.drivingMs / 60_000}m stationary=${breakdown.stationaryMs / 60_000}m unknown=${breakdown.unknownMs / 60_000}m coverage=${"%.0f".format(breakdown.gpsCoveragePct)}%")
            breakdown.drivingMs
        } catch (e: Exception) {
            Log.w(TAG, "calculateDriveTimeMs failed: ${e.message}")
            0L
        }
    }

    fun calculateBreakdown(ctx: Context, startTs: Long, endTs: Long): DriveTimeBreakdown {
        if (startTs >= endTs) return DriveTimeBreakdown(0, 0, 0, 0.0)
        return try {
            calculateBreakdownFromDb(ctx, startTs, endTs)
        } catch (e: Exception) {
            Log.w(TAG, "calculateBreakdown failed: ${e.message}")
            DriveTimeBreakdown(0, 0, endTs - startTs, 0.0)
        }
    }

    private fun calculateBreakdownFromDb(ctx: Context, startTs: Long, endTs: Long): DriveTimeBreakdown {
        val db = CallLogDb.get(ctx).readableDatabase
        val cursor = db.rawQuery(
            """SELECT ts, COALESCE(speed_kmh, speed * 3.6, 0) as spd
               FROM location_trace
               WHERE ts >= ? AND ts <= ?
                 AND (accuracy IS NULL OR (accuracy > 0 AND accuracy <= 80))
               ORDER BY ts ASC""",
            arrayOf(startTs.toString(), endTs.toString())
        )

        val points = mutableListOf<Pair<Long, Double>>()
        cursor.use {
            while (it.moveToNext()) {
                points.add(it.getLong(0) to it.getDouble(1))
            }
        }

        if (points.isEmpty()) return DriveTimeBreakdown(0, 0, endTs - startTs, 0.0)

        return calculateBreakdownFromPoints(points, startTs, endTs)
    }

    /**
     * 포인트 리스트로부터 breakdown 계산.
     * 테스트에서도 사용 가능하도록 internal.
     */
    internal fun calculateBreakdownFromPoints(
        points: List<Pair<Long, Double>>,
        startTs: Long,
        endTs: Long
    ): DriveTimeBreakdown {
        if (points.isEmpty() || startTs >= endTs) {
            val total = if (endTs > startTs) endTs - startTs else 0L
            return DriveTimeBreakdown(0, 0, total, 0.0)
        }

        // gap 기반으로 유효 구간 마킹
        val gapSet = buildGapSet(points)

        var drivingMs = 0L
        var stationaryMs = 0L
        var unknownMs = 0L
        var windowStart = startTs

        while (windowStart < endTs) {
            val windowEnd = minOf(windowStart + WINDOW_MS, endTs)
            val windowDuration = windowEnd - windowStart
            val windowPoints = points.filter { it.first in windowStart until windowEnd }

            if (windowPoints.isEmpty()) {
                unknownMs += windowDuration
            } else if (hasGapInWindow(windowPoints, windowStart, windowEnd, gapSet)) {
                unknownMs += windowDuration
            } else {
                val weightedAvg = timeWeightedAverage(windowPoints, windowEnd)
                if (weightedAvg > SPEED_THRESHOLD_KMH) {
                    drivingMs += windowDuration
                } else {
                    stationaryMs += windowDuration
                }
            }

            windowStart = windowEnd
        }

        val totalMs = endTs - startTs
        val coveredMs = drivingMs + stationaryMs
        val coveragePct = if (totalMs > 0) coveredMs.toDouble() / totalMs * 100.0 else 0.0

        return DriveTimeBreakdown(drivingMs, stationaryMs, unknownMs, coveragePct)
    }

    // 하위 호환용
    internal fun calculateFromPoints(
        points: List<Pair<Long, Double>>,
        startTs: Long,
        endTs: Long
    ): Long = calculateBreakdownFromPoints(points, startTs, endTs).drivingMs

    /**
     * 인접 trace 간 gap > 2분인 구간을 Set<Int>(포인트 인덱스)으로 반환.
     * gapSet에 i가 있으면 points[i]~points[i+1] 사이에 gap이 있다는 의미.
     */
    private fun buildGapSet(points: List<Pair<Long, Double>>): Set<Int> {
        val gaps = mutableSetOf<Int>()
        for (i in 0 until points.size - 1) {
            if (points[i + 1].first - points[i].first > GAP_THRESHOLD_MS) {
                gaps.add(i)
            }
        }
        return gaps
    }

    /**
     * 윈도우 내 인접 포인트 간 gap > 2분이 있는지 확인.
     * 윈도우 경계(시작/끝)는 인위적이므로 체크하지 않음.
     */
    private fun hasGapInWindow(
        windowPoints: List<Pair<Long, Double>>,
        windowStart: Long,
        windowEnd: Long,
        gapSet: Set<Int>
    ): Boolean {
        for (i in 0 until windowPoints.size - 1) {
            if (windowPoints[i + 1].first - windowPoints[i].first > GAP_THRESHOLD_MS) return true
        }
        return false
    }

    /**
     * 시간가중 평균 속도.
     * 각 포인트의 가중치 = 다음 포인트까지의 시간, 마지막은 windowEnd까지.
     */
    private fun timeWeightedAverage(points: List<Pair<Long, Double>>, windowEnd: Long): Double {
        if (points.size == 1) return points[0].second

        var sumSpeedDt = 0.0
        var sumDt = 0L
        for (i in points.indices) {
            val nextTs = if (i < points.size - 1) points[i + 1].first else windowEnd
            val dt = nextTs - points[i].first
            if (dt > 0) {
                sumSpeedDt += points[i].second * dt
                sumDt += dt
            }
        }
        return if (sumDt > 0) sumSpeedDt / sumDt else 0.0
    }
}
