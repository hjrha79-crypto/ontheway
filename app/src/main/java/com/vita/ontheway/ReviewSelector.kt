package com.vita.ontheway

import android.content.Context

object ReviewSelector {

    private const val MAX_REVIEW_COUNT = 5

    /**
     * 오늘 call_log에서 복기 대상 핵심 5건 선정.
     * 이미 review_log에 등록된 건은 제외.
     *
     * 우선순위:
     * 1. 고액 콜: price >= 8,000원
     * 2. 판정 없음: verdict = "UNKNOWN" 또는 verdictMsg에 "원"만 포함
     * 3. 배민 콜 (거리 입력 가능): platform = "baemin"
     * 4. 나머지: price 높은 순
     */
    fun selectTopCalls(ctx: Context): List<ReviewEntry> {
        val db = CallLogDb.get(ctx)
        val allCalls = db.getTodayCallLogs()
        val alreadyReviewed = db.getReviewedCallTimestamps()

        val candidates = allCalls.filter { it.callTs !in alreadyReviewed }
        return selectFromCandidates(candidates)
    }

    /** 테스트 가능한 순수 선정 로직 */
    fun selectFromCandidates(candidates: List<ReviewEntry>): List<ReviewEntry> {
        if (candidates.isEmpty()) return emptyList()

        val highPrice = candidates.filter { it.price >= 8000 }
        val unknownVerdict = candidates.filter { it.price < 8000 && isUnknownVerdict(it) }
        val baeminCalls = candidates.filter { it.price < 8000 && !isUnknownVerdict(it) && it.platform == "baemin" }
        val rest = candidates.filter { it.price < 8000 && !isUnknownVerdict(it) && it.platform != "baemin" }
            .sortedByDescending { it.price }

        val result = mutableListOf<ReviewEntry>()
        for (pool in listOf(highPrice.sortedByDescending { it.price }, unknownVerdict, baeminCalls, rest)) {
            for (entry in pool) {
                if (result.size >= MAX_REVIEW_COUNT) break
                if (result.none { it.callTs == entry.callTs && it.price == entry.price }) {
                    result.add(entry)
                }
            }
            if (result.size >= MAX_REVIEW_COUNT) break
        }
        return result
    }

    private fun isUnknownVerdict(entry: ReviewEntry): Boolean {
        if (entry.verdict == "UNKNOWN" || entry.verdict.isBlank()) return true
        val msg = entry.verdictMsg.trim()
        if (msg.isEmpty()) return true
        // verdictMsg가 "7,000원" 같은 금액만 포함하면 판정 없음 취급
        return msg.matches(Regex("^[\\d,]+원$"))
    }
}
