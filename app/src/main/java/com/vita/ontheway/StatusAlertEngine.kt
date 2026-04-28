package com.vita.ontheway

import android.content.Context

/**
 * Phase 1 계산 엔진: 상태 기반 알림.
 *
 * 1분 주기 체크, 조건 충족 시 OutputController.emit().
 * 같은 알림은 1회만 (세션 내 반복 방지).
 */
object StatusAlertEngine {

    private var alerted4h = false
    private var alerted5h = false
    private var alertedGoal = false

    /** 운행 시작 시 리셋 */
    fun reset() {
        alerted4h = false
        alerted5h = false
        alertedGoal = false
    }

    /**
     * 매 1분마다 호출.
     * 조건 체크 후 OutputController.emit().
     */
    fun checkAndAlert(ctx: Context) {
        try {
            checkDrivingTime(ctx)
            checkGoalAchieved(ctx)
        } catch (_: Exception) {}
    }

    private fun checkDrivingTime(ctx: Context) {
        val drivingMs = DrivingModeManager.getTodayDrivingTimeMs(ctx)
        val drivingHours = drivingMs / 3_600_000.0

        if (drivingHours >= 5.0 && !alerted5h) {
            alerted5h = true
            alerted4h = true  // 4h도 마킹
            OutputController.emit(
                ctx = ctx,
                ttsText = "운행 5시간입니다. 휴식을 권장합니다.",
                overlayText = "운행 5시간",
                mode = OutputMode.FULL,
                tts = OnTheWayService.instance?.let { getTts(it) },
                ttsReady = OnTheWayService.instance?.let { isTtsReady(it) } ?: false
            )
            OtwFileLogger.log("StatusAlert", "운행 5시간 경고 발생")
        } else if (drivingHours >= 4.0 && !alerted4h) {
            alerted4h = true
            OutputController.emit(
                ctx = ctx,
                ttsText = "운행 4시간입니다.",
                overlayText = "운행 4시간",
                mode = OutputMode.FULL,
                tts = OnTheWayService.instance?.let { getTts(it) },
                ttsReady = OnTheWayService.instance?.let { isTtsReady(it) } ?: false
            )
            OtwFileLogger.log("StatusAlert", "운행 4시간 경고 발생")
        }
    }

    private fun checkGoalAchieved(ctx: Context) {
        if (alertedGoal) return
        val earning = EarningManager.getTodayEarning(ctx)
        val goal = EarningManager.getGoal(ctx)
        if (goal > 0 && earning >= goal) {
            alertedGoal = true
            OutputController.emit(
                ctx = ctx,
                ttsText = "목표 달성! 오늘 수고 많으셨어요.",
                overlayText = "목표 달성!",
                mode = OutputMode.FULL,
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
