package com.vita.ontheway.core

import java.util.UUID

/**
 * Core Pipeline Phase 1: 원시 이벤트.
 * 판단 X. 콜 여부 X. dedup X.
 * 원본 보존 + 추적 ID/hash 생성만.
 */
data class RawEvent(
    val rawEventId: String = UUID.randomUUID().toString().take(12),
    val sourceType: String,          // "notification" / "accessibility" / "ocr" / "manual"
    val platformGuess: String,       // "coupang" / "baemin" / "kakao" / "unknown"
    val packageName: String,
    val occurredAtWall: Long = System.currentTimeMillis(),
    val sourceTimestamp: Long = 0,   // sbn.postTime or event.eventTime
    val payloadHash: Int = 0,        // combined.hashCode() (원문 미저장)
    val payloadText: String? = null, // 선택적 원문 (debug only, truncated)
    val payloadJson: String? = null, // 구조화 payload (optional)
    val truncated: Boolean = false,
    val schemaVersion: Int = 1
) {
    companion object {
        fun fromNotification(
            pkg: String, title: String, text: String, bigText: String,
            postTime: Long
        ): RawEvent {
            val combined = "$title $text $bigText".trim()
            val platform = when (pkg) {
                "com.woowahan.bros" -> "baemin"
                "com.coupang.mobile.eats.courier" -> "coupang"
                "com.kakaomobility.flexer", "com.kakao.taxi.driver" -> "kakao"
                else -> "unknown"
            }
            return RawEvent(
                sourceType = "notification",
                platformGuess = platform,
                packageName = pkg,
                sourceTimestamp = postTime,
                payloadHash = combined.hashCode(),
                payloadText = combined.take(200),
                truncated = combined.length > 200
            )
        }

        fun fromAccessibility(
            pkg: String, texts: List<String>, eventTime: Long
        ): RawEvent {
            val combined = texts.joinToString("|").take(500)
            val platform = when (pkg) {
                "com.woowahan.bros" -> "baemin"
                "com.coupang.mobile.eats.courier" -> "coupang"
                "com.kakaomobility.flexer" -> "kakao"
                else -> "unknown"
            }
            return RawEvent(
                sourceType = "accessibility",
                platformGuess = platform,
                packageName = pkg,
                sourceTimestamp = eventTime,
                payloadHash = combined.hashCode(),
                payloadText = combined.take(200),
                truncated = combined.length > 200
            )
        }
    }
}
