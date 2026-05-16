package com.vita.ontheway

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * COUPANG-NOTIFICATION-FIRST + CRITICAL-FIX: 쿠팡 콜 identity key.
 *
 * primary:  coupang:${sbnKey}:${postTime}  confidence=0.9
 * fallback: coupang:${price}:${dist}:${bundle}:${bucket10s}:${rawHash}  confidence=0.5
 *
 * P1-2: 같은 baseKey(price+dist+bundle+bucket) + 다른 rawHash → collision → confidence 0.3
 */
object CoupangIdentityKey {

    enum class KeyType { PRIMARY, FALLBACK }

    data class IdentityKey(
        val key: String,
        val type: KeyType,
        val confidence: Double
    )

    // fallback collision 추적: baseKey → Set<rawHash>
    private val fallbackBuckets = ConcurrentHashMap<String, MutableSet<String>>()

    fun primary(sbnKey: String, postTime: Long): IdentityKey {
        return IdentityKey(
            key = "coupang:$sbnKey:$postTime",
            type = KeyType.PRIMARY,
            confidence = 0.9
        )
    }

    fun fallback(price: Int, distanceKm: Double?, bundleCount: Int, postTime: Long, rawText: String = ""): IdentityKey {
        val timeBucket = postTime / 10_000
        val dist = distanceKm?.let { "%.1f".format(it) } ?: "null"
        val rawHash = if (rawText.isNotBlank()) sha256Short(rawText) else "nohash"
        val baseKey = "coupang:$price:$dist:$bundleCount:$timeBucket"
        val fullKey = "$baseKey:$rawHash"

        // collision 감지 + sequenceInBucket
        val hashes = fallbackBuckets.getOrPut(baseKey) { mutableSetOf() }
        hashes.add(rawHash)
        val sequenceInBucket = hashes.size
        val isCollision = sequenceInBucket > 1
        val confidence = if (isCollision) 0.3 else 0.5

        cleanupBuckets()

        // P1-1: sequence는 key에서 제거, debug용으로만 보존
        return IdentityKey(
            key = fullKey,
            type = KeyType.FALLBACK,
            confidence = confidence
        )
    }

    fun resolve(
        sbnKey: String?,
        postTime: Long,
        price: Int,
        distanceKm: Double?,
        bundleCount: Int,
        rawText: String = ""
    ): IdentityKey {
        return if (!sbnKey.isNullOrBlank()) {
            primary(sbnKey, postTime)
        } else {
            fallback(price, distanceKm, bundleCount, postTime, rawText)
        }
    }

    private fun sha256Short(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray())
            hash.take(4).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            input.hashCode().toUInt().toString(16).take(8)
        }
    }

    private fun cleanupBuckets() {
        if (fallbackBuckets.size > 100) {
            val toRemove = fallbackBuckets.keys.take(50)
            toRemove.forEach { fallbackBuckets.remove(it) }
        }
    }

    fun resetForTest() {
        fallbackBuckets.clear()
    }
}
