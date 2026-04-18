package com.vita.ontheway

import android.util.Log

/**
 * 배민 묶음배달 세션 관리 (선행 차단 방식)
 *
 * 상태: IDLE → COLLECTING → finalize() → IDLE
 * 트리거: "총 합계" / "X건 모두 수락" / "모두 거절"
 * 종료: 총 합계 금액 파싱 성공 시 즉시, 또는 15초 타임아웃
 */
object BaeminBundleSession {

    enum class State { IDLE, COLLECTING, FINALIZED }

    var state: State = State.IDLE
        private set

    private var sessionStartTime: Long = 0
    private var finalTotalPrice: Int = 0
    private var finalTotalPoint: Double = 0.0
    private var detectedBundleCount: Int = 0
    private val storeNames = mutableListOf<String>()
    private val collectedPrices = mutableListOf<Int>()
    private val collectedPoints = mutableListOf<Double>()

    const val SESSION_TIMEOUT_MS = 15_000L

    private val TOTAL_PRICE_PATTERN = Regex("총\\s*합계[^\\d]*(\\d[\\d,]*)\\s*원")
    private val TOTAL_POINT_PATTERN = Regex("([\\d.]+)\\s*P", RegexOption.IGNORE_CASE)
    private val BUNDLE_ACCEPT_PATTERN = Regex("(\\d+)\\s*건\\s*모두\\s*수락")
    private val BUNDLE_REJECT_PATTERN = Regex("모두\\s*거절")
    private val TOTAL_KEYWORD = Regex("총\\s*합계")

    /**
     * 텍스트에서 묶음 세션 트리거를 확인하고 세션을 시작/갱신한다.
     * @return true if session is now active
     */
    fun checkAndStartSession(joined: String): Boolean {
        val hasTotalKeyword = TOTAL_KEYWORD.containsMatchIn(joined)
        val acceptMatch = BUNDLE_ACCEPT_PATTERN.find(joined)
        val hasRejectAll = BUNDLE_REJECT_PATTERN.containsMatchIn(joined)

        if (!hasTotalKeyword && acceptMatch == null && !hasRejectAll) {
            return state == State.COLLECTING && !isTimedOut()
        }

        // 트리거 감지됨
        if (state == State.IDLE) {
            state = State.COLLECTING
            sessionStartTime = System.currentTimeMillis()
            finalTotalPrice = 0
            finalTotalPoint = 0.0
            detectedBundleCount = 0
            storeNames.clear()
            collectedPrices.clear()
            collectedPoints.clear()
            Log.d("BaeminBundle", "세션 시작")
        }

        // 건수 업데이트
        if (acceptMatch != null) {
            val count = acceptMatch.groupValues[1].toIntOrNull() ?: 0
            if (count > 0) detectedBundleCount = count
        }

        // 총 합계 금액 파싱
        val totalPriceMatch = TOTAL_PRICE_PATTERN.find(joined)
        if (totalPriceMatch != null) {
            val price = totalPriceMatch.groupValues[1].replace(",", "").toIntOrNull()
            if (price != null && price > 0) {
                finalTotalPrice = price
            }
        }

        // 총 합계 이후 포인트 파싱
        if (hasTotalKeyword) {
            val totalIdx = TOTAL_KEYWORD.find(joined)?.range?.last ?: 0
            val afterTotal = joined.substring(totalIdx)
            val pointMatch = TOTAL_POINT_PATTERN.find(afterTotal)
            if (pointMatch != null) {
                finalTotalPoint = pointMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            }
        }

        Log.d("BaeminBundle", "세션 갱신: count=$detectedBundleCount, price=$finalTotalPrice, point=$finalTotalPoint")
        return true
    }

    /** 세션 활성 여부 (타임아웃 체크 포함) */
    fun isActive(): Boolean {
        if (state != State.COLLECTING) return false
        if (isTimedOut()) {
            Log.d("BaeminBundle", "세션 타임아웃")
            return false
        }
        return true
    }

    /** 종료 가능 여부 (총 합계 금액 파싱 완료) */
    fun canFinalize(): Boolean = state == State.COLLECTING && finalTotalPrice > 0

    /** 개별 콜 데이터를 세션에 축적 */
    fun addCallData(price: Int, point: Double?, storeName: String?) {
        if (state != State.COLLECTING) return
        if (price > 0 && price !in collectedPrices) collectedPrices.add(price)
        if (point != null && point > 0) collectedPoints.add(point)
        if (!storeName.isNullOrEmpty() && storeName !in storeNames) storeNames.add(storeName)
    }

    /**
     * 세션 종료 → 묶음 DeliveryCall 반환.
     * 총 합계가 있으면 그 값 사용, 없으면 축적 금액 합산.
     */
    fun finalize(): DeliveryCall? {
        val price = if (finalTotalPrice > 0) finalTotalPrice else collectedPrices.sum()
        if (price <= 0) {
            reset()
            return null
        }

        val point: Double? = when {
            finalTotalPoint > 0 -> finalTotalPoint
            collectedPoints.isNotEmpty() -> collectedPoints.sum()
            else -> null
        }

        val count = when {
            detectedBundleCount > 0 -> detectedBundleCount
            collectedPrices.size >= 2 -> collectedPrices.size
            else -> 2
        }

        val uniqueStores = storeNames.distinct()
        val result = DeliveryCall(
            price = price,
            distance = null,
            isMulti = true,
            platform = "baemin",
            rawText = "묶음세션: ${count}건 ${price}원",
            storeName = uniqueStores.joinToString("+"),
            bundleCount = count,
            isMultiPickup = uniqueStores.size >= 2,
            point = point
        )

        Log.d("BaeminBundle", "세션 종료: ${count}건 ${price}원 point=$point stores=$uniqueStores")
        reset()
        return result
    }

    /** 타임아웃 시 축적 데이터로 종료 */
    fun finalizeOnTimeout(): DeliveryCall? {
        Log.d("BaeminBundle", "타임아웃 종료: prices=$collectedPrices")
        return finalize()
    }

    fun reset() {
        state = State.IDLE
        sessionStartTime = 0
        finalTotalPrice = 0
        finalTotalPoint = 0.0
        detectedBundleCount = 0
        storeNames.clear()
        collectedPrices.clear()
        collectedPoints.clear()
    }

    private fun isTimedOut(): Boolean {
        return state == State.COLLECTING &&
               System.currentTimeMillis() - sessionStartTime > SESSION_TIMEOUT_MS
    }
}
