package com.vita.ontheway

// ──────────────────────────────────────────
// SessionContext
// Vita OS → OnTheWay 전달 맥락 객체
// ──────────────────────────────────────────

data class SessionContext(
    val currentLocation: String = "",
    val destination: String = "",
    val departureTime: String = "즉시",
    val goal: String = "",
    val createdAt: String = ""
) {
    fun isValid(): Boolean =
        currentLocation.isNotBlank() && destination.isNotBlank()
}
