package com.vita.ontheway

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 메모리상 drop 사유별 누적 카운트.
 * 프로세스 종료 시 리셋됨 (의도된 동작 — 진단용).
 */
object DropStats {
    private val counts = ConcurrentHashMap<DropReason, AtomicInteger>()

    fun increment(reason: DropReason) {
        counts.getOrPut(reason) { AtomicInteger(0) }.incrementAndGet()
    }

    fun get(reason: DropReason): Int =
        counts[reason]?.get() ?: 0

    fun getAll(): Map<DropReason, Int> =
        counts.mapValues { it.value.get() }

    fun reset() = counts.clear()
}
