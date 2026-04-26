package com.vita.ontheway

data class LocationTrace(
    val id: Long = 0L,
    val mobilityEventId: String? = null,
    val ts: Long,
    val lat: Double,
    val lng: Double,
    val speed: Float,
    val accuracy: Float
)
