package com.vita.ontheway

import android.content.Context

/**
 * v3.20: 2계층 FeatureFlags 시스템
 * - Stable: 상수, UI 미노출, 제거 불가
 * - Experimental: 일반 설정 노출 (최대 5개)
 * - Future: 개발자 옵션 전용, 기본 OFF, 그릇만
 */
object FeatureFlags {

    // 🟢 Stable (상수, 제거 불가)
    const val CORE_CALL_DETECTION = true
    const val CORE_TTS = true
    const val CORE_FILTER = true

    // 🟡 Experimental (일반 설정 노출, 최대 5개)
    var overlayEnabled = true
    var acceptLoggerEnabled = true
    var feedbackSystemEnabled = true
    var ttsLevelToggle = false   // 4번째 슬롯
    var screenFilterLogging = true  // 5번째 슬롯: 화면 필터 skip 로그 수집
    var coupangDiagnosticLogging = true  // v3.19.3: 쿠팡 Flutter 접근성 진단
    var baeminDiagnosticLogging = true  // v3.21: 배민 접근성 트리 진단 (거리 탐색용)

    // 🟡 UI Control
    var devMode = false  // 개발자 모드 (false=사용자 모드, 로고 5탭으로 전환)
    var showChatTab = false  // 채팅 탭 표시 여부 (false: 상태 탭만 표시)
    var showAcceptFeedback = false  // 수락 시 피드백 다이얼로그 (테스트용, 기본 OFF)

    // 🔴 Future (개발자 옵션, 기본 OFF, 그릇만)
    var hourlyWageDisplay = false
    var personalPreset = false
    var regionHeatmap = false
    var connectivityTTS = false

    private const val PREF_NAME = "feature_flags"

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        overlayEnabled = prefs.getBoolean("overlayEnabled", true)
        acceptLoggerEnabled = prefs.getBoolean("acceptLoggerEnabled", true)
        feedbackSystemEnabled = prefs.getBoolean("feedbackSystemEnabled", true)
        ttsLevelToggle = prefs.getBoolean("ttsLevelToggle", false)
        screenFilterLogging = prefs.getBoolean("screenFilterLogging", true)
        coupangDiagnosticLogging = prefs.getBoolean("coupangDiagnosticLogging", true)
        baeminDiagnosticLogging = prefs.getBoolean("baeminDiagnosticLogging", true)
        devMode = prefs.getBoolean("devMode", false)
        showChatTab = prefs.getBoolean("showChatTab", false)
        showAcceptFeedback = prefs.getBoolean("showAcceptFeedback", false)
        hourlyWageDisplay = prefs.getBoolean("hourlyWageDisplay", false)
        personalPreset = prefs.getBoolean("personalPreset", false)
        regionHeatmap = prefs.getBoolean("regionHeatmap", false)
        connectivityTTS = prefs.getBoolean("connectivityTTS", false)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean("overlayEnabled", overlayEnabled)
            putBoolean("acceptLoggerEnabled", acceptLoggerEnabled)
            putBoolean("feedbackSystemEnabled", feedbackSystemEnabled)
            putBoolean("ttsLevelToggle", ttsLevelToggle)
            putBoolean("screenFilterLogging", screenFilterLogging)
            putBoolean("coupangDiagnosticLogging", coupangDiagnosticLogging)
            putBoolean("baeminDiagnosticLogging", baeminDiagnosticLogging)
            putBoolean("devMode", devMode)
            putBoolean("showChatTab", showChatTab)
            putBoolean("showAcceptFeedback", showAcceptFeedback)
            putBoolean("hourlyWageDisplay", hourlyWageDisplay)
            putBoolean("personalPreset", personalPreset)
            putBoolean("regionHeatmap", regionHeatmap)
            putBoolean("connectivityTTS", connectivityTTS)
        }.apply()
    }
}
