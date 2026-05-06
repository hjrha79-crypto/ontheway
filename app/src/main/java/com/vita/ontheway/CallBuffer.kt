package com.vita.ontheway

/**
 * 멀티 플랫폼 비교용 콜 버퍼 (10초 메모리).
 * raw 데이터는 버퍼 만료 시 자동 폐기.
 */
object CallBuffer {

    private const val WINDOW_MS = 10_000L

    data class BufferedCall(
        val platform: String,
        val price: Int,
        val distanceKm: Double?,
        val pickupKm: Double?,
        val ts: Long = System.currentTimeMillis()
    ) {
        fun unitPrice(): Int {
            val d = distanceKm ?: return -1
            if (d <= 0) return -1
            val totalKm = (pickupKm ?: 0.0) + d
            return if (totalKm > 0) (price / totalKm).toInt() else -1
        }
    }

    private val buffer = mutableListOf<BufferedCall>()

    fun add(call: BufferedCall) {
        cleanup()
        buffer.add(call)
    }

    /** 다른 플랫폼에서 10초 이내 들어온 콜 찾기 */
    fun findMatch(call: BufferedCall): BufferedCall? {
        cleanup()
        return buffer.firstOrNull {
            it.platform != call.platform &&
            kotlin.math.abs(call.ts - it.ts) <= WINDOW_MS
        }
    }

    fun clear() = buffer.clear()
    fun size() = buffer.size

    private fun cleanup() {
        val now = System.currentTimeMillis()
        buffer.removeAll { now - it.ts > WINDOW_MS }
    }
}
