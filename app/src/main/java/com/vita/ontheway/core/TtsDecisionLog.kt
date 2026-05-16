package com.vita.ontheway.core

import java.util.UUID

/**
 * Core Pipeline Phase 1: TTS 발화/억제 결정 로그.
 * TTS 엔진 교체 X. OutputController 호출 직전에 decision log만 기록.
 */
data class TtsDecisionLog(
    val ttsDecisionId: String = UUID.randomUUID().toString().take(12),
    val rawEventId: String? = null,
    val parsedEventId: String? = null,
    val callSessionId: String? = null,
    val platform: String,
    val decision: String,         // "speak" / "suppress"
    val reason: String,           // "first_seen" / "duplicate" / "pending" / "parse_failed" /
                                  // "low_confidence" / "cross_source_dedup" / "bundle_suppress" /
                                  // "tts_dedup" / "no_evidence" / "unknown"
    val messagePreview: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
