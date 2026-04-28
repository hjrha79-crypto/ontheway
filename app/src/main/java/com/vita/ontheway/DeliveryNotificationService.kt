package com.vita.ontheway

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * 배민/쿠팡 알림 수신 시 즉시 파싱 → TTS.
 * 사용자가 다른 앱(OnTheWay 포함)을 보고 있어도 바로 작동.
 * 기존 AccessibilityService와 병행: 알림에서 금액 파싱 가능하면 판정, 불가능하면 무시.
 */
class DeliveryNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG_LIFECYCLE = "DeliveryNLS_LIFECYCLE"
        private const val PKG_COUPANG = "com.coupang.mobile.eats.courier"
        private const val PKG_BAEMIN = "com.woowahan.bros"
        private const val PKG_FLEXER = "com.kakaomobility.flexer"
        private const val PKG_KAKAO_DRIVER = "com.kakao.taxi.driver"
        private val TARGET_PACKAGES = setOf(PKG_COUPANG, PKG_BAEMIN, PKG_FLEXER, PKG_KAKAO_DRIVER)

        // 중복 알림 방지: 알림key → 처리시각
        private val processedNotifs = mutableMapOf<String, Long>()

        // NLS rebind 카운터 (메모리상, DevStats 표시용)
        val rebindRequestCount = AtomicInteger(0)
        val lastRebindRequestTime = AtomicLong(0)
        val lastConnectedTime = AtomicLong(0)
    }

    // [Hotfix-2 P0-3] 메인 스레드 I/O 분리용 executor
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "DeliveryNLS-IO").apply { isDaemon = true }
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var sessionManager: SessionManager? = null
    private var listenerConnectedAt: Long = 0

    override fun onCreate() {
        super.onCreate()
        try { OtwFileLogger.init(this) } catch (_: Exception) {}
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                ttsReady = true
                Log.d("DeliveryNoti", "TTS 초기화 완료")
            }
        }
        try {
            sessionManager = SessionManager(StateTransitionLog(this))
        } catch (e: Exception) {
            Log.w("DeliveryNoti", "SessionManager 초기화 실패: ${e.message}")
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        ttsReady = false
        try { ioExecutor.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val now = System.currentTimeMillis()
        listenerConnectedAt = now
        lastConnectedTime.set(now)
        val rebindCount = rebindRequestCount.get()
        Log.d(TAG_LIFECYCLE, "NLS connected (rebindCount=$rebindCount)")
        OtwFileLogger.log(TAG_LIFECYCLE, "NLS connected (rebindCount=$rebindCount)")
        Log.d("DeliveryNoti", "알림 서비스 연결됨")
        Log.d("COUPANG_DBG", "onListenerConnected at $listenerConnectedAt")
        Log.d("COUPANG_DBG", "TARGET_PACKAGES=$TARGET_PACKAGES")

        // 가설 F 대응: 재바인딩 시 활성 알림 복구
        try {
            val active = activeNotifications
            Log.d("COUPANG_DBG", "getActiveNotifications returned ${active?.size ?: 0}")
            active?.forEach { sbn ->
                if (sbn.packageName == PKG_COUPANG) {
                    Log.d("COUPANG_DBG", "Recovered coupang notification: ${sbn.id}")
                    onNotificationPosted(sbn)
                }
            }
        } catch (e: Exception) {
            Log.e("COUPANG_DBG", "getActiveNotifications failed", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        val now = System.currentTimeMillis()
        Log.w(TAG_LIFECYCLE, "NLS disconnected, requesting rebind")
        OtwFileLogger.log(TAG_LIFECYCLE, "NLS disconnected, requesting rebind")
        Log.w("COUPANG_DBG", "onListenerDisconnected at $now")
        try {
            DropReason.recordDrop(DropReason.DROP_OTHER, "NLS_DISCONNECTED")
        } catch (_: Exception) {}
        try {
            requestRebind(ComponentName(this, DeliveryNotificationService::class.java))
            rebindRequestCount.incrementAndGet()
            lastRebindRequestTime.set(now)
            Log.d(TAG_LIFECYCLE, "requestRebind issued (total=${rebindRequestCount.get()})")
            OtwFileLogger.log(TAG_LIFECYCLE, "requestRebind issued (total=${rebindRequestCount.get()})")
        } catch (e: Exception) {
            Log.e(TAG_LIFECYCLE, "requestRebind failed: ${e.message}")
            OtwFileLogger.log(TAG_LIFECYCLE, "requestRebind failed: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val now = System.currentTimeMillis()
        Log.d("COUPANG_DBG", "onNotificationPosted: pkg=${sbn.packageName} " +
              "timeSinceConnect=${now - listenerConnectedAt}ms")
        val pkg = sbn.packageName ?: return
        if (pkg !in TARGET_PACKAGES) {
            DropReason.recordDrop(DropReason.DROP_PACKAGE_FILTER, "notification non-target", pkg)
            return
        }

        // 중복 알림 체크 (같은 key 10초 이내 무시)
        val notiKey = "${sbn.key}_${sbn.id}"
        if (processedNotifs[notiKey]?.let { now - it < 10_000 } == true) {
            DropReason.recordDrop(DropReason.DROP_DUPLICATE, "notification 10s dedup $notiKey", pkg)
            return
        }
        processedNotifs[notiKey] = now

        // 알림 텍스트 추출
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
        val combined = "$title $text $bigText".trim()

        Log.d("DeliveryNoti", "알림 수신: pkg=$pkg, title=$title, text=$text")

        if (combined.isBlank()) return

        // v2.2: 카카오T 알림 처리 (Accessibility 대안 경로)
        if (pkg == PKG_FLEXER || pkg == PKG_KAKAO_DRIVER) {
            Log.w("DeliveryNoti", "★ 카카오T 알림: pkg=$pkg, title=$title, text=$text, bigText=$bigText")
            val kakaoCalls = parseKakaoTNotification(combined)
            if (kakaoCalls.isNotEmpty()) {
                handleKakaoTCalls(kakaoCalls, now)
            }
            return
        }

        // v3.20: 쿠팡 알림 원문 로깅 (가게명 데이터 수집)
        if (pkg == PKG_COUPANG) {
            logCoupangNotifRaw(title, text, bigText)
        }

        // 플랫폼별 파싱 (배민 + 쿠팡 병행)
        val calls = when (pkg) {
            PKG_BAEMIN -> parseBaeminNotification(combined)
            PKG_COUPANG -> parseCoupangNotification(combined)
            else -> emptyList()
        }

        if (calls.isEmpty()) {
            Log.d("DeliveryNoti", "금액 파싱 불가 - Accessibility에 위임")
            DropReason.recordDrop(DropReason.DROP_PARSE_FAIL, "notification parse empty", pkg)
            return
        }

        // 판정 + TTS
        for (call in calls) {
            // 쿠팡: Accessibility가 10초 이내 처리했으면 NotificationListener는 스킵
            if (pkg == PKG_COUPANG && TtsDeduplicator.wasSpokenWithin("coupang", call.price, 10_000)) {
                Log.d("DeliveryNoti", "쿠팡 Accessibility 우선 - 알림 스킵: ${call.price}원")
                continue
            }
            // v3.18: SessionManager 경유
            val session = sessionManager?.onEventReceived(
                call.platform, call.storeName, call.price, "notification_posted"
            )
            val result = CallFilter.judge(call, this)
            Log.d("DeliveryNoti", "파싱 결과: price=${call.price}, result=${result.verdict} (${result.reason})")
            FilterLog.record(this, call, result, eventId = session?.eventId, sessionState = session?.state?.name)
            // 쿠팡은 알림 = 즉시 finalize
            if (call.platform == "coupang") {
                sessionManager?.finalizeActiveSession("notification_complete")
            }

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

            val ttsMode = TtsFormatMode.BASIC  // TODO: SettingsActivity에서 읽어오도록 변경 예정
            val verdict = if (result.verdict == CallFilter.Verdict.REJECT) "넘기세요" else {
                val isTop = unitPrice >= 2500 && call.distance != null && call.distance <= 3.0
                if (isTop) "잡으세요" else "괜찮습니다"
            }

            if (verdict == "넘기세요") {
                val msg = "$pName, 넘기세요, ${priceKr}원"
                speakTts(TtsMessageBuilder.build(ttsMode, call, result, msg))
                Log.d("DeliveryNoti", "REJECT: ${call.price}원 - ${result.reason}")
            } else if (verdict == "잡으세요") {
                val msg = "$pName, 잡으세요, 단가 $unitKr"
                speakTts(TtsMessageBuilder.build(ttsMode, call, result, msg))
                Log.d("DeliveryNoti", "ACCEPT(잡으세요): ${call.price}원")
            } else if (CallFilter.isOkVoiceEnabled(this)) {
                val msg = "$pName, 괜찮습니다" + if (unitPrice > 0) ", 단가 $unitKr" else ""
                speakTts(TtsMessageBuilder.build(ttsMode, call, result, msg))
                Log.d("DeliveryNoti", "ACCEPT(괜찮습니다): ${call.price}원")
            }

            // v3.19: Notification fallback — Accessibility 미처리 시 FloatingOverlay 표시
            val fmt = java.text.NumberFormat.getNumberInstance()
            val overlayText = "$verdict ${fmt.format(call.price)}원"
            FloatingOverlay.show(this, overlayText)

            // 2026-04-24: Accessibility 경로와 동일하게 OverlayManager 표시
            if (FeatureFlags.overlayEnabled) {
                try {
                    OverlayManager.show(
                        context = this,
                        verdict = verdict,
                        line1 = "$pName ${fmt.format(call.price)}원",
                        line2 = call.storeName.takeIf { it.isNotBlank() }
                    )
                } catch (e: Exception) {
                    Log.w("NotiFallback", "OverlayManager 실패: ${e.message}")
                }
            }

            Log.d("NotiFallback", "[$pName] overlay: $overlayText (noti path)")
        }

        // 오래된 처리 기록 정리
        processedNotifs.entries.removeIf { now - it.value > 60_000 }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) { /* no-op */ }

    // ── v2.2: 카카오T 알림 파싱 ──
    private fun parseKakaoTNotification(text: String): List<DeliveryCall> {
        // 금액 패턴: "39,400원", "12,000원" 등
        val priceMatch = Regex("([\\d,]+)\\s*원").find(text) ?: return emptyList()
        val price = priceMatch.groupValues[1].replace(",", "").toIntOrNull() ?: return emptyList()
        if (price !in 1000..100000) return emptyList()

        // 거리 파싱
        val distance = Regex("(\\d+\\.?\\d*)\\s*km", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toDoubleOrNull()

        // 주소 파싱 (간단)
        val from = Regex("(출발|픽업|탑승)[:\\s]*([가-힣]+)")
            .find(text)?.groupValues?.get(2) ?: ""
        val to = Regex("(도착|하차|목적지)[:\\s]*([가-힣]+)")
            .find(text)?.groupValues?.get(2) ?: ""

        Log.d("DeliveryNoti", "카카오T 파싱: ${price}원, ${distance}km, $from→$to")

        return listOf(DeliveryCall(
            price = price, distance = distance, isMulti = false,
            platform = "kakaot", rawText = text,
            storeName = from, destination = to,
            parsingMethod = V2Event.PARSING_NOTIFICATION
        ))
    }

    private fun handleKakaoTCalls(calls: List<DeliveryCall>, now: Long) {
        for (call in calls) {
            // Accessibility가 이미 처리했으면 스킵
            if (TtsDeduplicator.wasSpokenWithin("kakaot", call.price, 10_000)) {
                Log.d("DeliveryNoti", "카카오T Accessibility 우선 - 알림 스킵: ${call.price}원")
                continue
            }

            val result = CallFilter.judge(call, this)
            FilterLog.record(this, call, result)
            OnTheWayService.instance?.lastCallDetectedTime = now

            if (!TtsDeduplicator.shouldSpeak("kakaot", call.price)) continue

            val ttsMode = TtsFormatMode.BASIC  // TODO: SettingsActivity에서 읽어오도록 변경 예정
            val priceKr = toKoreanNumber(call.price)
            if (result.verdict == CallFilter.Verdict.REJECT) {
                val msg = "카카오, 넘기세요, ${priceKr}원"
                speakTts(TtsMessageBuilder.build(ttsMode, call, result, msg))
                Log.d("DeliveryNoti", "카카오T REJECT: ${call.price}원")
            } else {
                val msg = "카카오, 잡으세요, ${priceKr}원"
                speakTts(TtsMessageBuilder.build(ttsMode, call, result, msg))
                Log.d("DeliveryNoti", "카카오T ACCEPT: ${call.price}원")
            }
        }
        processedNotifs.entries.removeIf { now - it.value > 60_000 }
    }

    // ── 쿠팡 알림 파싱 ──
    private fun parseCoupangNotification(text: String): List<DeliveryCall> {
        // 프로모션/미션 알림 필터 (CoupangParser와 동일 키워드)
        if (CoupangParser.NON_CALL_KEYWORDS.any { text.contains(it) }) {
            Log.d("DeliveryNoti", "쿠팡 프로모션/미션 알림 스킵: ${text.take(50)}")
            return emptyList()
        }

        val priceMatch = Regex("([\\d,]+)\\s*원").find(text) ?: return emptyList()
        val price = priceMatch.groupValues[1].replace(",", "").toIntOrNull() ?: return emptyList()
        if (price !in 1000..100000) return emptyList()

        val distance = Regex("(\\d+\\.?\\d*)\\s*km", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val isMulti = text.contains("멀티") || text.contains("주문 두 건")

        return listOf(DeliveryCall(
            price = price, distance = distance, isMulti = isMulti,
            platform = "coupang", rawText = text,
            parsingMethod = V2Event.PARSING_NOTIFICATION
        ))
    }

    /** v3.20: 쿠팡 알림 원문 로깅 (가게명 추출 데이터 수집용) */
    private fun logCoupangNotifRaw(title: String, text: String, bigText: String) {
        // [Hotfix-2 P0-3] 메인 스레드에서 데이터만 캡처, 파일 I/O는 executor 위임
        val entryStr = try {
            JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("pkg", PKG_COUPANG)
                put("title", title)
                put("text", text)
                if (bigText.isNotBlank()) put("bigText", bigText)
            }.toString()
        } catch (e: Exception) {
            Log.w("DeliveryNoti", "쿠팡 알림 JSON 생성 실패: ${e.message}")
            return
        }
        val dir = filesDir
        ioExecutor.execute {
            try {
                val file = File(dir, "coupang_notif_raw.jsonl")
                file.appendText(entryStr + "\n")
                val lines = file.readLines()
                if (lines.size > 200) {
                    file.writeText(lines.takeLast(200).joinToString("\n") + "\n")
                }
            } catch (e: Exception) {
                Log.w("DeliveryNoti", "쿠팡 알림 로깅 실패: ${e.message}")
            }
        }
    }

    // ── 배민 알림 파싱 ──
    private fun parseBaeminNotification(text: String): List<DeliveryCall> {
        // "배달료 7,010원" 또는 "7,010원"
        val priceMatch = Regex("(?:배달료\\s*)?([\\d,]+)\\s*원").find(text) ?: return emptyList()
        val price = priceMatch.groupValues[1].replace(",", "").toIntOrNull() ?: return emptyList()
        if (price !in 500..100000) return emptyList()

        return listOf(DeliveryCall(
            price = price, distance = null, isMulti = false,
            platform = "baemin", rawText = text,
            parsingMethod = V2Event.PARSING_NOTIFICATION
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
