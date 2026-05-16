package com.vita.ontheway.kpi

/**
 * KPI 일일 요약 데이터 클래스.
 */
data class KpiDailySummary(
    val date: String,
    val detectedCalls: Int = 0,
    val rawNlsCalls: Int = 0,
    val confirmedCount: Int = 0,
    val rejectedFalseCount: Int = 0,
    val unconfirmedCount: Int = 0,
    // 1차 KPI
    val falseAcceptRate: Double? = null,
    val revenueBubbleRate: Double? = null,
    val missingCallRate: Double? = null,
    val coupangAcceptDetectionRate: Double? = null,
    val recommendationCoverage: Double? = null,
    val recommendationLatencyP50: Int? = null,
    // 보조 KPI
    val driveTimeBubbleRate: Double? = null,
    val unknownRate: Double? = null,
    // 게이트
    val sampleSize: Int = 0,
    val gatePass: Boolean = false
)
