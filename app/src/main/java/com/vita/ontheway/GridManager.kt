package com.vita.ontheway

import android.content.Context

/**
 * 외지 히트맵 v0 — 100m × 100m grid 방문 기록.
 *
 * Stage 0: 데이터 수집만 (활동권 판정 X, TTS X)
 * Stage 1 (7~14일 후): 단순 거리 외지 판정
 * Stage 2~3 (30일 후): visit_count 기반
 */
object GridManager {

    private const val GRID_SIZE = 0.001  // ~100m
    private const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000  // 30일
    private const val PREFS_NAME = "grid_visits"

    /** lat/lng → grid_id ("37412_127289") */
    fun toGridId(lat: Double, lng: Double): String {
        val gLat = (lat / GRID_SIZE).toInt()
        val gLng = (lng / GRID_SIZE).toInt()
        return "${gLat}_${gLng}"
    }

    /** 방문 기록 (LocationTracker GPS 콜백에서 호출) */
    fun recordVisit(ctx: Context, lat: Double, lng: Double) {
        val gridId = toGridId(lat, lng)
        val now = System.currentTimeMillis()
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(gridId, null)

        val count: Int
        if (existing != null) {
            val parts = existing.split("|")
            count = (parts.getOrNull(0)?.toIntOrNull() ?: 0) + 1
            prefs.edit().putString(gridId, "$count|$now|${parts.getOrNull(2) ?: now}").apply()
        } else {
            count = 1
            prefs.edit().putString(gridId, "1|$now|$now").apply()
        }

        if (count == 1 || count % 50 == 0) {
            OtwFileLogger.log("GridManager", "grid=$gridId count=$count")
        }
    }

    /** 30일 이상 된 방문 정리 */
    fun cleanup(ctx: Context): Int {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        val editor = prefs.edit()
        var removed = 0
        for ((key, value) in prefs.all) {
            try {
                val parts = (value as String).split("|")
                val lastSeen = parts.getOrNull(1)?.toLongOrNull() ?: 0
                if (lastSeen < cutoff) { editor.remove(key); removed++ }
            } catch (_: Exception) { editor.remove(key); removed++ }
        }
        editor.apply()
        if (removed > 0) OtwFileLogger.log("GridManager", "cleanup: $removed grids 제거")
        return removed
    }

    /** 활성 grid 수 */
    fun activeCount(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all.size

    /** 특정 grid 방문 횟수 */
    fun visitCount(ctx: Context, gridId: String): Int {
        val s = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(gridId, null) ?: return 0
        return s.split("|").getOrNull(0)?.toIntOrNull() ?: 0
    }
}
