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
            putBoolean("hourlyWageDisplay", hourlyWageDisplay)
            putBoolean("personalPreset", personalPreset)
            putBoolean("regionHeatmap", regionHeatmap)
            putBoolean("connectivityTTS", connectivityTTS)
        }.apply()
    }
}
