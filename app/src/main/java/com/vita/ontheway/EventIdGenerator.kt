package com.vita.ontheway

import java.security.MessageDigest

object EventIdGenerator {
    private const val TIME_BUCKET_MS = 10_000L  // 10초 윈도우

    /**
     * 같은 콜이 여러 이벤트로 쪼개져도 같은 ID가 나오도록 10초 bucket 적용.
     * storeName이 없으면 "UNKNOWN"으로 처리.
     */
    fun generate(
        storeName: String?,
        price: Int,
        timestamp: Long = System.currentTimeMillis()
    ): String {
        val bucket = timestamp / TIME_BUCKET_MS
        val raw = "${storeName ?: "UNKNOWN"}|$price|$bucket"
        val md = MessageDigest.getInstance("SHA-1")
        val hash = md.digest(raw.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }
}
