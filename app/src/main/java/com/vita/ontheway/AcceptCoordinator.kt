package com.vita.ontheway

import android.content.Context
import android.util.Log

/**
 * 수락 감지 통합 조정기.
 * 4개 경로의 수락 감지를 단일 진입점으로 통합.
 *
 * 경로:
 * 1. SYSTEM_NOTI — TYPE_VIEW_CLICKED (알림 탭)
 * 2. COUPANG_PICKUP — 쿠팡 픽업 진행 화면
 * 3. BAEMIN_PROGRESS — 배민 배달 진행 화면
 * 4. FALLBACK — FIX-19 FilterLog 매칭
 */
object AcceptCoordinator {

    private const val TAG = "AcceptCoordinator"
    private const val DEDUP_WINDOW_MS = 300_000L  // 5분 (기존 10초 → 동일 콜 반복 인식 방지)

    enum class AcceptSource {
        SYSTEM_NOTI, COUPANG_PICKUP, BAEMIN_PROGRESS, FALLBACK
    }

    private var lastTs: Long = 0
    private var lastPrice: Int = 0
    private var lastPlatform: String = ""
    private var lastStoreName: String = ""

    /**
     * 수락 감지 단일 진입점.
     * 중복 방지 + EarningsTracker + JudgmentMatchLogger + 로그.
     */
    fun handleAccept(ctx: Context, source: AcceptSource, price: Int, platform: String, storeName: String = "") {
        if (price <= 0) return
        val now = System.currentTimeMillis()

        // 중복 방지 (5분 + 동일 금액+플랫폼+가게명)
        val sameCall = price == lastPrice && platform == lastPlatform &&
            (storeName.isBlank() || lastStoreName.isBlank() || storeName == lastStoreName)
        if (sameCall && now - lastTs < DEDUP_WINDOW_MS) {
            OtwFileLogger.log(TAG, "중복 스킵: $source ${price}원 $platform store='$storeName' (${now - lastTs}ms)")
            return
        }
        lastTs = now
        lastPrice = price
        lastPlatform = platform
        lastStoreName = storeName

        // 1. 시급 누적
        EarningsTracker.recordAccept(ctx, price, platform)

        // 2. 판정-행동 매칭
        try { JudgmentMatchLogger.onAcceptDetected(ctx) } catch (_: Exception) {}

        // 3. 로그
        OtwFileLogger.log(TAG, "수락 확정: $source ${price}원 $platform store='$storeName'")
        Log.d(TAG, "수락 확정: $source ${price}원 $platform store='$storeName'")
    }

    /**
     * FilterLog에서 최근 콜 금액 추출 (fallback용).
     * @param windowMs 매칭 윈도우 (기본 60초)
     */
    fun getRecentPrice(ctx: Context, windowMs: Long = 60_000): Pair<Int, String>? {
        val recent = FilterLog.getRecent(ctx, 1)
        if (recent.isEmpty()) return null
        val entry = recent[0]
        val ts = entry.optLong("ts", 0)
        if (System.currentTimeMillis() - ts > windowMs) return null
        val price = entry.optInt("price", 0)
        val platform = entry.optString("platform", "unknown")
        return if (price > 0) price to platform else null
    }
}
