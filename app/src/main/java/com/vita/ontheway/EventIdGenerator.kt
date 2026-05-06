package com.vita.ontheway

import java.security.MessageDigest

object EventIdGenerator {
    private const val TIME_BUCKET_MS = 300_000L  // 5분 윈도우 (기존 10초 → cooldown과 동기화)

    /**
     * 같은 콜이 여러 이벤트로 쪼개져도 같은 ID가 나오도록.
     *
     * FIX-T2CN: orderId(배민 T2CN) 있으면 5분 bucket 없이 stable ID.
     * orderId 없으면 기존 storeName+price+bucket fallback.
     */
    fun generate(
        storeName: String?,
        price: Int,
        timestamp: Long = System.currentTimeMillis(),
        orderId: String? = null
    ): String {
        val raw = if (!orderId.isNullOrBlank()) {
            // stable: 주문번호만으로 고유 식별 (시간 무관)
            orderId
        } else {
            val bucket = timestamp / TIME_BUCKET_MS
            "${storeName ?: "UNKNOWN"}|$price|$bucket"
        }
        val md = MessageDigest.getInstance("SHA-1")
        val hash = md.digest(raw.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }
}
