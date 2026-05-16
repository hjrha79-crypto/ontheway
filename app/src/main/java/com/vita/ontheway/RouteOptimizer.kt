package com.vita.ontheway

/**
 * Route Mini v0.2: nearest-first 경로 최적화.
 *
 * 좌표가 있으면 haversine 거리, 없으면 주소 텍스트 heuristic.
 */
object RouteOptimizer {

    /**
     * nearest-first 정렬.
     * @param stops 정렬할 정류장 목록
     * @param currentAddress 현재 위치 주소 (좌표 없을 때 fallback)
     * @param currentLat 현재 위치 위도 (0.0이면 미사용)
     * @param currentLng 현재 위치 경도 (0.0이면 미사용)
     * @return 정렬된 새 리스트 (order 필드 갱신됨)
     */
    fun optimize(
        stops: List<RouteStop>,
        currentAddress: String? = null,
        currentLat: Double = 0.0,
        currentLng: Double = 0.0
    ): List<RouteStop> {
        if (stops.size <= 1) return stops.mapIndexed { i, s -> s.copy(order = i) }

        val remaining = stops.toMutableList()
        val result = mutableListOf<RouteStop>()
        var curLat = currentLat
        var curLng = currentLng
        var curAddr = currentAddress ?: ""

        while (remaining.isNotEmpty()) {
            val nearest = findNearest(remaining, curLat, curLng, curAddr)
            remaining.remove(nearest)
            result.add(nearest.copy(order = result.size))
            if (nearest.hasCoord()) {
                curLat = nearest.lat
                curLng = nearest.lng
            }
            curAddr = nearest.address
        }

        return result
    }

    private fun findNearest(
        candidates: List<RouteStop>,
        lat: Double, lng: Double, addr: String
    ): RouteStop {
        if (candidates.size == 1) return candidates[0]

        // 좌표 기반 (현재 좌표 + 후보 좌표 모두 있어야)
        if (lat != 0.0 && lng != 0.0) {
            val withCoord = candidates.filter { it.hasCoord() }
            if (withCoord.isNotEmpty()) {
                return withCoord.minByOrNull {
                    RouteGeocoder.haversineKm(lat, lng, it.lat, it.lng)
                } ?: candidates.first()
            }
        }

        // 주소 텍스트 heuristic fallback
        if (addr.isBlank()) return candidates.first()
        return candidates.minByOrNull { addressDistance(addr, it.address) } ?: candidates.first()
    }

    /**
     * 주소 텍스트 간 거리 heuristic (낮을수록 가까움).
     */
    internal fun addressDistance(a: String, b: String): Int {
        val dongA = extractDistrict(a, "동")
        val dongB = extractDistrict(b, "동")
        if (dongA.isNotBlank() && dongA == dongB) {
            return numberDiff(a, b)
        }

        val guA = extractDistrict(a, "구")
        val guB = extractDistrict(b, "구")
        if (guA.isNotBlank() && guA == guB) {
            return 10 + numberDiff(a, b)
        }

        val siA = extractDistrict(a, "시")
        val siB = extractDistrict(b, "시")
        if (siA.isNotBlank() && siA == siB) {
            return 20 + numberDiff(a, b)
        }

        return 30 + numberDiff(a, b)
    }

    private fun extractDistrict(addr: String, suffix: String): String {
        val regex = Regex("""(\S+$suffix)""")
        return regex.find(addr)?.value ?: ""
    }

    private fun numberDiff(a: String, b: String): Int {
        val numA = Regex("""(\d+)""").findAll(a).lastOrNull()?.value?.toIntOrNull() ?: 0
        val numB = Regex("""(\d+)""").findAll(b).lastOrNull()?.value?.toIntOrNull() ?: 0
        return (kotlin.math.abs(numA - numB) / 100).coerceAtMost(5)
    }
}
