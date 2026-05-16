package com.vita.ontheway.core

import java.util.UUID

/**
 * Core Pipeline Phase 1: 파싱 결과.
 * 추출만. 추천 판단 X. TTS 판단 X.
 */
data class ParsedEvent(
    val parsedEventId: String = UUID.randomUUID().toString().take(12),
    val rawEventId: String,
    val parserName: String,           // "BaeminParser" / "CoupangParser" / "BaeminNlsParser" / "CoupangNlsParser"
    val parserVersion: String = "1",
    val platform: String,             // "coupang" / "baemin"
    val eventType: String = "call_candidate",  // "call_candidate" / "order_status" / "navigation" / "noise" / "unknown"
    val parseStatus: String,          // "success" / "partial" / "failed"
    val failureReason: String? = null,
    val price: Int = 0,
    val distanceText: String? = null,
    val distanceValue: Double? = null,
    val bundleSize: Int = 1,
    val bundleType: String = "unknown",  // "single" / "bundle" / "unknown"
    val storeHint: String? = null,
    val pickupHint: String? = null,
    val dropoffHint: String? = null,
    val confidenceScore: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromDeliveryCall(
            rawEventId: String,
            parserName: String,
            call: com.vita.ontheway.DeliveryCall
        ): ParsedEvent = ParsedEvent(
            rawEventId = rawEventId,
            parserName = parserName,
            platform = call.platform,
            eventType = "call_candidate",
            parseStatus = if (call.parseSuccess) "success" else "partial",
            price = call.price,
            distanceText = call.distance?.let { "${it}km" },
            distanceValue = call.distance,
            bundleSize = call.bundleCount,
            bundleType = when {
                call.isMulti -> "bundle"
                call.bundleCount <= 1 -> "single"
                else -> "unknown"
            },
            storeHint = call.storeName.ifBlank { null },
            dropoffHint = call.destination.ifBlank { null },
            confidenceScore = if (call.parseSuccess) 1.0 else 0.5
        )

        fun failed(
            rawEventId: String,
            parserName: String,
            platform: String,
            reason: String
        ): ParsedEvent = ParsedEvent(
            rawEventId = rawEventId,
            parserName = parserName,
            platform = platform,
            eventType = "unknown",
            parseStatus = "failed",
            failureReason = reason,
            confidenceScore = 0.0
        )
    }
}
