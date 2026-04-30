package com.vita.ontheway

import android.content.Context

object ReviewSelector {

    private const val PAGE_SIZE = 10

    /**
     * 오늘 call_log에서 복기 대상 로드 (최신순).
     * 이미 review_log에 등록된 건은 제외.
     * limit 개수만큼 반환 (페이징용).
     */
    fun selectCalls(ctx: Context, limit: Int = PAGE_SIZE): List<ReviewEntry> {
        val db = CallLogDb.get(ctx)
        val allCalls = db.getTodayCallLogs() // 이미 최신순 (ORDER BY timestamp DESC)
        val alreadyReviewed = db.getReviewedCallTimestamps()
        return allCalls.filter { it.callTs !in alreadyReviewed }.take(limit)
    }

    /** 테스트용: 후보에서 최신순 limit건 반환 */
    fun selectFromCandidates(candidates: List<ReviewEntry>, limit: Int = PAGE_SIZE): List<ReviewEntry> {
        // candidates는 이미 최신순 가정
        return candidates.take(limit)
    }
}
