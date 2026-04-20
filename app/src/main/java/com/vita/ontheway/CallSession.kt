package com.vita.ontheway

data class CallSession(
    val sessionId: String,           // UUID
    val eventId: String,             // SHA1(storeName + price + time_bucket_10s)
    val platform: String,            // "baemin" / "coupang" / "kakaot"
    var state: SessionState,         // IDLE / COLLECTING / FINALIZED / EXPIRED
    val startedAt: Long,             // timestamp
    var endedAt: Long? = null,

    // 수집되는 필드 (누적 업데이트)
    var price: Int = 0,
    var distanceKm: Double? = null,
    var storeName: String? = null,
    var destination: String? = null,
    var isBundle: Boolean = false,
    var bundleCount: Int = 1,

    // 파싱 원본 보관 (디버깅용)
    var lastRawTexts: List<String> = emptyList()
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        return (now - startedAt) > 30_000L  // 30초 timeout
    }

    fun isActive(): Boolean = state == SessionState.COLLECTING
}

enum class SessionState {
    IDLE,        // 초기
    COLLECTING,  // 이벤트 수집 중
    FINALIZED,   // 정상 종료 (카운트 대상)
    EXPIRED      // 타임아웃 종료
}
