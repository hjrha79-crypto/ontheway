package com.vita.ontheway

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * FIX-TTS-DELIVERY-FLOW: 배달 흐름 상태 관리 + TTS 트리거.
 *
 * 5 시나리오:
 * A: 100m crossing → "103동 1302호"
 * B: 도착 (DELIVERY_NEAR 200m) → "103동 1302호, 공동현관 1234, 문앞에..."
 * C: 60초 미완료 → "문앞에 두고 문자 주세요"
 * D: 배달 완료 → "다음 207동 1801호"
 * E: 멀티 픽업 완료 → "105동 502호 다음 207동 1801호"
 */
object DeliveryFlowManager {

    private const val TAG = "DeliveryFlow"
    private const val REMINDER_DELAY_MS = 60_000L // 60초

    // 현재 배달 큐
    private var currentDelivery: DeliveryAddress? = null
    private var nextDelivery: DeliveryAddress? = null
    private var multiDeliveries: List<DeliveryAddress> = emptyList()

    // 상태
    private var deliveryNearFired = false
    private var reminderScheduled = false
    private val handler = Handler(Looper.getMainLooper())
    private var reminderRunnable: Runnable? = null

    // TTS 콜백 (OnTheWayService에서 설정)
    var speakCallback: ((String) -> Unit)? = null

    /** Fix M1.wire-fix v2: sha256(text+"otw")[:8] = 16 hex */
    internal fun hashText(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((text + "otw").toByteArray()).take(8)
            .joinToString("") { "%02x".format(it) }

    /** 수락 시 배달 큐 설정 */
    fun onAccepted(call: DeliveryCall, customerRequest: String?) {
        clearState()

        val dest = call.destination
        currentDelivery = DeliveryAddress.parse(
            address = dest,
            customerRequest = customerRequest ?: "",
            storeName = call.storeName
        )

        // 멀티콜: "+" 구분 가게명에서 복수 배달지 설정
        if (call.isMulti && call.bundleCount >= 2) {
            // 멀티콜의 개별 배달지는 접근성 트리에서 별도 파싱 필요
            // 현재는 단일 destination만 사용, 추후 확장
            nextDelivery = null
        }

        OtwFileLogger.log(TAG, "큐 설정: current='${currentDelivery?.shortAddress()}', store='${call.storeName}'")
    }

    /** 시나리오 B: 배달지 근접 (DELIVERY_NEAR, 200m) */
    fun onDeliveryNear() {
        if (deliveryNearFired) return
        deliveryNearFired = true

        val delivery = currentDelivery ?: return
        val msg = DeliveryTtsBuilder.buildArrival(delivery)
        if (msg != null) {
            speak(msg)
            val reqPart = if (delivery.customerRequest.isNotBlank())
                "req_hash=${hashText(delivery.customerRequest)} len=${delivery.customerRequest.length}"
            else "req=none"
            OtwFileLogger.log(TAG, "시나리오 B (도착): addr=${delivery.shortAddress()} $reqPart")
        }

        // 시나리오 C: 60초 리마인더 예약
        scheduleReminder(delivery)
    }

    /** 시나리오 C: 60초 미완료 리마인더 */
    private fun scheduleReminder(delivery: DeliveryAddress) {
        if (reminderScheduled) return
        val reminderMsg = DeliveryTtsBuilder.buildReminder(delivery) ?: return

        reminderScheduled = true
        reminderRunnable = Runnable {
            // 아직 완료 안됐으면 리마인드
            if (currentDelivery != null && deliveryNearFired) {
                speak(reminderMsg)
                OtwFileLogger.log(TAG, "시나리오 C (리마인더): hash=${hashText(reminderMsg)} len=${reminderMsg.length}")
            }
        }
        handler.postDelayed(reminderRunnable!!, REMINDER_DELAY_MS)
    }

    /** 시나리오 D: 배달 완료 → 다음 배달지 안내 */
    fun onDeliveryComplete() {
        cancelReminder()

        val nextMsg = DeliveryTtsBuilder.buildNextDelivery(nextDelivery)
        if (nextMsg != null) {
            speak(nextMsg)
            OtwFileLogger.log(TAG, "시나리오 D (다음): \"$nextMsg\"")
        }

        // 큐 전진
        currentDelivery = nextDelivery
        nextDelivery = null
        deliveryNearFired = false
    }

    /** 시나리오 E: 멀티 픽업 완료 → 배달 순서 안내 */
    fun onMultiPickupComplete() {
        if (multiDeliveries.isEmpty()) return
        val msg = DeliveryTtsBuilder.buildMultiPickupNotice(multiDeliveries)
        if (msg != null) {
            speak(msg)
            OtwFileLogger.log(TAG, "시나리오 E (멀티픽업): \"$msg\"")
        }
    }

    /** 멀티 배달지 목록 설정 (멀티콜 픽업 시) */
    fun setMultiDeliveries(deliveries: List<DeliveryAddress>) {
        multiDeliveries = deliveries
        if (deliveries.size >= 2) {
            currentDelivery = deliveries[0]
            nextDelivery = deliveries[1]
        } else if (deliveries.size == 1) {
            currentDelivery = deliveries[0]
        }
    }

    fun getCurrentDelivery(): DeliveryAddress? = currentDelivery

    private fun speak(msg: String) {
        speakCallback?.invoke(msg)
            ?: Log.w(TAG, "speakCallback null, 메시지 발화 불가: \"$msg\"")
    }

    private fun cancelReminder() {
        reminderRunnable?.let { handler.removeCallbacks(it) }
        reminderRunnable = null
        reminderScheduled = false
    }

    fun clearState() {
        cancelReminder()
        currentDelivery = null
        nextDelivery = null
        multiDeliveries = emptyList()
        deliveryNearFired = false
    }

    /** 테스트용: 상태 조회 */
    fun isDeliveryNearFired(): Boolean = deliveryNearFired
}
