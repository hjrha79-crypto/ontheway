package com.vita.ontheway

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
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

    // 배달 필터용 TTS
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        // v2.2: 진단 모드 — 모든 패키지별 이벤트 카운트
        packageEventCount[pkg] = (packageEventCount[pkg] ?: 0) + 1

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
                    Log.d("OTW_DEBUG", "getWindows에서도 $pkg 윈도우 없음, root=$rootPkg 로 처리")
                    handleDeliveryPlatform(root, pkg)
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
                "주행기록 기반"
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

        // 배민 포인트 파싱 (거리 지표)
        val baeminPoint = if (pkg == PKG_BAEMIN) BaeminParser.parsePoint(texts) else null
        // 포인트 기반 환산거리 (1P = 0.25km, 보수적 환산)
        val pointDistKm = if (baeminPoint != null) baeminPoint * 0.25 else null

        for (call in calls) {
            // 포인트 환산 정보를 CallFilter에 전달하기 위해 enriched call 생성
            val enrichedCall = if (call.platform == "baemin" && call.distance == null && pointDistKm != null) {
                call.copy(distance = pointDistKm)
            } else call

            val result = CallFilter.judge(enrichedCall, this)
            val callKey = "${call.platform}_${call.price}_${call.distance ?: 0}"

            // 안전 조건: 1콜 1음성
            if (callSpeakHistory.containsKey(callKey)) {
                Log.d("DeliveryFilter", "중복 콜 건너뜀: $callKey")
                continue
            }

            // 안전 조건: 2초 쿨다운
            if (now - lastSpeakTime < 2000) {
                Log.d("DeliveryFilter", "쿨다운 중 - 건너뜀")
                continue
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
                continue
            }

            // 단가 계산: 포인트 환산거리 우선, 없으면 실거리
            val effectiveDist = pointDistKm ?: call.distance
            val unitPrice = if (effectiveDist != null && effectiveDist > 0)
                (call.price / effectiveDist).toInt() else 0
            val pName = if (call.platform == "coupang") "쿠팡" else "배민"
            val priceStr = formatPrice(call.price)
            val unitKorean = toKoreanNumber(unitPrice)

            if (result.verdict == CallFilter.Verdict.REJECT) {
                // REJECT → "넘기세요"
                callSpeakHistory[callKey] = now
                lastSpeakTime = now
                lastDeliveryCall = call
                lastDeliveryVerdict = "넘기세요"
                lastDeliveryPlatform = platformName
                // v3.1: 잡으세요만 모드면 REJECT 음성 스킵
                if (!TtsPrefs.isGrabOnlyEnabled(this)) {
                    speakTts("$pName, 넘기세요, ${priceStr}원")
                }
                Log.d("DeliveryFilter", "REJECT: ${call.price}원 - ${result.reason}")
            } else {
                callSpeakHistory[callKey] = now

                // ── "잡으세요" 확장 판정 (v2 2.0) ──
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

                // v3.0: 마지막 판정 기록
                lastDeliveryCall = call
                lastDeliveryPlatform = platformName
                lastDeliveryVerdict = if (isTopAccept) "잡으세요" else "괜찮습니다"

                if (isTopAccept) {
                    lastSpeakTime = now
                    speakTts("$pName, 잡으세요, ${priceStr}원")
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

            // v3.2: 진동 + 알림 업데이트
            vibrate(lastDeliveryVerdict)
            updateNotification()

            // v3.0: 일별 리포트 타이머 갱신
            DailyReport.onCallDetected(this)
        }

        // 오래된 히스토리 정리
        val expireTime = now - 60000
        callSpeakHistory.entries.removeIf { it.value < expireTime }
        callDetectedAt.entries.removeIf { it.value < expireTime }
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
        // v3.2: Foreground notification
        startForegroundNotification()
        Log.d("OnTheWay", "OnTheWay 서비스 시작")
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CHANNEL_ID, "OnTheWay 서비스", NotificationManager.IMPORTANCE_LOW).apply {
                description = "OnTheWay 작동 상태"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
        val notif = buildStatusNotification("OnTheWay 작동 중 | 대기")
        try {
            startForeground(NOTIF_ID, notif)
        } catch (e: Exception) {
            Log.w("OnTheWay", "startForeground 실패: ${e.message}")
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
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        when (verdict) {
            "잡으세요" -> {
                // 강한 진동 500ms x 3회
                val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
            "괜찮습니다" -> {
                // 보통 진동 300ms x 1회
                vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            // 넘기세요: 진동 없음
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        ttsReady = false
        super.onDestroy()
    }
}
