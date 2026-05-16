package com.vita.ontheway.core

import android.content.Context
import android.util.Log

/**
 * Core Pipeline Phase 1: 관측 계층 facade.
 *
 * 기존 로직 호출만 — 변경 X.
 * 외부에서 보는 진입점을 하나로 모으되, 기존 SessionManager/
 * CallSessionRegistry/CrossSourceCallDetectionDedup는 호출만.
 */
object CorePipeline {

    private const val TAG = "CorePipeline"

    /**
     * RawEvent 기록 (DB 저장).
     * 알림/접근성 이벤트 수신 직후 호출.
     */
    fun recordRawEvent(ctx: Context, event: RawEvent) {
        try {
            CoreInstrumentationDb.get(ctx).insertRawEvent(event)
        } catch (e: Exception) {
            Log.w(TAG, "RawEvent 저장 실패: ${e.message}")
        }
    }

    /**
     * ParsedEvent 기록 (DB 저장).
     * parser 결과 확보 후 호출.
     */
    fun recordParsedEvent(ctx: Context, event: ParsedEvent) {
        try {
            CoreInstrumentationDb.get(ctx).insertParsedEvent(event)
        } catch (e: Exception) {
            Log.w(TAG, "ParsedEvent 저장 실패: ${e.message}")
        }
    }

    /**
     * TtsDecisionLog 기록 (DB 저장).
     * OutputController.emit 직전 또는 suppress 시점에 호출.
     */
    fun recordTtsDecision(ctx: Context, decision: TtsDecisionLog) {
        try {
            CoreInstrumentationDb.get(ctx).insertTtsDecision(decision)
        } catch (e: Exception) {
            Log.w(TAG, "TtsDecision 저장 실패: ${e.message}")
        }
    }
}
