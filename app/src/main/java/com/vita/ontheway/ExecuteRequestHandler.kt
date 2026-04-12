package com.vita.ontheway

import java.text.SimpleDateFormat
import java.util.*

// ──────────────────────────────────────────
// 실행 요청
// ──────────────────────────────────────────
data class ExecuteRequest(
    val requestType: String = "execute",
    val requestId: String,
    val callId: String
)

// ──────────────────────────────────────────
// 실행 결과
// ──────────────────────────────────────────
data class ExecuteResult(
    val status: String,           // "success" | "fail"
    val callId: String,
    val message: String,
    val nextAction: String,       // navigation_start | recommend_again | retry | none
    val duplicate: Boolean = false,
    val executedAt: String = ""
) {
    // Vita가 사용자에게 전달할 자연어 응답
    fun toNaturalLanguage(): String = when {
        duplicate -> ""
        status == "success" -> "콜 잡았습니다. 출발하세요!"
        message.contains("사라졌") -> "아쉽게도 콜이 사라졌어요. 다시 추천해 드릴까요?"
        else -> "수락에 실패했어요. 다시 시도해 드릴까요?"
    }
}

// ──────────────────────────────────────────
// 실행 핸들러 (싱글턴)
// request_id 기반 중복 실행 방지 포함
// ──────────────────────────────────────────
object ExecuteRequestHandler {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
    private val idFormat   = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    private var seqCounter = 0

    // 처리 이력: requestId → ExecuteResult (세션 만료 시 함께 소멸)
    private val executedRequests = mutableMapOf<String, ExecuteResult>()

    fun handle(request: ExecuteRequest): ExecuteResult {

        // 1. 중복 요청 확인
        executedRequests[request.requestId]?.let { cached ->
            return cached.copy(duplicate = true)
        }

        // 2. 세션 유효성 확인
        if (!RecommendationSessionManager.isSessionValid()) {
            return ExecuteResult(
                status = "fail",
                callId = request.callId,
                message = RecommendationSessionManager.getExpiredMessage(),
                nextAction = "recommend_again"
            ).also { pruneExpiredHistory() }
        }

        // 3. 세션에서 콜 확인
        val session = RecommendationSessionManager.getSession()
        val targetCall = session?.recommendations?.firstOrNull { it.callId == request.callId }
            ?: return buildResult("fail", request.callId, "콜 정보를 찾을 수 없습니다", "recommend_again", request.requestId)

        // 4. 수락 실행
        return try {
            OnTheWayService.instance?.acceptCurrentCall()
                ?: throw IllegalStateException("서비스 미연결")

            buildResult("success", request.callId, "${targetCall.dropoff} 콜 수락 완료", "navigation_start", request.requestId)
        } catch (e: Exception) {
            buildResult("fail", request.callId, "수락 실행 실패: ${e.message}", "retry", request.requestId)
        }
    }

    fun generateRequestId(): String {
        seqCounter++
        return "req_${idFormat.format(Date())}_${seqCounter.toString().padStart(3, '0')}"
    }

    fun clearHistory() {
        executedRequests.clear()
        seqCounter = 0
    }

    private fun pruneExpiredHistory() {
        if (!RecommendationSessionManager.isSessionValid()) executedRequests.clear()
    }

    private fun buildResult(status: String, callId: String, message: String, nextAction: String, requestId: String): ExecuteResult {
        val result = ExecuteResult(
            status = status, callId = callId, message = message,
            nextAction = nextAction, executedAt = dateFormat.format(Date())
        )
        executedRequests[requestId] = result
        return result
    }
}
