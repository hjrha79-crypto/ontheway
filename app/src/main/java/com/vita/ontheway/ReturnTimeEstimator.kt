package com.vita.ontheway

import android.content.Context
import kotlin.math.*

/**
 * Phase 1 계산 엔진: 복귀 시간 추정.
 *
 * 운행 시작 GPS 위치 저장 → 현재 GPS 직선 거리 ÷ 20km/h.
 */
object ReturnTimeEstimator {

    private const val AVG_SPEED_KMH = 20.0
    private const val PREFS = "return_estimator"

    private var startLat: Double = 0.0
    private var startLng: Double = 0.0

    /** 운행 시작 시 호출 — 현재 GPS 위치를 시작점으로 저장 */
    fun saveStartLocation(ctx: Context, lat: Double, lng: Double) {
        if (lat == 0.0 && lng == 0.0) return
        startLat = lat
        startLng = lng
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("start_lat", lat.toFloat())
            .putFloat("start_lng", lng.toFloat())
            .apply()
    }

    /** SharedPreferences에서 복원 */
    fun restore(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        startLat = prefs.getFloat("start_lat", 0f).toDouble()
        startLng = prefs.getFloat("start_lng", 0f).toDouble()
    }

    /**
     * 복귀 예상 시간 (분).
     * GPS 없거나 시작 위치 미저장이면 null.
     */
    fun estimateReturnMinutes(): Int? {
        if (startLat == 0.0 && startLng == 0.0) return null
        val curLat = OnTheWayService.currentLat
        val curLng = OnTheWayService.currentLng
        if (curLat == 0.0 && curLng == 0.0) return null

        val distKm = haversineKm(startLat, startLng, curLat, curLng)
        if (distKm < 0.1) return 0  // 거의 같은 위치

        val minutes = (distKm / AVG_SPEED_KMH * 60).toInt()
        return minutes.coerceAtLeast(1)
    }

    /**
     * 포맷 문자열. "복귀 7분" 또는 null.
     */
    fun formatReturnTime(): String? {
        val min = estimateReturnMinutes() ?: return null
        return "복귀 ${min}분"
    }

    /** Haversine formula — 두 GPS 좌표 간 직선 거리 (km) */
    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}
