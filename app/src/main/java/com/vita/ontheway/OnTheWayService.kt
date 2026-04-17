package com.vita.ontheway

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
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

        // 수락 관련 상수
        val ACCEPT_BUTTON_TEXTS = listOf("배차수락", "배차 수락", "주문 수락", "주문수락", "수락하기", "모두 수락")
        val VOICE_ACCEPT_COMMANDS = setOf("잡아", "수락", "이거")
        const val ACCEPT_TIMEOUT_MS = 30_000L
        const val AUTO_ACCEPT_COOLDOWN_MS = 60_000L
        const val NOTIF_CHANNEL_ID = "otw_service"
        const val NOTIF_ID = 1001

        // v3.4: GPS 위치
        var currentLat: Double = 0.0
        var currentLng: Double = 0.0
        var currentSpeed: Float = 0f  // m/s
        var gpsActive: Boolean = false

        // v2.2: 진단 모드 — 패키지별 이벤트 카운트
        val packageEventCount = mutableMapOf<String, Int>()
    }

    // 1콜 1음성: callKey → 마지막 발화 시각
    private val callSpeakHistory = mutableMapOf<String, Long>()
    // callKey → 최초 감지 시각 (3초 안전창)
    private val callDetectedAt = mutableMapOf<String, Long>()
    private var lastSpeakTime: Long = 0
    var lastCallDetectedTime: Long = 0  // 상태 표시용

    // v3.0: 자동 수락 쿨다운
    private var lastAutoAcceptTime: Long = 0

    // v3.0: 마지막 판정 정보 (수락 감지용)
    private var lastDeliveryCall: DeliveryCall? = null
    private var lastDeliveryVerdict: String = ""  // "잡으세요", "괜찮습니다", "넘기세요"
    private var lastDeliveryPlatform: String = ""

    // v3.3: 연속 REJECT 카운터
    var consecutiveRejectCount: Int = 0
    // v3.3: 마지막 수락 시각 (배달 완료 소요시간)
    private var lastAcceptTime: Long = 0

    // 배민 묶음 debounce (2초 윈도우)
    private data class PendingCall(val call: DeliveryCall, val enrichedCall: DeliveryCall, val result: CallFilter.FilterResult, val baeminPoint: Double?, val pickupDistKm: Double?)
    private val baeminBuffer = mutableListOf<PendingCall>()
    private var baeminDebounceRunnable: Runnable? = null
    private val debounceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val BAEMIN_DEBOUNCE_MS = 2000L

    // v3.6: 배민 콜 대량 중복 감지 방지 (같은 플랫폼+금액 30초 이내 = 중복)
    private data class RecentCall(val platform: String, val price: Int, val time: Long)
    private val recentCalls = mutableListOf<RecentCall>()

    // 배달 필터용 TTS
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        // v2.2: 진단 모드 — 모든 패키지별 이벤트 카운트
        packageEventCount[pkg] = (packageEventCount[pkg] ?: 0) + 1

        // 쿠팡 진단 모드
        if (pkg == PKG_COUPANG && AdvancedPrefs.isCoupangDebugEnabled(this)) {
            val evTexts = event.text?.joinToString(" ") ?: ""
            val className = event.className?.toString() ?: ""
            val reason = "eventType=${event.eventType} pkg=$pkg text=[$evTexts] class=[$className]"
            Log.w("OTW_COUPANG_DEBUG", reason)
            FilterLog.record(this, DeliveryCall(
                price = 0, distance = null, isMulti = false, platform = "쿠팡진단",
                rawText = reason, parseSuccess = false
            ), CallFilter.FilterResult(CallFilter.Verdict.REJECT, reason))

            // getWindows() 패키지 로그 (10초에 1회)
            val now = System.currentTimeMillis()
            if (now - (callDetectedAt["coupang_win_log"] ?: 0) > 10000) {
                callDetectedAt["coupang_win_log"] = now
                try {
                    val winPkgs = windows?.mapNotNull { w -> w.root?.packageName?.toString() } ?: emptyList()
                    val winReason = "WINDOWS: $winPkgs"
                    Log.w("OTW_COUPANG_DEBUG", winReason)
                    FilterLog.record(this, DeliveryCall(
                        price = 0, distance = null, isMulti = false, platform = "쿠팡진단",
                        rawText = winReason, parseSuccess = false
                    ), CallFilter.FilterResult(CallFilter.Verdict.REJECT, winReason))
                } catch (e: Exception) {}
            }
        }

        // 카카오 관련 패키지는 별도 경고 로그
        if (pkg.contains("kakaomobility") || pkg.contains("flexer") || pkg.contains("kakao")) {
            Log.w("OTW_KAKAO", "★ 카카오: pkg=$pkg, type=${event.eventType}, count=${packageEventCount[pkg]}, source=${event.source != null}, root=${rootInActiveWindow != null}")
        }

        // v3.0: 수락 버튼 클릭 감지 (수익 트래킹)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedText = event.text?.joinToString("") ?: event.contentDescription?.toString() ?: ""
            if (ACCEPT_BUTTON_TEXTS.any { clickedText.contains(it) }) {
                onAcceptDetected()
            }
        }

        // v3.3: 배달 완료 감지
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val evTexts = event.text?.joinToString(" ") ?: ""
            if (evTexts.contains("배달 완료") || evTexts.contains("배달완료") || evTexts.contains("배달이 완료되었습니다")) {
                onDeliveryComplete()
            }
        }

        if (pkg !in TARGET_PACKAGES) return

        // root 확보: rootInActiveWindow 우선, 없으면 event.source, 카카오T는 getWindows() fallback
        var root = rootInActiveWindow ?: event.source
        if (root == null && (pkg == PKG_FLEXER || pkg == PKG_DRIVER)) {
            root = findWindowRoot(pkg)
        }
        if (root == null) return

        // ── 배달 플랫폼 분기 (쿠팡이츠/배민커넥트) ──
        if (pkg in DELIVERY_PACKAGES) {
            // root의 패키지가 이벤트 패키지와 다르면 getWindows()로 정확한 윈도우 탐색
            val rootPkg = root.packageName?.toString()
            if (rootPkg != pkg) {
                val correctRoot = findWindowRoot(pkg)
                if (correctRoot != null) {
                    Log.d("OTW_DEBUG", "getWindows로 정확한 윈도우 확보: $pkg (root was $rootPkg)")
                    handleDeliveryPlatform(correctRoot, pkg)
                } else {
                    Log.d("OTW_DEBUG", "getWindows에서도 $pkg 윈도우 없음 — 잘못된 root($rootPkg) 사용 방지, 스킵")
                    return
                }
            } else {
                handleDeliveryPlatform(root, pkg)
            }
            return
        }

        // 카카오T → CallRecommender 경로 (CallFilter 미사용)
        Log.d("OTW_ROUTE", "카카오T 경로: $pkg → CallRecommender (CallFilter 미사용)")
        val isDriverApp = (pkg == PKG_DRIVER)

        val session = SearchSessionStore.ensureActiveSession(this)
        activeSearchSessionId = session.sessionId

        val texts = mutableListOf<String>()
        extractText(root, texts)

        // 카카오T 감지 로그
        Log.d("OTW_ROUTE", "카카오T 텍스트: ${texts.joinToString(" ").take(50)}")

        if (isDriverApp) {
            Log.d("KakaoDriver", "rawText: ${texts.joinToString(" | ")}")
        }

        // ── 금액 추출 ─────────────────────────────
        val amtRange = if (isDriverApp) 1000..100000 else 1000..50000
        val amountPattern = Regex("([\\d,]+)\\s*[원P]")
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

        Log.d("OTW_ROUTE", "카카오T 금액: $amounts")
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

        // ── 추천 (카카오T는 CallRecommender로만 처리) ──
        val now = System.currentTimeMillis()
        lastCallDetectedTime = now

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
        // 타임아웃: 마지막 콜 감지 후 30초 이내만 수락 가능
        if (System.currentTimeMillis() - lastCallDetectedTime > ACCEPT_TIMEOUT_MS) {
            Log.d("OnTheWay", "수락 타임아웃 - 마지막 콜 감지 30초 초과")
            return
        }

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
        // 수락 버튼 텍스트 매칭 (contains)
        val acceptNode = ACCEPT_BUTTON_TEXTS.firstNotNullOfOrNull { findNodeByText(root, it) }
        if (acceptNode != null) {
            acceptNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            speakTts("수락합니다")
            Log.d("OnTheWay", "수락 자동 클릭 성공")
        } else {
            Log.d("OnTheWay", "수락 버튼을 찾을 수 없음")
        }
    }

    /** 음성 명령으로 수락 시도 (외부 호출용) */
    fun tryVoiceAccept(command: String): Boolean {
        if (VOICE_ACCEPT_COMMANDS.any { command.contains(it) }) {
            if (CallFilter.isVoiceAcceptEnabled(this)) {
                // 실제 수락
                acceptCurrentCall()
            } else {
                // 테스트 모드: TTS만, 실제 클릭 안 함
                speakTts("수락하시겠습니까?")
                Log.d("OnTheWay", "음성 수락 테스트 모드 - 실제 클릭 안 함")
            }
            return true
        }
        return false
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

    /** getWindows()를 순회하여 지정 패키지의 root를 찾는다 */
    private fun findWindowRoot(targetPkg: String): AccessibilityNodeInfo? {
        return try {
            windows?.firstNotNullOfOrNull { w ->
                w.root?.takeIf { it.packageName?.toString() == targetPkg }
            }
        } catch (e: Exception) {
            Log.w("OTW_DEBUG", "getWindows fallback 실패: ${e.message}")
            null
        }
    }

    /** v3.0: 수락 버튼 클릭 감지 시 처리 */
    private fun onAcceptDetected() {
        val call = lastDeliveryCall ?: return
        val price = call.price
        val platform = lastDeliveryPlatform.ifEmpty { call.platform }

        Log.d("OnTheWay", "수락 감지: ${price}원 ($platform)")

        // v3.3: 수락 시각 기록 (배달 완료 소요시간 계산용)
        lastAcceptTime = System.currentTimeMillis()

        // 수익 트래킹
        EarningsTracker.recordAccept(this, price, platform)

        // 네비 자동실행 (3초 딜레이)
        val pickupAddr = call.storeName.ifEmpty { call.destination }
        if (pickupAddr.isNotEmpty()) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                NaviLauncher.autoLaunchForAccept(this, pickupAddr)
            }, 3000)
        }
    }

    /** v3.0: 자동 수락 (잡으세요 판정만) */
    private fun tryAutoAccept() {
        if (!AdvancedPrefs.isAutoAcceptEnabled(this)) return
        if (lastDeliveryVerdict != "잡으세요") return

        val now = System.currentTimeMillis()
        if (now - lastAutoAcceptTime < AUTO_ACCEPT_COOLDOWN_MS) {
            Log.d("OnTheWay", "자동수락 쿨다운 중 (${(AUTO_ACCEPT_COOLDOWN_MS - (now - lastAutoAcceptTime)) / 1000}초 남음)")
            return
        }

        lastAutoAcceptTime = now
        speakTts("자동 수락합니다")

        // 1초 후 수락 버튼 클릭
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            acceptCurrentCall()
        }, 1000)
    }

    // ── 배달 플랫폼 처리 (쿠팡이츠/배민커넥트) ─────────
    private fun handleDeliveryPlatform(root: AccessibilityNodeInfo, pkg: String) {
        val texts = mutableListOf<String>()
        extractText(root, texts)

        val platformName = if (pkg == PKG_COUPANG) "coupang" else "baemin"

        // 대기 화면 무시 필터 — 파싱/로그/TTS 전부 스킵 (최우선 체크)
        val joined = texts.joinToString(" ")
        if (pkg == PKG_BAEMIN) {
            val baeminSkipKeywords = listOf(
                // 기존
                "가상 배달을 체험해 보세요", "신규배차를 켜고 배달을 시작하세요",
                "배달을 시작해", "배차 대기",
                "배달 완료", "배달 중", "가게 도착", "고객에게 전달",
                "배달 내역", "정산", "공지사항", "내 정보",
                // v2 2.0 추가 필터
                "배달 체험하기",
                "진행 배달미션", "배달 미션", "완료 시 최대", "미션 전체보기",
                "메뉴금액", "주문정보", "가게정보", "찾아오는 길",
                "신규배차를 켜고",
                // v2.1 추가 필터
                "주행기록 기반",
                // v2.2 유령콜 필터
                "배달이 많은 곳으로 이동"
            )
            if (baeminSkipKeywords.any { joined.contains(it) }) {
                return
            }
        }
        if (pkg == PKG_COUPANG) {
            if (joined.isBlank() || Regex("(NAVER|YouTube|Chrome|카카오톡|Samsung|배달 현황|출근하기)").containsMatchIn(joined)) {
                return
            }
        }

        Log.d("DeliveryFilter", "[$platformName] rawText: ${texts.joinToString(" | ")}")

        // v3.6: 배민 대량 중복 감지 — 파싱 전에 최근 3초 이내 같은 플랫폼 이벤트 횟수 체크
        val now0 = System.currentTimeMillis()
        recentCalls.removeIf { now0 - it.time > 60000 }  // 60초 지난 기록 정리

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

        // v3.6: 배민 대량 중복 감지 — 같은 플랫폼+금액 3초 이내 = 중복 무시
        val dedupedCalls = calls.filter { call ->
            val isDup = recentCalls.any { r ->
                r.platform == platformName && r.price == call.price && (now0 - r.time < 30000)
            }
            if (isDup) {
                Log.d("DeliveryFilter", "[$platformName] 대량 중복 무시: ${call.price}원")
                false
            } else {
                recentCalls.add(RecentCall(platformName, call.price, now0))
                true
            }
        }
        if (dedupedCalls.isEmpty()) return
        // 이후 dedupedCalls 사용
        val activeCalls = dedupedCalls

        val now = System.currentTimeMillis()

        // 배민 포인트 파싱 (참고용, 거리 환산 판정에 미사용)
        val baeminPoint = if (pkg == PKG_BAEMIN) BaeminParser.parsePoint(texts) else null

        // 콜별 enrichment + 판정
        val pendingCalls = activeCalls.map { call ->
            var enrichedCall = call  // 포인트 환산거리 주입 폐기 (v3.6)

            var pickupDistKm: Double? = null
            if (gpsActive && currentLat != 0.0) {
                val storeAddr = call.storeName.ifEmpty { call.destination }
                pickupDistKm = LocationTable.distanceTo(currentLat, currentLng, storeAddr)
                if (pickupDistKm != null) {
                    enrichedCall = enrichedCall.copy(pickupDistanceKm = pickupDistKm)
                }
            }
            val result = CallFilter.judge(enrichedCall, this)
            PendingCall(call, enrichedCall, result, baeminPoint, pickupDistKm)
        }

        // ── 배민: 2초 debounce (묶음 중복 방지) ──
        if (pkg == PKG_BAEMIN) {
            synchronized(baeminBuffer) {
                baeminBuffer.addAll(pendingCalls)
            }
            baeminDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
            baeminDebounceRunnable = Runnable { processBaeminBuffer() }
            debounceHandler.postDelayed(baeminDebounceRunnable!!, BAEMIN_DEBOUNCE_MS)
            return
        }

        // ── 쿠팡: 즉시 처리 (debounce 없음) ──
        for (pending in pendingCalls) {
            processDeliveryCall(pending, now)
        }

        // 오래된 히스토리 정리
        val expireTime = now - 60000
        callSpeakHistory.entries.removeIf { it.value < expireTime }
        callDetectedAt.entries.removeIf { it.value < expireTime }
    }

    /** 배민 debounce 버퍼 처리: 2초 후 실행 */
    private fun processBaeminBuffer() {
        val buffered: List<PendingCall>
        synchronized(baeminBuffer) {
            buffered = baeminBuffer.toList()
            baeminBuffer.clear()
        }
        if (buffered.isEmpty()) return

        val now = System.currentTimeMillis()

        // bundleCount > 1인 콜이 있으면 그것만 TTS
        val bundleCall = buffered.firstOrNull { it.call.bundleCount > 1 || it.call.isMulti }
        val ttsCall = bundleCall ?: buffered.last()

        for (pending in buffered) {
            if (pending === ttsCall) {
                processDeliveryCall(pending, now)
            } else {
                // TTS 없이 로그만 저장 (묶음 중복 제거)
                val silentResult = CallFilter.FilterResult(pending.result.verdict, pending.result.reason + " (묶음 중복 제거)")
                FilterLog.record(this, pending.enrichedCall, silentResult, pending.baeminPoint)
                Log.d("DeliveryFilter", "배민 debounce 로그만: ${pending.call.price}원 (묶음 중복 제거)")
            }
        }

        val expireTime = now - 60000
        callSpeakHistory.entries.removeIf { it.value < expireTime }
        callDetectedAt.entries.removeIf { it.value < expireTime }
    }

    /** 개별 콜 처리 (TTS + 로그 + 진동 등) */
    private fun processDeliveryCall(pending: PendingCall, now: Long) {
        val call = pending.call
        val enrichedCall = pending.enrichedCall
        val result = pending.result
        val baeminPoint = pending.baeminPoint
        val pickupDistKm = pending.pickupDistKm
        val platformName = call.platform

        val callKey = "${call.platform}_${call.price}_${call.distance ?: 0}"

        // 안전 조건: 1콜 1음성
        if (callSpeakHistory.containsKey(callKey)) {
            Log.d("DeliveryFilter", "중복 콜 건너뜀: $callKey")
            return
        }

        // 안전 조건: 2초 쿨다운
        if (now - lastSpeakTime < 2000) {
            Log.d("DeliveryFilter", "쿨다운 중 - 건너뜀")
            return
        }

        // 묶음 총액 기록 (부분 파싱 중복 방지)
        if (call.isMulti) {
            TtsDeduplicator.recordBundleTotal(call.platform, call.price)
        }

        // 로그 기록 (enriched call로 기록하여 포인트 환산거리 포함)
        FilterLog.record(this, enrichedCall, result, baeminPoint)
        // 마지막 감지 시각 기록 (상태 표시용)
        lastCallDetectedTime = now

        // ── TTS 3단계 판정 (중복 방지) ──
        if (!TtsDeduplicator.shouldSpeak(call.platform, call.price)) {
            Log.d("DeliveryFilter", "TtsDeduplicator 중복 스킵: ${call.platform} ${call.price}원")
            return
        }

        // 단가 계산
        val effectiveDist = enrichedCall.distance
            val unitPrice = if (effectiveDist != null && effectiveDist > 0)
                (call.price / effectiveDist).toInt() else 0
        val pName = if (call.platform == "coupang") "쿠팡" else "배민"
        val priceStr = formatPrice(call.price)
        val unitKorean = toKoreanNumber(unitPrice)

        // 픽업 예상 시간
        val pickupEtaMin = if (pickupDistKm != null && pickupDistKm > 0) {
            val speedKmh = if (currentSpeed > 1f) currentSpeed * 3.6 else 30.0
            (pickupDistKm / speedKmh * 60).toInt().coerceAtLeast(1)
        } else null
        val pickupTtsExtra = if (pickupEtaMin != null) ", 픽업 ${pickupEtaMin}분 거리" else ""

        if (result.verdict == CallFilter.Verdict.REJECT) {
            callSpeakHistory[callKey] = now
            lastSpeakTime = now
            lastDeliveryCall = call
            lastDeliveryVerdict = "넘기세요"
            lastDeliveryPlatform = platformName
            if (!TtsPrefs.isGrabOnlyEnabled(this)) {
                speakTts("$pName, 넘기세요, ${priceStr}원")
            }
            Log.d("DeliveryFilter", "REJECT: ${call.price}원 - ${result.reason}")
        } else {
            callSpeakHistory[callKey] = now
            val grabThreshold = TtsPrefs.getGrabThreshold(this)
            val isTopAccept = when {
                call.price >= grabThreshold -> true
                call.price >= TtsPrefs.getHighPriceThreshold(this) && (
                    (call.distance != null && call.distance <= 3.0) ||
                    (baeminPoint != null && baeminPoint <= 15.0)
                ) -> true
                unitPrice >= 2500 && effectiveDist != null && effectiveDist <= 3.0 -> true
                else -> false
            }
            lastDeliveryCall = call
            lastDeliveryPlatform = platformName
            lastDeliveryVerdict = if (isTopAccept) "잡으세요" else "괜찮습니다"

            if (isTopAccept) {
                lastSpeakTime = now
                speakTts("$pName, 잡으세요, ${priceStr}원$pickupTtsExtra")
                Log.d("DeliveryFilter", "ACCEPT(잡으세요): ${call.price}원, 단가 ${unitPrice}원/km")
                tryAutoAccept()
            } else if (!TtsPrefs.isRejectOnlyEnabled(this) && !TtsPrefs.isGrabOnlyEnabled(this)
                && CallFilter.isOkVoiceEnabled(this)) {
                lastSpeakTime = now
                var ttsMsg = "$pName, 괜찮습니다"
                if (unitPrice > 0) ttsMsg += ", 단가 $unitKorean"
                if (call.distance != null && call.distance > 3.0) ttsMsg += " 픽업 멉니다"
                if (baeminPoint != null && baeminPoint >= 25.0) ttsMsg += ", 먼 거리입니다"
                speakTts(ttsMsg)
                Log.d("DeliveryFilter", "ACCEPT(괜찮습니다): ${call.price}원")
            } else {
                Log.d("DeliveryFilter", "ACCEPT: ${call.price}원 - 음성 OFF (TTS설정)")
            }
        }

        // v3.6: 연속 REJECT 자동 기준 하향
        val streakMsg = CallFilter.updateRejectStreak(result.verdict, this)
        if (streakMsg != null) {
            speakTts(streakMsg)
        }
        consecutiveRejectCount = CallFilter.getConsecutiveRejectCount()

        if (call.storeName.isNotEmpty() && StoreManager.isFavorite(this, call.storeName)) {
            speakTts("단골 가게입니다")
        }

        val overlayText = "$lastDeliveryVerdict ${java.text.NumberFormat.getNumberInstance().format(call.price)}원"
        FloatingOverlay.show(this, overlayText)

        try {
            val db = CallLogDb.get(this)
            val up = if (enrichedCall.distance != null && enrichedCall.distance > 0)
                (enrichedCall.price / enrichedCall.distance).toInt() else 0
            db.insert(
                platform = call.platform, price = call.price,
                distance = enrichedCall.distance, unitPrice = up,
                point = baeminPoint, verdict = result.verdict.name,
                reason = result.reason, bundleCount = call.bundleCount,
                isMultiPickup = call.isMultiPickup, storeName = call.storeName,
                destination = call.destination, pickupKm = pickupDistKm
            )
        } catch (e: Exception) { Log.w("DeliveryFilter", "DB 저장 실패: ${e.message}") }

        playCallSound(lastDeliveryVerdict)
        vibrate(lastDeliveryVerdict)
        updateNotification()
        checkGoalProgress()
        DailyReport.onCallDetected(this)
    }

    /** 숫자를 한국어 TTS용으로 변환: 3200 → "삼천이백" */
    private fun toKoreanNumber(n: Int): String {
        if (n <= 0) return "영"
        val sb = StringBuilder()
        val cheon = n / 1000
        val baek = (n % 1000) / 100
        val sip = (n % 100) / 10
        val digits = arrayOf("", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
        if (n >= 10000) {
            val man = n / 10000
            if (man > 1) sb.append(digits[man])
            sb.append("만")
            val rest = n % 10000
            if (rest > 0) sb.append(toKoreanNumber(rest))
            return sb.toString()
        }
        if (cheon > 0) { if (cheon > 1) sb.append(digits[cheon]); sb.append("천") }
        if (baek > 0)  { if (baek > 1) sb.append(digits[baek]); sb.append("백") }
        if (sip > 0)   { if (sip > 1) sb.append(digits[sip]); sb.append("십") }
        val il = n % 10
        if (il > 0 && n >= 10) sb.append(digits[il])
        else if (n < 10) sb.append(digits[n])
        return sb.toString()
    }

    /** 외부에서 TTS 호출 (VoiceControl 등) */
    fun speakTtsPublic(text: String) = speakTts(text)

    /** v3.1: TTS 설정 반영 (속도, 볼륨 부스트) */
    private fun speakTts(text: String) {
        if (tts == null || !ttsReady) {
            Log.w("DeliveryFilter", "TTS 미준비 - 음성 출력 건너뜀")
            return
        }
        // TTS 속도 설정
        tts?.setSpeechRate(TtsPrefs.getSpeed(this))

        // 볼륨 부스트
        if (TtsPrefs.isVolBoostEnabled(this)) {
            try {
                val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                    am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC), 0)
            } catch (e: Exception) {
                Log.w("DeliveryFilter", "볼륨 부스트 실패: ${e.message}")
            }
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "filter_${System.currentTimeMillis()}")
    }

    /** v3.1: 금액 읽기 방식 (한국어 / 숫자) */
    private fun formatPrice(price: Int): String {
        return if (TtsPrefs.getPriceReadMode(this) == "number") {
            String.format("%,d", price)
        } else {
            toKoreanNumber(price)
        }
    }

    /** v3.3: 배달 완료 감지 시 처리 */
    private fun onDeliveryComplete() {
        if (!AdvancedPrefs.isDeliveryCompleteEnabled(this)) return
        val earnings = EarningsTracker.getToday(this)
        val fmt = java.text.NumberFormat.getNumberInstance()
        speakTts("배달 완료. 오늘 ${earnings.acceptedCount}건 완료, 매출 ${fmt.format(earnings.totalRevenue)}원")
        Log.d("OnTheWay", "배달 완료 감지")

        // 소요시간 기록
        if (lastAcceptTime > 0) {
            val elapsed = (System.currentTimeMillis() - lastAcceptTime) / 60000
            Log.d("OnTheWay", "배달 소요시간: ${elapsed}분")
            lastAcceptTime = 0
        }
    }

    /** v3.3: 콜 알림음 재생 */
    private fun playCallSound(verdict: String) {
        if (!AdvancedPrefs.isCallSoundEnabled(this)) return
        try {
            val resId = when (verdict) {
                "잡으세요" -> resources.getIdentifier("sound_grab", "raw", packageName)
                "괜찮습니다" -> resources.getIdentifier("sound_ok", "raw", packageName)
                "넘기세요" -> resources.getIdentifier("sound_skip", "raw", packageName)
                else -> 0
            }
            if (resId != 0) {
                val mp = android.media.MediaPlayer.create(this, resId)
                mp?.setOnCompletionListener { it.release() }
                mp?.start()
            } else {
                // 사운드 파일 없으면 기본 시스템 알림음
                val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                android.media.RingtoneManager.getRingtone(this, uri)?.play()
            }
        } catch (e: Exception) {
            Log.w("OnTheWay", "알림음 재생 실패: ${e.message}")
        }
    }

    /** v3.3: 목표 달성 확인 */
    private fun checkGoalProgress() {
        if (!GoalManager.isGoalAlertEnabled(this)) return
        val progress = GoalManager.getProgress(this)
        if (progress >= 1.0f && !GoalManager.wasFullAlerted(this)) {
            GoalManager.markFullAlerted(this)
            speakTts("오늘 목표 달성! 수고하셨습니다!")
        } else if (progress >= 0.5f && !GoalManager.wasHalfAlerted(this)) {
            GoalManager.markHalfAlerted(this)
            speakTts("목표 절반 달성!")
        }
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
        // 각 모듈 초기화는 개별 try-catch (하나 실패해도 서비스 계속)
        try { startForegroundNotification() } catch (e: Exception) { Log.w("OnTheWay", "알림 초기화 실패: ${e.message}") }
        try { startGps() } catch (e: Exception) { Log.w("OnTheWay", "GPS 초기화 실패: ${e.message}") }
        try { VoiceControl.start(this) } catch (e: Exception) { Log.w("OnTheWay", "음성제어 초기화 실패: ${e.message}") }
        try { CallLogDb.get(this).cleanup() } catch (e: Exception) { Log.w("OnTheWay", "DB 정리 실패: ${e.message}") }
        Log.d("OnTheWay", "OnTheWay 서비스 시작")
    }

    private var locationManager: LocationManager? = null
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            currentLat = loc.latitude
            currentLng = loc.longitude
            currentSpeed = loc.speed
            gpsActive = true
            Log.d("OTW_GPS", "위치: $currentLat, $currentLng, 속도: ${currentSpeed}m/s")
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { gpsActive = false }
    }

    private fun startGps() {
        if (!AdvancedPrefs.isGpsEnabled(this)) return
        try {
            locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 30000L, 50f, locationListener
                )
                // 네트워크도 fallback
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 30000L, 50f, locationListener
                )
                gpsActive = true
                Log.d("OTW_GPS", "GPS 시작")
            } else {
                Log.w("OTW_GPS", "위치 권한 없음 - GPS 비활성화")
                gpsActive = false
            }
        } catch (e: Exception) {
            Log.w("OTW_GPS", "GPS 시작 실패: ${e.message}")
            gpsActive = false
        }
    }

    private fun startForegroundNotification() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(NOTIF_CHANNEL_ID, "OnTheWay 서비스", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "OnTheWay 작동 상태"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
            // AccessibilityService는 startForeground 불가 → 일반 알림 사용
            val notif = buildStatusNotification("OnTheWay 작동 중 | 대기")
            nm.notify(NOTIF_ID, notif)
        } catch (e: Exception) {
            Log.w("OnTheWay", "알림 표시 실패: ${e.message}")
        }
    }

    /** v3.2: 콜 감지 시 알림 업데이트 */
    fun updateNotification() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildStatusNotification(null))
        } catch (e: Exception) { /* ignore */ }
    }

    private fun buildStatusNotification(customText: String?): android.app.Notification {
        val fmt = java.text.NumberFormat.getNumberInstance()
        val detail = FilterLog.getTodayDetail(this)
        val earnings = EarningsTracker.getToday(this)
        val count = if (earnings.acceptedCount > 0) earnings.acceptedCount else detail.total
        val revenue = earnings.totalRevenue
        val hourly = earnings.hourlyRate
        val hourlyStr = if (hourly > 0) "${fmt.format(hourly)}원/h" else "0원/h"
        val text = customText ?: "OnTheWay 작동 중 | 오늘 ${count}건 | 매출 ${fmt.format(revenue)}원 | 시급 $hourlyStr"

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("OnTheWay")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** v3.2: 진동 발생 */
    private fun vibrate(verdict: String) {
        if (!TtsPrefs.isVibrationEnabled(this)) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            when (verdict) {
                "잡으세요" -> {
                    val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                }
                "괜찮습니다" -> {
                    vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        } catch (e: Exception) {
            Log.w("OnTheWay", "진동 실패: ${e.message}")
        }
    }

    override fun onDestroy() {
        try { tts?.shutdown() } catch (e: Exception) {}
        tts = null
        ttsReady = false
        try { locationManager?.removeUpdates(locationListener) } catch (e: Exception) {}
        gpsActive = false
        try { VoiceControl.stop() } catch (e: Exception) {}
        try { FloatingOverlay.hide() } catch (e: Exception) {}
        super.onDestroy()
    }
}
