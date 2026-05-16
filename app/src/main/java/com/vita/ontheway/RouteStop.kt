package com.vita.ontheway

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Route Mini v0.2: 배송 정류장 하나.
 */
data class RouteStop(
    val id: String = UUID.randomUUID().toString().take(8),
    val address: String,
    val dong: String = "",
    val ho: String = "",
    val memo: String = "",
    var status: Status = Status.PENDING,
    var order: Int = 0,
    val lat: Double = 0.0,
    val lng: Double = 0.0
) {
    enum class Status { PENDING, CURRENT, DONE }

    fun hasCoord(): Boolean = lat != 0.0 && lng != 0.0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("address", address)
        put("dong", dong)
        put("ho", ho)
        put("memo", memo)
        put("status", status.name)
        put("order", order)
        if (lat != 0.0) put("lat", lat)
        if (lng != 0.0) put("lng", lng)
    }

    companion object {
        fun fromJson(j: JSONObject): RouteStop = RouteStop(
            id = j.optString("id", UUID.randomUUID().toString().take(8)),
            address = j.optString("address", ""),
            dong = j.optString("dong", ""),
            ho = j.optString("ho", ""),
            memo = j.optString("memo", ""),
            status = try { Status.valueOf(j.optString("status", "PENDING")) } catch (_: Exception) { Status.PENDING },
            order = j.optInt("order", 0),
            lat = j.optDouble("lat", 0.0),
            lng = j.optDouble("lng", 0.0)
        )

        fun listToJson(stops: List<RouteStop>): String {
            val arr = JSONArray()
            stops.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(json: String): List<RouteStop> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) { emptyList() }
        }
    }
}
