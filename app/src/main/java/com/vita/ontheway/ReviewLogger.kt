package com.vita.ontheway

import android.content.ContentValues
import android.content.Context
import kotlin.math.*

/**
 * 복기 시 GPS 거리 자동 계산.
 * location_trace에서 callTs ~ min(nextCallTs, callTs+25분) 구간의
 * GPS 포인트를 추출하여 Haversine 공식으로 거리를 합산한다.
 */
object ReviewLogger {

    private const val TAG = "ReviewLogger"
    private const val MAX_WINDOW_MS = 25L * 60 * 1000 // 25분

    /**
     * callTs 시점부터 다음 콜(또는 25분)까지의 GPS 이동 거리를 계산한다.
     * location_trace 포인트가 2개 미만이면 null 반환.
     */
    fun calculateGpsDistance(ctx: Context, callTs: Long): Float? {
        try {
            val db = CallLogDb.get(ctx).readableDatabase

            // 다음 콜 타임스탬프 조회
            val nextCallTs = run {
                val cursor = db.rawQuery(
                    "SELECT timestamp FROM ${CallLogDb.TABLE} WHERE timestamp > ? ORDER BY timestamp ASC LIMIT 1",
                    arrayOf(callTs.toString())
                )
                cursor.use { if (it.moveToFirst()) it.getLong(0) else null }
            }

            val endTs = minOf(
                nextCallTs ?: (callTs + MAX_WINDOW_MS),
                callTs + MAX_WINDOW_MS
            )

            // GPS 포인트 추출
            val cursor = db.rawQuery(
                "SELECT lat, lng FROM location_trace WHERE ts >= ? AND ts <= ? ORDER BY ts ASC",
                arrayOf(callTs.toString(), endTs.toString())
            )

            val points = mutableListOf<Pair<Double, Double>>()
            cursor.use {
                while (it.moveToNext()) {
                    points.add(it.getDouble(0) to it.getDouble(1))
                }
            }

            if (points.size < 2) return null

            // Haversine 거리 합산
            var totalKm = 0.0
            for (i in 1 until points.size) {
                totalKm += haversineKm(
                    points[i - 1].first, points[i - 1].second,
                    points[i].first, points[i].second
                )
            }

            val result = totalKm.toFloat()
            val windowMin = (endTs - callTs) / 60000
            OtwFileLogger.log(TAG, "GPS 거리 계산: ${"%.1f".format(result)}km (콜ts~ts+${windowMin}min, ${points.size}포인트)")
            return result
        } catch (e: Exception) {
            OtwFileLogger.log(TAG, "GPS 거리 계산 실패: ${e.message}")
            return null
        }
    }

    /** review_log에 gps_distance_km 업데이트 */
    fun updateGpsDistance(ctx: Context, callTs: Long, price: Int, gpsDistanceKm: Float) {
        try {
            val db = CallLogDb.get(ctx).writableDatabase
            val cv = ContentValues().apply {
                put("gps_distance_km", gpsDistanceKm.toDouble())
            }
            db.update("review_log", cv, "call_ts=? AND price=?",
                arrayOf(callTs.toString(), price.toString()))
        } catch (e: Exception) {
            OtwFileLogger.log(TAG, "GPS 거리 저장 실패: ${e.message}")
        }
    }

    /** Haversine 공식: 두 좌표 간 거리 (km) */
    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * asin(sqrt(a))
    }
}
