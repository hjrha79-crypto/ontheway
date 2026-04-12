package com.vita.ontheway

import android.accessibilityservice.AccessibilityService
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class OnTheWayService : AccessibilityService() {

    companion object {
        var currentDir: String = ""
        var currentDest: String = ""
        var departureTime: String = ""
        var lastShadowTs: String = ""
        var instance: OnTheWayService? = null
        var resultCallback: ((String, String, Int, String) -> Unit)? = null

        // SearchSession 추적용
        var activeSearchSessionId: String? = null
        var lastRecommendedAmount: Int = 0
        var lastRecommendedUrgent: Boolean = false
        var lastCallData: CallData? = null
        var lastReason: String = ""   // 화면 표시용 이유

        // 감지 대상 패키지
        private const val PKG_FLEXER = "com.kakaomobility.flexer"
        private const val PKG_DRIVER = "com.kakao.taxi.driver"
        private const val PKG_COUPANG = "com.coupang.mobile.eats.courier"
        private const val PKG_BAEMIN = "com.woowahan.bros"
        val TARGET_PACKAGES = setOf(PKG_FLEXER, PKG_DRIVER, PKG_COUPANG, PKG_BAEMIN)
        val DELIVERY_PACKAGES = setOf(PKG_COUPANG, PKG_BAEMIN)

        // UI 텍스트 필터
        val IGNORE_TEXTS = setOf(
            "리스트 설정", "추천순", "신규",
            "내 오더", "퀵 배송", "도보배송",
            "한차배송", "20km", "10km", "30km",
            "서포트모드", "카드설정", "수요지도",
            "콜 대기중", "콜 대기", "대기중",
            "새로고침", "정렬옵션", "리스트 설정",
            "콜 수요 지도", "내 예약", "선호도착지"
        )

        val DRIVER_IGNORE_TEXTS = setOf(
            "배차 설정", "자동배차", "배차대기", "콜 설정",
            "공지사항", "알림", "내 정보", "설정",
            "콜 대기중", "단독배정권", "맞춤콜",
            "수요 지도", "나의 예약"
        )
    }

    // 1콜 1음성: callKey → 마지막 발화 시각
    private val callSpeakHistory = mutableMapOf<String, Long>()
    // callKey → 최초 감지 시각 (3초 안전창)
    private val callDetectedAt = mutableMapOf<String, Long>()
    private var lastSpeakTime: Long = 0

    // 배달 필터용 TTS
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        Log.d("OTW_DEBUG", "패키지: $pkg")
        if (pkg !in TARGET_PACKAGES) return
        val root = rootInActiveWindow ?: return

        // ── 배달 플랫폼 분기 (쿠팡이츠/배민커넥트) ──
        if (pkg in DELIVERY_PACKAGES) {
            handleDeliveryPlatform(root, pkg)
            return
        }

        val isDriverApp = (pkg == PKG_DRIVER)

        val session = SearchSessionStore.ensureActiveSession(this)
        activeSearchSessionId = session.sessionId

        val texts = mutableListOf<String>()
        extractText(root, texts)

        if (isDriverApp) {
            Log.d("KakaoDriver", "rawText: ${texts.joinToString(" | ")}")
        }

        // ── 금액 추출 ─────────────────────────────
        val amtRange = if (isDriverApp) 1000..100000 else 1000..50000
        val amountPattern = Regex("([\\d,]+)\\s*[원P]?")
        val ignoreSet = if (isDriverApp) DRIVER_IGNORE_TEXTS + IGNORE_TEXTS else IGNORE_TEXTS

        val amounts = texts.mapNotNull { text ->
            if (text.length > 30) return@mapNotNull null  // 긴 텍스트는 금액 아님
            if (ignoreSet.any { text.contains(it) }) return@mapNotNull null
            amountPattern.find(text)?.groupValues?.get(1)
                ?.replace(",", "")?.trim()?.toIntOrNull()
                ?.takeIf { it in amtRange }
        }.distinct()

        if (amounts.isEmpty()) {
            if (isDriverApp) Log.d("KakaoDriver", "금액 감지 실패")
            return
        }

        if (isDriverApp) Log.d("KakaoDriver", "대리 콜 감지: ${amounts.size}건 $amounts")

        activeSearchSessionId?.let { sid ->
            SearchSessionStore.incrementCallsReceived(this, sid, amounts.size)
            SearchSessionStore.updateFirstCall(this, sid, amounts.first())
        }

        // ── 픽업/배송 거리 파싱 (레이블 기반) ──────
        val pickupKmParsed = texts.mapNotNull { t ->
            Regex("픽업\\s*(\\d+\\.?\\d*)\\s*km", RegexOption.IGNORE_CASE)
                .find(t)?.groupValues?.get(1)?.toDoubleOrNull()
        }.firstOrNull()

        val deliveryKmParsed = texts.mapNotNull { t ->
            Regex("배송\\s*(\\d+\\.?\\d*)\\s*km", RegexOption.IGNORE_CASE)
                .find(t)?.groupValues?.get(1)?.toDoubleOrNull()
        }.firstOrNull()

        // fallback: "4.1km" 같이 레이블 없는 거리
        val fallbackDistances = texts.mapNotNull { t ->
            Regex("^(\\d+\\.\\d+)\\s*km$").find(t.trim())
                ?.groupValues?.get(1)?.toDoubleOrNull()
        }

        // ── 예약 시간 파싱 ──────────────────────
        val hasReservation = texts.any { it.contains("예약") }
        val reservationTime = texts.firstOrNull { t ->
            t.matches(Regex(".*오늘\\s*\\d{1,2}:\\d{2}.*")) ||
            t.matches(Regex(".*내일\\s*\\d{1,2}:\\d{2}.*")) ||
            t.matches(Regex(".*\\d{1,2}/\\d{1,2}\\([^)]*\\)\\s*\\d{1,2}:\\d{2}.*"))
        }?.trim()

        val deliveryDeadline = texts.firstOrNull { t ->
            t.contains("마감") && t.matches(Regex(".*\\d{1,2}:\\d{2}.*"))
        }?.trim()

        // ── 콜 타입 파싱 ───────────────────────
        val callType = if (isDriverApp) "대리" else when {
            texts.any { it.contains("도보") } -> "도보"
            texts.any { it.contains("한차") } -> "한차"
            texts.any { it.contains("대리") } -> "대리"
            else -> "퀵"
        }

        // ── 주소 파싱 ──────────────────────────
        // 대리앱: 출발/도착 마커 기반
        val daeriPickup = if (callType == "대리") {
            texts.firstOrNull { t ->
                (t.contains("출발") || t.contains("탑승") || t.contains("승차")) &&
                !ignoreSet.any { t.contains(it) }
            }?.replace(Regex("(출발|탑승|승차)[:\\s]*"), "")?.trim()
        } else null

        val daeriDest = if (callType == "대리") {
            texts.firstOrNull { t ->
                (t.contains("도착") || t.contains("하차")) &&
                !ignoreSet.any { t.contains(it) }
            }?.replace(Regex("(도착|하차)[:\\s]*"), "")?.trim()
        } else null

        // 퀵/일반: 주소처럼 생긴 텍스트 (구, 동, 시 포함)
        val addressTexts = texts.filter { t ->
            t.length in 3..30 &&
            ignoreSet.none { t.contains(it) } &&
            !t.contains("km") && !t.contains("원") && !t.contains("분") &&
            (t.contains("구") || t.contains("동") || t.contains("시") || t.contains("면") || t.contains("로"))
        }

        // ── 기타 파싱 ──────────────────────────
        val itemSize = when {
            texts.any { it.contains("반나절") } -> "반나절"
            texts.any { it.contains("초소형") } -> "초소형"
            texts.any { it.contains("대형") }   -> "대형"
            texts.any { it.contains("중형") }   -> "중형"
            texts.any { it.contains("소형") }   -> "소형"
            else -> "소형"
        }

        val vehicleType = when {
            texts.any { it.contains("오토바이") || it.contains("바이크") } -> "오토바이"
            texts.any { it.contains("승용차") || it.contains("승용") }    -> "승용차"
            else -> null
        }

        val notice = texts.firstOrNull { t ->
            t.contains("유의") || t.contains("주의") || t.contains("참고")
        }?.trim()

        // ── CallData 생성 ───────────────────────
        val calls = amounts.mapIndexed { i, amt ->
            val km = pickupKmParsed
                ?: fallbackDistances.getOrNull(0)
                ?: 0.0
            val deliveryKm = deliveryKmParsed
                ?: fallbackDistances.getOrNull(1)
                ?: 0.0

            val from = when {
                callType == "대리" && daeriPickup != null -> daeriPickup
                addressTexts.size > i * 2     -> addressTexts.getOrNull(i * 2) ?: ""
                else                           -> ""
            }
            val to = when {
                callType == "대리" && daeriDest != null -> daeriDest
                addressTexts.size > i * 2 + 1 -> addressTexts.getOrNull(i * 2 + 1) ?: ""
                else                           -> ""
            }

            CallData(
                from             = from,
                to               = to,
                amount           = amt,
                pickupKm         = km,
                deliveryKm       = deliveryKm,
                callType         = callType,
                isReservation    = hasReservation,
                reservationTime  = reservationTime,
                deliveryDeadline = deliveryDeadline,
                itemSize         = itemSize,
                vehicleType      = vehicleType,
                notice           = notice
            )
        }

        if (isDriverApp) Log.d("KakaoDriver", "파싱 완료: ${calls.map { "${it.from}→${it.to} ${it.amount}원 픽업${it.pickupKm}km" }}")

        // ── CallFilter 판정 (카카오T에도 적용) ────
        val now = System.currentTimeMillis()
        for (callData in calls) {
            val totalDist = callData.pickupKm + callData.deliveryKm
            val deliveryCall = DeliveryCall(
                price = callData.amount,
                distance = if (totalDist > 0) totalDist else null,
                isMulti = false,
                platform = "kakaot"
            )
            val filterResult = CallFilter.judge(deliveryCall, this)
            FilterLog.record(this, deliveryCall, filterResult)

            if (filterResult.verdict == CallFilter.Verdict.REJECT) {
                val fKey = "kakaot_${callData.amount}_${callData.pickupKm}"
                if (!callSpeakHistory.containsKey(fKey) && now - lastSpeakTime >= 3000) {
                    callSpeakHistory[fKey] = now
                    lastSpeakTime = now
                    speakTts("넘겨라")
                    Log.d("OnTheWay", "CallFilter REJECT: ${callData.amount}원 - ${filterResult.reason}")
                }
            }
        }

        // ── 추천 ────────────────────────────────
        val direction = if (currentDest.isNotEmpty()) currentDest else currentDir
        val result = CallRecommender.recommend(calls, direction) ?: return

        // ── 안전 조건 1: 파싱 실패 시 음성 금지 ──
        if (result.call.amount <= 0) {
            Log.d("OnTheWay", "파싱 실패 - 음성 출력 건너뜀")
            return
        }

        val callKey = "${result.call.amount}_${result.call.callType}_${result.call.pickupKm}"

        // ── 안전 조건 2: 1콜 1음성 ──────────────
        if (callSpeakHistory.containsKey(callKey)) {
            Log.d("OnTheWay", "중복 콜 - 음성 건너뜀: $callKey")
            return
        }

        // ── 안전 조건 3: 3초 안전창 ─────────────
        val detectedAt = callDetectedAt.getOrPut(callKey) { now }
        if (now - detectedAt > 3000) {
            Log.d("OnTheWay", "3초 초과 - 음성 건너뜀: $callKey")
            callDetectedAt.remove(callKey)
            return
        }

        // ── 안전 조건 4: 최소 발화 간격 5초 ──────
        if (now - lastSpeakTime < 5000) {
            Log.d("OnTheWay", "발화 간격 미달 - 건너뜀")
            return
        }

        // ── 발화 기록 ────────────────────────────
        callSpeakHistory[callKey] = now
        lastSpeakTime = now
        lastRecommendedAmount = result.call.amount
        lastRecommendedUrgent = texts.any { it.contains("급송") || it.contains("긴급") }
        lastCallData = result.call
        lastReason = result.reason

        // Shadow Mode 로깅
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        lastShadowTs = ts
        val availCalls = calls.mapIndexed { i, c ->
            AvailableCall(id = "call_$i", price = c.amount, direction = "${c.from}_${c.to}", distanceKm = c.pickupKm)
        }
        val bestIdx = calls.indexOf(result.call)
        val recommended = listOf(RecommendedEntry(rank = 1, callId = "call_$bestIdx"))
        ShadowLog.record(this, direction, availCalls, recommended, "")

        // MobilityEvent INSERT (추천 시점)
        val recommendEvent = MobilityEventBuilder.buildRecommendation(
            sessionId = activeSearchSessionId ?: "",
            callId    = callKey,
            call      = result.call,
            score     = result.totalScore,
            reason    = result.reason,
            direction = direction
        )
        Log.d("MobilityEvent", "INSERT(추천): ${recommendEvent.summary}")

        val logTag = if (isDriverApp) "KakaoDriver" else "OnTheWay"
        Log.d(logTag, "추천: ${result.call.from}→${result.call.to} ${result.call.amount}원 | ${result.voice}")

        // SKIP 판정 시 카드 표시 안 함 (음성만 출력)
        if (result.reasonDetail == "SKIP") {
            Log.d(logTag, "SKIP 판정 - 카드 미표시: ${result.voice}")
            return
        }

        // voice 전달 (reason 파라미터로 voice 전달)
        resultCallback?.invoke(result.call.from, result.call.to, result.call.amount, result.voice)

        // 오래된 히스토리 정리 (메모리 누수 방지)
        val expireTime = now - 60000 // 1분 이상 된 것 제거
        callSpeakHistory.entries.removeIf { it.value < expireTime }
        callDetectedAt.entries.removeIf { it.value < expireTime }
    }

    fun acceptCurrentCall() {
        activeSearchSessionId?.let { sid ->
            SearchSessionStore.updateAccepted(
                context   = this,
                sessionId = sid,
                callId    = lastRecommendedAmount.toString(),
                price     = lastRecommendedAmount,
                isUrgent  = lastRecommendedUrgent
            )
        }

        val root = rootInActiveWindow ?: return
        val acceptNode = findNodeByText(root, "수락하기")
        if (acceptNode != null) {
            acceptNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d("OnTheWay", "수락하기 자동 클릭 성공")
        }
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(text) == true) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findNodeByText(it, text) }
            if (found != null) return found
        }
        return null
    }

    private fun extractText(node: AccessibilityNodeInfo, results: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { results.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { extractText(it, results) }
        }
    }

    // ── 배달 플랫폼 처리 (쿠팡이츠/배민커넥트) ─────────
    private fun handleDeliveryPlatform(root: AccessibilityNodeInfo, pkg: String) {
        val texts = mutableListOf<String>()
        extractText(root, texts)

        val platformName = if (pkg == PKG_COUPANG) "coupang" else "baemin"
        Log.d("DeliveryFilter", "[$platformName] rawText: ${texts.joinToString(" | ")}")
        Log.d("OTW_DEBUG", "배달앱 감지: $pkg, 텍스트: ${texts.joinToString(" | ")}")

        // 파싱
        val calls = when (pkg) {
            PKG_COUPANG -> CoupangParser.parse(texts)
            PKG_BAEMIN  -> BaeminParser.parse(texts)
            else        -> return
        }
        Log.d("OTW_DEBUG", "파서 호출: 결과=$calls")

        if (calls.isEmpty()) {
            Log.d("DeliveryFilter", "[$platformName] 파싱 실패 - 무음")
            return
        }

        val now = System.currentTimeMillis()

        for (call in calls) {
            val result = CallFilter.judge(call, this)
            val callKey = "${call.platform}_${call.price}_${call.distance ?: 0}"

            // 안전 조건: 1콜 1음성
            if (callSpeakHistory.containsKey(callKey)) {
                Log.d("DeliveryFilter", "중복 콜 건너뜀: $callKey")
                continue
            }

            // 안전 조건: 3초 쿨다운
            if (now - lastSpeakTime < 3000) {
                Log.d("DeliveryFilter", "쿨다운 중 - 건너뜀")
                continue
            }

            // 로그 기록
            FilterLog.record(this, call, result)

            // REJECT → TTS "넘겨라", ACCEPT → 무음
            if (result.verdict == CallFilter.Verdict.REJECT) {
                callSpeakHistory[callKey] = now
                lastSpeakTime = now
                speakTts("넘겨라")
                Log.d("DeliveryFilter", "REJECT: ${call.price}원 - ${result.reason}")
            } else {
                callSpeakHistory[callKey] = now
                Log.d("DeliveryFilter", "ACCEPT: ${call.price}원 - 무음")
            }
        }

        // 오래된 히스토리 정리
        val expireTime = now - 60000
        callSpeakHistory.entries.removeIf { it.value < expireTime }
        callDetectedAt.entries.removeIf { it.value < expireTime }
    }

    private fun speakTts(text: String) {
        if (tts == null || !ttsReady) {
            Log.w("DeliveryFilter", "TTS 미준비 - 음성 출력 건너뜀")
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "filter_${System.currentTimeMillis()}")
    }

    override fun onInterrupt() { instance = null }
    override fun onServiceConnected() {
        instance = this
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                ttsReady = true
                Log.d("OnTheWay", "TTS 초기화 완료")
            }
        }
        Log.d("OnTheWay", "OnTheWay 서비스 시작")
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        ttsReady = false
        super.onDestroy()
    }
}
