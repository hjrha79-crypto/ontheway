package com.vita.ontheway

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 배민/쿠팡 알림 수신 시 즉시 파싱 → TTS.
 * 사용자가 다른 앱(OnTheWay 포함)을 보고 있어도 바로 작동.
 * 기존 AccessibilityService와 병행: 알림에서 금액 파싱 가능하면 판정, 불가능하면 무시.
 */
class DeliveryNotificationService : NotificationListenerService() {

    companion object {
        private const val PKG_COUPANG = "com.coupang.mobile.eats.courier"
        private const val PKG_BAEMIN = "com.woowahan.bros"
        private val TARGET_PACKAGES = setOf(PKG_COUPANG, PKG_BAEMIN)

        // 중복 알림 방지: 알림key → 처리시각
        private val processedNotifs = mutableMapOf<String, Long>()
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                ttsReady = true
                Log.d("DeliveryNoti", "TTS 초기화 완료")
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        ttsReady = false
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("DeliveryNoti", "알림 서비스 연결됨")
        Log.d("DeliveryNoti", "onListenerConnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg !in TARGET_PACKAGES) return

        // 중복 알림 체크 (같은 key 10초 이내 무시)
        val notiKey = "${sbn.key}_${sbn.id}"
        val now = System.currentTimeMillis()
        if (processedNotifs[notiKey]?.let { now - it < 10_000 } == true) return
        processedNotifs[notiKey] = now

        Log.d("DeliveryNoti", "알림: pkg=${sbn.packageName}, title=${sbn.notification?.extras?.getCharSequence("android.title")}, text=${sbn.notification?.extras?.getCharSequence("android.text")}")

        // 알림 텍스트 추출
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
        val combined = "$title $text $bigText".trim()

        Log.d("DeliveryNoti", "알림 수신: pkg=$pkg, title=$title, text=$text")

        if (combined.isBlank()) return

        // 플랫폼별 파싱
        val calls = when (pkg) {
            PKG_COUPANG -> parseCoupangNotification(combined)
            PKG_BAEMIN -> parseBaeminNotification(combined)
            else -> emptyList()
        }

        if (calls.isEmpty()) {
            Log.d("DeliveryNoti", "금액 파싱 불가 - Accessibility에 위임")
            return
        }

        // 판정 + TTS
        for (call in calls) {
            val result = CallFilter.judge(call, this)
            Log.d("DeliveryNoti", "파싱 결과: price=${call.price}, result=${result.verdict} (${result.reason})")
            FilterLog.record(this, call, result)

            // OnTheWayService의 lastCallDetectedTime도 갱신
            OnTheWayService.instance?.lastCallDetectedTime = now

            if (!TtsDeduplicator.shouldSpeak(call.platform, call.price)) {
                Log.d("DeliveryNoti", "TtsDeduplicator 중복 스킵: ${call.platform} ${call.price}원")
                continue
            }

            val unitPrice = if (call.distance != null && call.distance > 0)
                (call.price / call.distance).toInt() else 0
            val pName = if (call.platform == "coupang") "쿠팡" else "배민"
            val priceKr = toKoreanNumber(call.price)
            val unitKr = toKoreanNumber(unitPrice)

            if (result.verdict == CallFilter.Verdict.REJECT) {
                speakTts("$pName, 넘기세요, ${priceKr}원")
                Log.d("DeliveryNoti", "REJECT: ${call.price}원 - ${result.reason}")
            } else {
                val isTop = unitPrice >= 2500 && call.distance != null && call.distance <= 3.0
                if (isTop) {
                    speakTts("$pName, 잡으세요, 단가 $unitKr")
                    Log.d("DeliveryNoti", "ACCEPT(잡으세요): ${call.price}원")
                } else if (CallFilter.isOkVoiceEnabled(this)) {
                    var msg = "$pName, 괜찮습니다"
                    if (unitPrice > 0) msg += ", 단가 $unitKr"
                    speakTts(msg)
                    Log.d("DeliveryNoti", "ACCEPT(괜찮습니다): ${call.price}원")
                }
            }
        }

        // 오래된 처리 기록 정리
        processedNotifs.entries.removeIf { now - it.value > 60_000 }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) { /* no-op */ }

    // ── 쿠팡 알림 파싱 ──
    private fun parseCoupangNotification(text: String): List<DeliveryCall> {
        val priceMatch = Regex("([\\d,]+)\\s*원").find(text) ?: return emptyList()
        val price = priceMatch.groupValues[1].replace(",", "").toIntOrNull() ?: return emptyList()
        if (price !in 1000..100000) return emptyList()

        val distance = Regex("(\\d+\\.?\\d*)\\s*km", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val isMulti = text.contains("멀티") || text.contains("주문 두 건")

        return listOf(DeliveryCall(
            price = price, distance = distance, isMulti = isMulti,
            platform = "coupang", rawText = text
        ))
    }

    // ── 배민 알림 파싱 ──
    private fun parseBaeminNotification(text: String): List<DeliveryCall> {
        // "배달료 7,010원" 또는 "7,010원"
        val priceMatch = Regex("(?:배달료\\s*)?([\\d,]+)\\s*원").find(text) ?: return emptyList()
        val price = priceMatch.groupValues[1].replace(",", "").toIntOrNull() ?: return emptyList()
        if (price !in 500..100000) return emptyList()

        return listOf(DeliveryCall(
            price = price, distance = null, isMulti = false,
            platform = "baemin", rawText = text
        ))
    }

    private fun speakTts(text: String) {
        if (tts == null || !ttsReady) {
            Log.w("DeliveryNoti", "TTS 미준비 - 스킵됨")
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "noti_${System.currentTimeMillis()}")
    }

    private fun toKoreanNumber(n: Int): String {
        if (n <= 0) return "영"
        val sb = StringBuilder()
        val digits = arrayOf("", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
        if (n >= 10000) {
            val man = n / 10000
            if (man > 1) sb.append(digits[man])
            sb.append("만")
            val rest = n % 10000
            if (rest > 0) sb.append(toKoreanNumber(rest))
            return sb.toString()
        }
        val cheon = n / 1000
        val baek = (n % 1000) / 100
        val sip = (n % 100) / 10
        val il = n % 10
        if (cheon > 0) { if (cheon > 1) sb.append(digits[cheon]); sb.append("천") }
        if (baek > 0) { if (baek > 1) sb.append(digits[baek]); sb.append("백") }
        if (sip > 0) { if (sip > 1) sb.append(digits[sip]); sb.append("십") }
        if (il > 0 && n >= 10) sb.append(digits[il])
        else if (n < 10) sb.append(digits[n])
        return sb.toString()
    }
}
