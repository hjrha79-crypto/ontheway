package com.vita.ontheway

import kotlin.math.*

/** v3.4 주요 지역 좌표 테이블 + Haversine 거리 계산 */
object LocationTable {

    data class AreaCoord(val name: String, val lat: Double, val lng: Double)

    // 경기 광주시 주요 동/읍/면 좌표
    private val AREA_COORDS = listOf(
        AreaCoord("태전동", 37.4095, 127.2555),
        AreaCoord("장지동", 37.4020, 127.2480),
        AreaCoord("양벌동", 37.3850, 127.2650),
        AreaCoord("오포읍", 37.3750, 127.2100),
        AreaCoord("중대동", 37.4200, 127.2700),
        AreaCoord("탄벌동", 37.4300, 127.2850),
        AreaCoord("역동", 37.4150, 127.2750),
        AreaCoord("광남동", 37.4050, 127.2600),
        AreaCoord("송정동", 37.4180, 127.2400),
        AreaCoord("경안동", 37.4130, 127.2550),
        AreaCoord("쌍령동", 37.4250, 127.2500),
        AreaCoord("목현동", 37.3950, 127.2350),
        AreaCoord("회덕동", 37.3900, 127.2750),
        AreaCoord("도척면", 37.3600, 127.2900),
        AreaCoord("곤지암읍", 37.3400, 127.3200),
        AreaCoord("초월읍", 37.3850, 127.2200),
        AreaCoord("실촌읍", 37.4400, 127.2300),
        AreaCoord("퇴촌면", 37.4500, 127.3100),
        AreaCoord("남종면", 37.4800, 127.2800),
        AreaCoord("남한산성면", 37.4700, 127.1800),
        AreaCoord("삼동", 37.4000, 127.2700),
        AreaCoord("능평동", 37.4100, 127.2450),
        // 성남/분당
        AreaCoord("판교", 37.3948, 127.1112),
        AreaCoord("분당", 37.3825, 127.1194),
        AreaCoord("수내동", 37.3780, 127.1250),
        AreaCoord("정자동", 37.3630, 127.1110),
        AreaCoord("야탑동", 37.4100, 127.1300),
        // 하남
        AreaCoord("미사동", 37.5600, 127.1900),
        AreaCoord("신장동", 37.5400, 127.2100),
        AreaCoord("덕풍동", 37.5500, 127.2000),
        // 용인
        AreaCoord("수지", 37.3222, 127.0988),
        AreaCoord("기흥", 37.2750, 127.1150),
        AreaCoord("처인", 37.2350, 127.2000)
    )

    /** 주소에서 동/읍/면 이름을 추출하여 좌표 매칭 */
    fun findCoord(address: String): AreaCoord? {
        if (address.isBlank()) return null
        // 정확 매칭
        for (area in AREA_COORDS) {
            if (address.contains(area.name)) return area
        }
        // 부분 매칭 (동 이름의 앞 2글자)
        for (area in AREA_COORDS) {
            val prefix = area.name.take(2)
            if (prefix.length >= 2 && address.contains(prefix)) return area
        }
        return null
    }

    /** Haversine 공식으로 두 좌표 간 거리 (km) */
    fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371.0 // 지구 반지름 (km)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    /** 현재위치 → 주소의 거리 (km). 매칭 불가 시 null */
    fun distanceTo(currentLat: Double, currentLng: Double, address: String): Double? {
        val coord = findCoord(address) ?: return null
        return haversineKm(currentLat, currentLng, coord.lat, coord.lng)
    }

    /** 현재위치 → 목적지 방향 (8방향) */
    fun getDirection(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): String {
        val dLat = toLat - fromLat
        val dLng = toLng - fromLng
        val angle = Math.toDegrees(atan2(dLng, dLat)).let { if (it < 0) it + 360 else it }
        return when {
            angle < 22.5 || angle >= 337.5 -> "북"
            angle < 67.5 -> "북동"
            angle < 112.5 -> "동"
            angle < 157.5 -> "남동"
            angle < 202.5 -> "남"
            angle < 247.5 -> "남서"
            angle < 292.5 -> "서"
            else -> "북서"
        }
    }

    /** 현재위치 기준 가장 가까운 동 이름 */
    fun getNearestArea(lat: Double, lng: Double): String? {
        if (lat == 0.0 && lng == 0.0) return null
        return AREA_COORDS.minByOrNull { haversineKm(lat, lng, it.lat, it.lng) }?.name
    }
}
