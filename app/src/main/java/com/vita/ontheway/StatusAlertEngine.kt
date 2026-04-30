package com.vita.ontheway

import android.content.Context

/**
 * Phase 1 계산 엔진: 상태 기반 알림.
 *
 * 1분 주기 체크, 조건 충족 시 OutputController.emit().
 * 같은 알림은 1회만 (세션 내 반복 방지).
 */
object StatusAlertEngine {

    private var alertedGoal = false

    /** 운행 시작 시 리셋 */
    fun reset() {
        alertedGoal = false
    }

    /**
     * 매 1분마다 호출.
     * 조건 체크 후 OutputController.emit().
     */
    fun checkAndAlert(ctx: Context) {
        try {
            checkGoalAchieved(ctx)
        } catch (_: Exception) {}
    }

    private fun checkGoalAchieved(ctx: Context) {
        if (alertedGoal) return
        val earning = EarningManager.getTodayEarning(ctx)
        val goal = EarningManager.getGoal(ctx)
        if (goal > 0 && earning >= goal) {
            alertedGoal = true
            OutputController.emitStatusAlert(
                ctx = ctx, ttsText = "목표 달성", overlayText = "목표 달성",
                tts = OnTheWayService.instance?.let { getTts(it) },
                ttsReady = OnTheWayService.instance?.let { isTtsReady(it) } ?: false
            )
            OtwFileLogger.log("StatusAlert", "목표 달성 알림: ${earning}원 >= ${goal}원")
        }
    }

    // TTS 접근 헬퍼 — OnTheWayService의 private TTS에 간접 접근
    private fun getTts(service: OnTheWayService): android.speech.tts.TextToSpeech? {
        return try {
            val field = OnTheWayService::class.java.getDeclaredField("tts")
            field.isAccessible = true
            field.get(service) as? android.speech.tts.TextToSpeech
        } catch (_: Exception) { null }
    }

    private fun isTtsReady(service: OnTheWayService): Boolean {
        return try {
            val field = OnTheWayService::class.java.getDeclaredField("ttsReady")
            field.isAccessible = true
            field.getBoolean(service)
        } catch (_: Exception) { false }
    }
}
