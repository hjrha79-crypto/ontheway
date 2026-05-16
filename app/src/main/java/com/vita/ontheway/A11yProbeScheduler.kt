package com.vita.ontheway

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * F1.h3: NLS 트리거 → Accessibility probe 스케줄러.
 *
 * 배민 NLS "신규배달" 수신 시 가격이 없어도 PENDING_DETECTION 생성 후,
 * rootInActiveWindow를 짧은 간격(0/200/500/1000ms)으로 probe하여
 * Accessibility 파싱을 강제 재시도.
 *
 * 첫 probe 성공 시 나머지 취소 + CALL_DETECTED 승격.
 * 1000ms 후 모두 실패 시 PENDING_TIMEOUT.
 *
 * v2: 중첩 schedule 시 이전 pending PENDING_SUPERSEDED 발화.
 */
object A11yProbeScheduler {

    private const val TAG = "A11yProbe"
    val PROBE_DELAYS = longArrayOf(0, 200, 500, 1000)

    private val handler = Handler(Looper.getMainLooper())

    // 현재 활성 probe sequence ID (취소 판정용)
    @Volatile
    private var activeSequenceId: Long = 0

    // 남은 probe 수 (timeout 판정용)
    @Volatile
    private var remainingProbes: Int = 0

    // 콜백: (sequenceId) -> Boolean (true=파싱 성공)
    private var probeCallback: ((Long) -> Boolean)? = null

    // 타임아웃 콜백: (sequenceId) -> Unit
    private var timeoutCallback: ((Long) -> Unit)? = null

    // superseded 콜백: (oldSequenceId) -> Unit
    private var supersededCallback: ((Long) -> Unit)? = null

    /** 이전 시퀀스가 superseded 되었는지 (테스트용) */
    @Volatile
    internal var lastSupersededSeqId: Long = 0
        private set

    /**
     * probe 스케줄 시작.
     * @param onProbe rootInActiveWindow 읽기 + 배민 파싱 시도. 성공 시 true.
     * @param onTimeout 모든 probe 실패 시 호출.
     * @param onSuperseded 이전 pending이 새 NLS로 종결될 때 호출.
     * @return sequenceId
     */
    fun schedule(
        onProbe: (sequenceId: Long) -> Boolean,
        onTimeout: (sequenceId: Long) -> Unit,
        onSuperseded: ((oldSequenceId: Long) -> Unit)? = null
    ): Long {
        // F1.h3 v2: 이전 시퀀스 활성 → PENDING_SUPERSEDED 발화 후 취소
        val oldSeqId = activeSequenceId
        if (oldSeqId > 0) {
            Log.d(TAG, "superseding previous seqId=$oldSeqId")
            OtwFileLogger.log(TAG, "PENDING_SUPERSEDED: oldSeqId=$oldSeqId")
            lastSupersededSeqId = oldSeqId
            try { onSuperseded?.invoke(oldSeqId) } catch (_: Exception) {}
        }

        // 이전 시퀀스 취소
        cancel()

        val seqId = System.currentTimeMillis()
        activeSequenceId = seqId
        remainingProbes = PROBE_DELAYS.size
        probeCallback = onProbe
        timeoutCallback = onTimeout
        supersededCallback = onSuperseded

        Log.d(TAG, "schedule: seqId=$seqId, probes=${PROBE_DELAYS.size}")
        OtwFileLogger.log(TAG, "schedule: seqId=$seqId")

        for (delay in PROBE_DELAYS) {
            handler.postDelayed({
                executeProbe(seqId)
            }, delay)
        }

        return seqId
    }

    private fun executeProbe(seqId: Long) {
        // 이미 취소된 시퀀스
        if (seqId != activeSequenceId) return

        val cb = probeCallback ?: return
        val success = try {
            cb(seqId)
        } catch (e: Exception) {
            Log.w(TAG, "probe error: ${e.message}")
            false
        }

        if (success) {
            Log.d(TAG, "probe SUCCESS: seqId=$seqId")
            OtwFileLogger.log(TAG, "probe SUCCESS: seqId=$seqId")
            // 나머지 probe 취소
            cancel()
            return
        }

        // 실패: remaining 감소
        remainingProbes--
        if (remainingProbes <= 0) {
            Log.d(TAG, "all probes FAILED: seqId=$seqId")
            OtwFileLogger.log(TAG, "all probes FAILED: seqId=$seqId → PENDING_TIMEOUT")
            val tcb = timeoutCallback
            cancel()
            tcb?.invoke(seqId)
        }
    }

    fun cancel() {
        activeSequenceId = 0
        remainingProbes = 0
        probeCallback = null
        timeoutCallback = null
        supersededCallback = null
        handler.removeCallbacksAndMessages(null)
    }

    /** 서비스 종료 시 안전 취소 (onDestroy 용) */
    fun cancelAll() {
        cancel()
        lastSupersededSeqId = 0
    }

    /** 테스트용 리셋 */
    fun resetForTest() {
        cancel()
        lastSupersededSeqId = 0
    }

    /** 현재 활성 시퀀스 ID (테스트용) */
    fun getActiveSequenceId(): Long = activeSequenceId
}
