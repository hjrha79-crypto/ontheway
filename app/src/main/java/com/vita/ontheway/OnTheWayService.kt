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

    // [Hotfix-2 P0-3] DB I/O 비동기화 executor
    private val dbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "OTW-DB-IO").apply { isDaemon = true }
    }

    // [Hotfix-2 P0-1] 윈도우 재추적 갱신 관리
    private var lastRefreshTime: Long = 0L
    private val REFRESH_COOLDOWN_MS = 3000L
    private var refreshCount: Int = 0
    private val windowEventRing = mutableListOf<String>()
    private val WINDOW_EVENT_RING_SIZE = 50

    companion object {
        private const val TAG_ACCESSIBILITY = "OTW_ACCESSIBILITY"

        private val REFRESH_TARGET_PACKAGES = setOf(
            PKG_COUPANG, PKG_BAEMIN, PKG_FLEXER
        )

        var currentDir: String = ""
        var currentDest: String = ""
        var departureTime: String = ""
        var lastShadowTs: String = ""
        var instance: OnTheWayService? = null
        var resultCallback: ((String, String, Int, String) -> Unit)? = null

        // FIX-SELFPING: 마지막 accessibility 이벤트 수신 시각
        @Volatile var lastAccessibilityEventTime: Long = 0L

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

        // P0-2 진단: 마지막 View 트리 로그 시각 (5초 쿨다운)
        private var lastP02DiagTime: Long = 0

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
        val ACCEPT_BUTTON_TEXTS = listOf("수락", "배차수락", "배차 수락", "주문 수락", "주문수락", "수락하기", "모두 수락")

        const val ROAD_DISTANCE_FACTOR = 1.3  // 직선 → 도로 거리 보정 계수
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

    // 서비스 시작 시각 (재시작 시 오래된 이벤트 DROP용)
    private var serviceStartTime: Long = 0L
    private var serviceStartUptime: Long = 0L

    // 1콜 1음성: callKey → 마지막 발화 시각
    private val callSpeakHistory = mutableMapOf<String, Long>()
    // callKey → 최초 감지 시각 (3초 안전창)
    private val callDetectedAt = mutableMapOf<String, Long>()
    private var lastSpeakTime: Long = 0
    var lastCallDetectedTime: Long = 0  // 상태 표시용


    // v3.0: 마지막 판정 정보 (수락 감지용 + 오버레이 피드백)
    var lastDeliveryCall: DeliveryCall? = null
    var lastDeliveryVerdict: String = ""  // "추천", "보통", "비추천"
    var lastDeliveryPlatform: String = ""
    var lastDeliveryReason: String? = null
    var lastDeliverySessionId: String? = null

    // v3.3: 연속 REJECT 카운터
    var consecutiveRejectCount: Int = 0
    // v3.3: 마지막 수락 시각 (배달 완료 소요시간)
    private var lastAcceptTime: Long = 0

    // 배민 묶음 debounce (2초 윈도우)
    private data class PendingCall(val call: DeliveryCall, val enrichedCall: DeliveryCall, val result: CallFilter.FilterResult, val baeminPoint: Double?, val pickupDistKm: Double?)
    private val baeminBuffer = mutableListOf<PendingCall>()
    private var baeminDebounceRunnable: Runnable? = null
    private var bundleTimeoutRunnable: Runnable? = null
    private val debounceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val BAEMIN_DEBOUNCE_MS = 2000L

    // FIX-RETRY: root null / parser empty 시 짧은 retry
    private val RETRY_DELAY_1_MS = 200L
    private val RETRY_DELAY_2_MS = 500L

    // v3.6: 배민 콜 대량 중복 감지 방지 (같은 플랫폼+금액+storeName 30초 이내 = 중복)
    private data class RecentCall(val platform: String, val price: Int, val store: String, val time: Long)
    private val recentCalls = mutableListOf<RecentCall>()

    // v3.24: 배민 묶음→단건 중복 방지
    private var lastBundleTime: Long = 0L
    private var lastBundlePrice: Int = 0
    // FIX-MULTI-SPLIT: 마지막 묶음의 가게명 목록 (분리 재요청 감지용)
    private var lastBundleStoreNames: Set<String> = emptySet()

    // v3.18: 세션 매니저
    private var sessionManager: SessionManager? = null

    // v3.26: 고객 요청 리마인드 (중복 방지)
    private var lastCustomerRequest: String? = null
    private var lastCustomerRequestCallKey: String = ""

    // v3.22: 절전 모드 스킵 카운트
    private var powerSaveSkipCount = 0L

    // 배달 필터용 TTS
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        lastAccessibilityEventTime = System.currentTimeMillis()
        val pkg = event.packageName?.toString() ?: return

        // 서비스 시작 5초 이전 이벤트 DROP (재시작 시 오래된 큐 방지)
        if (serviceStartUptime > 0 && event.eventTime < serviceStartUptime - 5_000) {
            DropReason.recordDrop(DropReason.DROP_STALE, "eventTime=${event.eventTime} < startUptime=$serviceStartUptime", pkg)
            return
        }

        // [Hotfix-2 P0-1] 타겟 앱 윈도우 상태 변경 감지 + 자동 재연결
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && pkg in REFRESH_TARGET_PACKAGES) {
            recordWindowEvent(pkg, event.eventType)
            val now = System.currentTimeMillis()
            if (now - lastRefreshTime > REFRESH_COOLDOWN_MS) {
                refreshAccessibilityConnection(pkg)
                lastRefreshTime = now
            } else {
                val cooldownMsg = "쿨다운 중, 재할당 스킵: pkg=$pkg, elapsed=${now - lastRefreshTime}ms"
                Log.d(TAG_ACCESSIBILITY, cooldownMsg)
                OtwFileLogger.log(TAG_ACCESSIBILITY, cooldownMsg)
            }
        }

        // v3.23: 배민/쿠팡은 운행 모드와 무관하게 항상 처리
        val isDeliveryApp = (pkg == PKG_BAEMIN || pkg == PKG_COUPANG)
        if (!isDeliveryApp) {
            // 타 앱 이벤트: 운행 OFF 시 스킵
            if (DrivingModeManager.getMode(this) != DrivingMode.DRIVING && !FeatureFlags.backgroundCapture) {
                powerSaveSkipCount++
                if (powerSaveSkipCount % 100 == 1L) {
                    OtwFileLogger.log("PowerSave", "[절전] pkg=${pkg} → 스킵 (누적 ${powerSaveSkipCount}건)")
                }
                return
            }
        }

        // 2026-04-25 P0: 자기 패키지 즉시 차단 (자기 참조 유령 세션 방지)
        if (pkg == "com.vita.ontheway") {
            DropReason.recordDrop(DropReason.DROP_SELF_REFERENCE, "AccessibilityEvent", pkg)
            return
        }

        // v2.2: 진단 모드 — 모든 패키지별 이벤트 카운트
        packageEventCount[pkg] = (packageEventCount[pkg] ?: 0) + 1

        // 쿠팡 진단 모드 — DiagnosticLog 별도 DB 기록 (v3.14)
        if (pkg == PKG_COUPANG && AdvancedPrefs.isCoupangDebugEnabled(this)) {
            val evTexts = event.text?.joinToString(" ") ?: ""
            val className = event.className?.toString() ?: ""
            DiagnosticLog.record(pkg, event.eventType, className, evTexts)

            // getWindows() 패키지 로그 (10초에 1회)
            val now = System.currentTimeMillis()
            if (now - (callDetectedAt["coupang_win_log"] ?: 0) > 10000) {
                callDetectedAt["coupang_win_log"] = now
                try {
                    val winPkgs = windows?.mapNotNull { w -> w.root?.packageName?.toString() } ?: emptyList()
                    DiagnosticLog.record(pkg, event.eventType, "WINDOWS", winPkgs.toString())
                } catch (e: Exception) {}
            }
        }

        // v3.19.3: 쿠팡이츠 Flutter 접근성 트리 진단 로깅
        if (pkg == PKG_COUPANG && FeatureFlags.coupangDiagnosticLogging) {
            try {
                val diagRoot = rootInActiveWindow ?: event.source
                if (diagRoot != null) {
                    com.vita.ontheway.diagnostic.CoupangDiagnosticLogger.logEvent(diagRoot, event.eventType)
                }
            } catch (e: Exception) {
                Log.w("CoupangDiag", "진단 로깅 실패: ${e.message}")
            }
        }

        // v3.21: 배민 접근성 트리 진단 로깅 (거리 탐색용)
        if (pkg == PKG_BAEMIN && FeatureFlags.baeminDiagnosticLogging) {
            try {
                val diagRoot = rootInActiveWindow ?: event.source
                if (diagRoot != null) {
                    com.vita.ontheway.diagnostic.BaeminDiagnosticLogger.logEvent(diagRoot, event.eventType)
                }
            } catch (e: Exception) {
                Log.w("BaeminDiag", "진단 로깅 실패: ${e.message}")
            }
        }

        // 카카오 관련 패키지는 별도 경고 로그
        if (pkg.contains("kakaomobility") || pkg.contains("flexer") || pkg.contains("kakao")) {
            Log.w("OTW_KAKAO", "★ 카카오: pkg=$pkg, type=${event.eventType}, count=${packageEventCount[pkg]}, source=${event.source != null}, root=${rootInActiveWindow != null}")
        }

        // v3.0: 수락 버튼 클릭 감지 (수익 트래킹)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedText = event.text?.joinToString("") ?: event.contentDescription?.toString() ?: ""
            if (pkg in DELIVERY_PACKAGES && clickedText.isNotBlank()) {
                OtwFileLogger.log("CLICK", "pkg=$pkg text=${event.text} desc=${event.contentDescription}")
            }
            if (ACCEPT_BUTTON_TEXTS.any { clickedText.contains(it) }) {
                onAcceptDetected()
                // AcceptDetectionLogger (로그 기록)
                if (FeatureFlags.acceptLoggerEnabled) {
                    try {
                        AcceptDetectionLogger.log(
                            this, "click", pkg, clickedText,
                            sessionManager?.getActiveSession()?.sessionId
                        )
                    } catch (_: Exception) {}
                }
                // lastDeliveryCall null이면 FilterLog에서 금액 추출
                if (lastDeliveryCall == null) {
                    val fallback = AcceptCoordinator.getRecentPrice(this)
                    if (fallback != null) {
                        AcceptCoordinator.handleAccept(this, AcceptCoordinator.AcceptSource.FALLBACK, fallback.first, fallback.second)
                    }
                }
            }
        }

        // v3.3: 배달 완료 / 픽업 완료 감지
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val evTexts = event.text?.joinToString(" ") ?: ""
            if (evTexts.contains("배달 완료") || evTexts.contains("배달완료") || evTexts.contains("배달이 완료되었습니다")) {
                onDeliveryComplete()
            }
            // FIX-TTS-DELIVERY-FLOW: 시나리오 E - 픽업 완료 감지
            if (evTexts.contains("픽업 완료") || evTexts.contains("수령 완료") || evTexts.contains("출발")) {
                try { DeliveryFlowManager.onMultiPickupComplete() } catch (_: Exception) {}
            }
            // v3.20: 배달 시작 감지 (AcceptDetectionLogger)
            if (FeatureFlags.acceptLoggerEnabled && evTexts.isNotBlank()) {
                try {
                    val isDeliveryStart = when (pkg) {
                        PKG_COUPANG -> evTexts.contains("배달중") || evTexts.contains("픽업하러 이동")
                        PKG_BAEMIN -> evTexts.contains("신규 배달 배정") || evTexts.contains("배달 시작")
                        else -> false
                    }
                    if (isDeliveryStart) {
                        AcceptDetectionLogger.log(
                            this, "delivery_start", pkg, evTexts,
                            sessionManager?.getActiveSession()?.sessionId
                        )
                    }
                } catch (_: Exception) {}
            }
        }

        if (pkg !in TARGET_PACKAGES) {
            DropReason.recordDrop(DropReason.DROP_PACKAGE_FILTER, "non-target package", pkg)
            return
        }

        // root 확보: rootInActiveWindow 우선, 없으면 event.source, 카카오T는 getWindows() fallback
        var root = rootInActiveWindow ?: event.source
        if (root == null && (pkg == PKG_FLEXER || pkg == PKG_DRIVER)) {
            root = findWindowRoot(pkg)
        }
        if (root == null) {
            if (pkg in DELIVERY_PACKAGES) {
                OtwFileLogger.logSync(TAG_ACCESSIBILITY, "ROOT_NULL: pkg=$pkg, eventType=${event.eventType}")
                try { CriticalEventDb.get(this).record("ROOT_NULL", pkg) } catch (_: Exception) {}
                // FIX-RETRY: 200ms + 500ms retry
                scheduleRootRetry(pkg, RETRY_DELAY_1_MS, 1)
            }
            return
        }

        // ── P0-2 진단: 배민/쿠팡 이벤트 시 View 트리 기록 ──
        if (pkg in DELIVERY_PACKAGES) {
            logViewTreeForDiag(root, pkg)
        }

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
        lastAccessibilityCallTime = now

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
            DropReason.recordDrop(DropReason.DROP_DUPLICATE, "kakaot callSpeakHistory $callKey")
            return
        }

        // ── 안전 조건 3: 3초 안전창 ─────────────
        val detectedAt = callDetectedAt.getOrPut(callKey) { now }
        if (now - detectedAt > 3000) {
            Log.d("OnTheWay", "3초 초과 - 음성 건너뜀: $callKey")
            DropReason.recordDrop(DropReason.DROP_COOLDOWN, "kakaot 3s window expired $callKey")
            callDetectedAt.remove(callKey)
            return
        }

        // ── 안전 조건 4: 최소 발화 간격 5초 ──────
        if (now - lastSpeakTime < 5000) {
            Log.d("OnTheWay", "발화 간격 미달 - 건너뜀")
            DropReason.recordDrop(DropReason.DROP_COOLDOWN, "kakaot speakTime cooldown 5s")
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



    private fun extractText(node: AccessibilityNodeInfo, results: MutableList<String>) {
        // 2026-04-25 P0: 자기 패키지 노드는 텍스트 수집 스킵 (자기 참조 방지)
        if (node.packageName?.toString() == "com.vita.ontheway") {
            DropReason.recordDrop(DropReason.DROP_SELF_REFERENCE, "extractText node", "com.vita.ontheway")
            return
        }

        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { results.add(it) }
        // 2026-04-24: 배민 "배달료기준거리"가 contentDescription에만 있는 케이스 커버.
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { results.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { extractText(it, results) }
        }
    }

    // ── P0-2 진단: View 트리 Logcat 기록 ──
    private fun logViewTreeForDiag(root: AccessibilityNodeInfo, pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastP02DiagTime < 5000) return  // 5초 쿨다운
        lastP02DiagTime = now
        logNodeRecursive(root, pkg, 0)
    }

    private fun logNodeRecursive(node: AccessibilityNodeInfo, pkg: String, depth: Int) {
        if (depth > 8) return  // 과도한 깊이 방지
        val id = node.viewIdResourceName ?: "null"
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val txt = node.text?.toString()?.take(20) ?: ""
        Log.d("P0_2_DIAG", "pkg=$pkg depth=$depth id=$id class=$cls text=\"$txt\"")
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { logNodeRecursive(it, pkg, depth + 1) }
        }
    }

    /** getWindows()를 순회하여 지정 패키지의 root를 찾는다 */
    private fun findWindowRoot(targetPkg: String): AccessibilityNodeInfo? {
        return try {
            windows?.firstNotNullOfOrNull { w ->
                w.root?.takeIf {
                    val winPkg = it.packageName?.toString()
                    // 2026-04-25 P0: OnTheWay 자기 윈도우 제외 (자기 참조 방지)
                    if (winPkg == "com.vita.ontheway") {
                        DropReason.recordDrop(DropReason.DROP_SELF_REFERENCE, "findWindowRoot", winPkg)
                    }
                    winPkg == targetPkg && winPkg != "com.vita.ontheway"
                }
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

        // 판정-행동 매칭: PENDING → ACCEPTED
        try { JudgmentMatchLogger.onAcceptDetected(this) } catch (_: Exception) {}

        // v3.22: 운행 모드 자동 ON + 시작 위치 저장
        if (DrivingModeManager.getMode(this) != DrivingMode.DRIVING) {
            ReturnTimeEstimator.saveStartLocation(this, currentLat, currentLng)
            StatusAlertEngine.reset()
        }
        DrivingModeManager.setMode(this, DrivingMode.DRIVING, "call_accepted")
        if (!gpsActive) startGps()  // 운행 ON 시 서비스 GPS도 시작

        // v3.3: 수락 시각 기록 (배달 완료 소요시간 계산용)
        lastAcceptTime = System.currentTimeMillis()

        // v3.26: 고객 요청 리마인드 (MEDIUM 이상 프리셋)
        if (FeatureFlags.ttsPreset.ordinal >= TtsPreset.MEDIUM.ordinal && lastCustomerRequest != null) {
            val request = lastCustomerRequest!!
            val callKey = "${platform}_${price}"
            if (callKey != lastCustomerRequestCallKey) {
                lastCustomerRequestCallKey = callKey
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    speakTts(request)
                    OtwFileLogger.log("CustomerRequest", "리마인드 TTS: \"$request\"")
                }, 5000)
            }
        }

        // GPS 근접 TTS 타겟 설정
        try { setProximityTarget(call, platform) } catch (_: Exception) {}

        // FIX-TTS-DELIVERY-FLOW: 배달 큐 설정
        try { DeliveryFlowManager.onAccepted(call, lastCustomerRequest) } catch (_: Exception) {}

        // 수익 트래킹
        EarningsTracker.recordAccept(this, price, platform)

        // 네비 자동실행 (3초 딜레이)
        val pickupAddr = call.storeName.ifEmpty { call.destination }
        if (pickupAddr.isNotEmpty()) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                NaviLauncher.autoLaunchForAccept(this, pickupAddr)
            }, 3000)
        }

        // 수락 피드백 다이얼로그 (FeatureFlag, 5초 지연)
        if (FeatureFlags.showAcceptFeedback) {
            val ctx = this
            val fbPlatform = platform
            val fbStore = call.storeName
            val fbPrice = price
            val fbDist = call.distance ?: 0.0
            val fbVerdict = lastDeliveryVerdict
            val fbReason = lastDeliveryReason ?: ""
            val fbSession = lastDeliverySessionId
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val intent = Intent(ctx, AcceptFeedbackActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra(AcceptFeedbackActivity.EXTRA_PLATFORM, fbPlatform)
                        putExtra(AcceptFeedbackActivity.EXTRA_STORE, fbStore)
                        putExtra(AcceptFeedbackActivity.EXTRA_PRICE, fbPrice)
                        putExtra(AcceptFeedbackActivity.EXTRA_DISTANCE, fbDist)
                        putExtra(AcceptFeedbackActivity.EXTRA_VERDICT, fbVerdict)
                        putExtra(AcceptFeedbackActivity.EXTRA_REASON, fbReason)
                        putExtra(AcceptFeedbackActivity.EXTRA_SESSION_ID, fbSession)
                    }
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    Log.w("OnTheWay", "수락 피드백 Activity 실행 실패: ${e.message}")
                }
            }, 5000)
        }
    }


    /**
     * FIX-RETRY: root null 또는 parser empty 시 짧은 retry.
     * rootInActiveWindow 재획득 → handleDeliveryPlatform 재진입.
     * retry 1: 200ms, retry 2: 500ms. 최대 2회.
     */
    private var lastRetryPkg: String? = null
    private var lastRetryTime: Long = 0L
    private fun scheduleRootRetry(pkg: String, delayMs: Long, attempt: Int) {
        if (attempt > 2) return
        // FIX-RETRY-2: 1초 dedup은 새 시퀀스(attempt=1)에만 적용
        // attempt > 1 = 같은 시퀀스 내 연속 retry → dedup 우회
        if (attempt == 1) {
            val now = System.currentTimeMillis()
            if (pkg == lastRetryPkg && now - lastRetryTime < 1000) return
            lastRetryPkg = pkg
            lastRetryTime = now
        }

        debounceHandler.postDelayed({
            if (instance == null) return@postDelayed
            val retryRoot = rootInActiveWindow ?: findWindowRoot(pkg)
            if (retryRoot != null) {
                val retryRootPkg = retryRoot.packageName?.toString()
                if (retryRootPkg == pkg) {
                    Log.d(TAG_ACCESSIBILITY, "RETRY_SUCCESS: pkg=$pkg attempt=$attempt delay=${delayMs}ms")
                    OtwFileLogger.log(TAG_ACCESSIBILITY, "RETRY_SUCCESS: pkg=$pkg attempt=$attempt")
                    handleDeliveryPlatform(retryRoot, pkg)
                } else {
                    val correctRoot = findWindowRoot(pkg)
                    if (correctRoot != null) {
                        Log.d(TAG_ACCESSIBILITY, "RETRY_SUCCESS(getWindows): pkg=$pkg attempt=$attempt")
                        OtwFileLogger.log(TAG_ACCESSIBILITY, "RETRY_SUCCESS(getWindows): pkg=$pkg attempt=$attempt")
                        handleDeliveryPlatform(correctRoot, pkg)
                    } else {
                        scheduleRootRetry(pkg, RETRY_DELAY_2_MS, attempt + 1)
                    }
                }
            } else {
                Log.d(TAG_ACCESSIBILITY, "RETRY_FAIL: pkg=$pkg attempt=$attempt root=null")
                OtwFileLogger.log(TAG_ACCESSIBILITY, "RETRY_FAIL: pkg=$pkg attempt=$attempt root=null")
                scheduleRootRetry(pkg, RETRY_DELAY_2_MS, attempt + 1)
            }
        }, delayMs)
    }

    // ── 배달 플랫폼 처리 (쿠팡이츠/배민커넥트) ─────────
    private fun handleDeliveryPlatform(root: AccessibilityNodeInfo, pkg: String) {
        val texts = mutableListOf<String>()
        val platformTag = if (pkg == PKG_COUPANG) "COUPANG" else "BAEMIN"
        PerfTrace.mark(platformTag, "event_received")
        extractText(root, texts)

        val platformName = if (pkg == PKG_COUPANG) "coupang" else "baemin"

        // 대기 화면 무시 필터 — 파싱/로그/TTS 전부 스킵 (최우선 체크)
        val joined = texts.joinToString(" ")
        if (pkg == PKG_BAEMIN) {
            // v3.19 Layer 1: 화면 타입 식별
            var screen = ScreenTypeDetector.detect(joined)
            // UNKNOWN이지만 배달료 패턴이 있으면 NEW_CALL로 오버라이드
            if (screen.type == ScreenTypeDetector.ScreenType.UNKNOWN &&
                Regex("배달료\\s*[\\d,]+\\s*원").containsMatchIn(joined)) {
                OtwFileLogger.log("ScreenFilter", "[ScreenFilter] UNKNOWN → NEW_CALL 오버라이드 (배달료 패턴 감지)")
                screen = ScreenTypeDetector.ScreenDetection(ScreenTypeDetector.ScreenType.NEW_CALL, ScreenTypeDetector.Confidence.LOW)
            }
            if (screen.type != ScreenTypeDetector.ScreenType.NEW_CALL &&
                screen.type != ScreenTypeDetector.ScreenType.BUNDLE_SESSION) {
                // 배민 배달 진행 화면 = 수락 확정
                if (screen.type == ScreenTypeDetector.ScreenType.IN_PROGRESS) {
                    val acceptPrice = lastDeliveryCall?.price
                        ?: AcceptCoordinator.getRecentPrice(this)?.first ?: 0
                    if (acceptPrice > 0) {
                        AcceptCoordinator.handleAccept(this, AcceptCoordinator.AcceptSource.BAEMIN_PROGRESS, acceptPrice, "baemin", lastDeliveryCall?.storeName ?: "")
                    }
                }
                Log.d("ScreenFilter", "[$platformName] skip: ${screen.type} (${screen.confidence})")
                DropReason.recordDrop(DropReason.DROP_SCREEN_FILTER, "${screen.type} (${screen.confidence})", pkg)
                if (FeatureFlags.screenFilterLogging) {
                    com.vita.ontheway.logger.ScreenFilterLogger.log(
                        this, pkg, screen.type.name, screen.confidence.name, joined
                    )
                }
                return
            }
        }
        if (pkg == PKG_COUPANG) {
            if (joined.isBlank() || Regex("(NAVER|YouTube|Chrome|카카오톡|Samsung|배달 현황|출근하기)").containsMatchIn(joined)) {
                return
            }
            // 쿠팡 픽업 진행 화면 감지 → 수락 확정 + 가게명 사후 추출
            if (joined.contains("매장 도착") || joined.contains("매장 픽업")) {
                val call = lastDeliveryCall
                // 가게명 사후 추출
                if (call != null && call.platform == "coupang" && call.storeName.isBlank()) {
                    val storeName = CoupangParser.extractStoreFromProgress(texts)
                    if (storeName.isNotBlank()) {
                        lastDeliveryCall = call.copy(storeName = storeName)
                        FilterLog.updateLastStoreName(this, storeName)
                        OtwFileLogger.log("CoupangParser", "사후 추출: store='$storeName'")
                    }
                }
                // 수락 확정 (픽업 화면 = 수락 완료 증거)
                val acceptPrice = call?.price
                    ?: AcceptCoordinator.getRecentPrice(this)?.first ?: 0
                if (acceptPrice > 0) {
                    AcceptCoordinator.handleAccept(this, AcceptCoordinator.AcceptSource.COUPANG_PICKUP, acceptPrice, "coupang", call?.storeName ?: "")
                }
                return  // 픽업 진행 화면은 콜 화면이 아니므로 return
            }
        }

        val rawMsg = "[$platformName] rawText: ${texts.joinToString(" | ")}"
        Log.d("DeliveryFilter", rawMsg)
        OtwFileLogger.log("DeliveryFilter", rawMsg)

        // v3.26: 고객 요청사항 파싱 (배민)
        if (pkg == PKG_BAEMIN) {
            val customerReq = BaeminParser.parseCustomerRequest(texts)
            if (customerReq != null) {
                lastCustomerRequest = customerReq
                OtwFileLogger.log("CustomerRequest", "감지: \"$customerReq\"")
            }
        }

        // v3.6: 배민 대량 중복 감지 — 파싱 전에 최근 3초 이내 같은 플랫폼 이벤트 횟수 체크
        val now0 = System.currentTimeMillis()
        recentCalls.removeIf { now0 - it.time > 60000 }  // 60초 지난 기록 정리

        // ── 배민 묶음 세션 선행 차단 ──
        if (pkg == PKG_BAEMIN) {
            PerfTrace.mark("BAEMIN", "session_check")
            BaeminBundleSession.checkAndStartSession(joined)

            if (BaeminBundleSession.isActive()) {
                // 파싱하여 세션에 데이터 피딩
                PerfTrace.mark("BAEMIN", "parse_start")
                val sessionCalls = BaeminParser.parse(texts)
                if (sessionCalls == null) {
                    DropReason.recordDrop(DropReason.DROP_HISTORY_SCREEN, "history screen in bundle session", pkg)
                    return
                }
                PerfTrace.mark("BAEMIN", "parse_end", "results=${sessionCalls.size}")
                val sessionPoint = BaeminParser.parsePoint(texts)
                for (call in sessionCalls) {
                    BaeminBundleSession.addCallData(call.price, call.point ?: sessionPoint, call.storeName)
                    // v3.18: SessionManager에 이벤트 기록
                    sessionManager?.onEventReceived("baemin", call.storeName, call.price, "accessibility_event", orderId = call.orderId)
                }

                // 대기 중인 debounce 버퍼도 세션으로 드레인
                synchronized(baeminBuffer) {
                    for (pending in baeminBuffer) {
                        BaeminBundleSession.addCallData(pending.call.price, pending.baeminPoint, pending.call.storeName)
                    }
                    baeminBuffer.clear()
                }
                baeminDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }

                // 총 합계 파싱 완료 시 즉시 종료
                if (BaeminBundleSession.canFinalize()) {
                    bundleTimeoutRunnable?.let { debounceHandler.removeCallbacks(it) }
                    PerfTrace.mark("BAEMIN", "session_finalize")
                    SessionStats.onBundleFinalized(this)
                    // v3.19: 묶음 suppression 등록 (finalize 전에 키 추출)
                    val suppressionKeys = BaeminBundleSession.getSuppressionKeys()
                    val currentSessId = sessionManager?.getActiveSession()?.sessionId ?: ""
                    sessionManager?.registerBundleSuppression(suppressionKeys, currentSessId)
                    // v3.18: SessionManager 경유
                    sessionManager?.finalizeActiveSession("bundle_finalized")
                    val bundleCall = BaeminBundleSession.finalize()
                    if (bundleCall != null) {
                        var enrichedBundle = bundleCall
                        var pickupDistKm: Double? = null
                        if (gpsActive && currentLat != 0.0 &&
                            DrivingModeManager.getMode(this) == DrivingMode.DRIVING) {
                            val addr = (bundleCall.storeName.split("+").firstOrNull() ?: "").ifEmpty {
                                bundleCall.destination
                            }
                            if (addr.isNotEmpty()) {
                                val straight = KakaoGeocoder.distanceTo(this, currentLat, currentLng, addr)
                                pickupDistKm = validatePickupDistance(straight)
                                if (pickupDistKm != null) {
                                    enrichedBundle = enrichedBundle.copy(pickupDistanceKm = pickupDistKm)
                                }
                            }
                        }
                        val result = CallFilter.judge(enrichedBundle, this)
                        val pendingCall = PendingCall(bundleCall, enrichedBundle, result, bundleCall.point, pickupDistKm)
                        lastCallDetectedTime = System.currentTimeMillis()
                        lastAccessibilityCallTime = System.currentTimeMillis()
                        processDeliveryCall(pendingCall, System.currentTimeMillis())
                    }
                } else {
                    scheduleBundleTimeout()
                }
                return
            }
        }

        // 파싱
        PerfTrace.mark(platformTag, "parse_start")
        val calls = when (pkg) {
            PKG_COUPANG -> CoupangParser.parse(texts)
            PKG_BAEMIN  -> BaeminParser.parse(texts) ?: run {
                DropReason.recordDrop(DropReason.DROP_HISTORY_SCREEN, "history screen detected", pkg)
                return
            }
            else        -> return
        }
        PerfTrace.mark(platformTag, "parse_end", "results=${calls.size}")
        Log.d("OTW_DEBUG", "파서 호출: 결과=$calls")

        if (calls.isEmpty()) {
            Log.d("DeliveryFilter", "[$platformName] 파싱 실패 - 무음")
            OtwFileLogger.log("DeliveryFilter", "[$platformName] 파싱 실패 - 무음")
            DropReason.recordDrop(DropReason.DROP_PARSE_FAIL, "parser returned empty", pkg)
            // FIX-RETRY: 200ms retry (parser empty)
            scheduleRootRetry(pkg, RETRY_DELAY_1_MS, 1)
            return
        }

        // v3.6: 배민 대량 중복 감지 — 같은 플랫폼+금액 3초 이내 = 중복 무시
        // FIX-T2CN: orderId 1순위 → storeName → rawText hash fallback
        val dedupedCalls = calls.filter { call ->
            val storeKey = call.orderId ?: call.storeName.ifEmpty { call.rawText.hashCode().toString() }
            val isDup = recentCalls.any { r ->
                r.platform == platformName && r.price == call.price && r.store == storeKey && (now0 - r.time < 30000)
            }
            if (isDup) {
                Log.d("DeliveryFilter", "[$platformName] 대량 중복 무시: ${call.price}원 $storeKey")
                OtwFileLogger.log("DeliveryFilter", "[$platformName] 대량 중복 무시: ${call.price}원 $storeKey")
                DropReason.recordDrop(DropReason.DROP_DUPLICATE, "$platformName ${call.price}원 $storeKey 30s내 중복", pkg)
                false
            } else {
                recentCalls.add(RecentCall(platformName, call.price, storeKey, now0))
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
            if (gpsActive && currentLat != 0.0 &&
                DrivingModeManager.getMode(this) == DrivingMode.DRIVING) {
                val addr = call.storeName.ifEmpty { call.destination }
                val straight = KakaoGeocoder.distanceTo(this, currentLat, currentLng, addr)
                pickupDistKm = validatePickupDistance(straight)
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
            // v3.18: 쿠팡은 알림 도착 = 즉시 finalize
            sessionManager?.finalizeActiveSession("coupang_immediate")
        }

        // 오래된 히스토리 정리
        val expireTime = now - 60000
        callSpeakHistory.entries.removeIf { it.value < expireTime }
        callDetectedAt.entries.removeIf { it.value < expireTime }
    }

    /** 배민 debounce 버퍼 처리: 2초 후 실행 */
    private fun processBaeminBuffer() {
        // 세션 활성 시 버퍼를 세션으로 드레인
        if (BaeminBundleSession.isActive()) {
            synchronized(baeminBuffer) {
                for (pending in baeminBuffer) {
                    BaeminBundleSession.addCallData(pending.call.price, pending.baeminPoint, pending.call.storeName)
                }
                baeminBuffer.clear()
            }
            return
        }

        val buffered: List<PendingCall>
        synchronized(baeminBuffer) {
            buffered = baeminBuffer.toList()
            baeminBuffer.clear()
        }
        if (buffered.isEmpty()) return

        val now = System.currentTimeMillis()

        // 선행 차단으로 "묶음 중복 제거" 불필요 → 모든 콜 개별 처리
        for (pending in buffered) {
            SessionStats.onSingleDetected(this)
            processDeliveryCall(pending, now)
        }

        val expireTime = now - 60000
        callSpeakHistory.entries.removeIf { it.value < expireTime }
        callDetectedAt.entries.removeIf { it.value < expireTime }
    }

    /** 묶음 세션 타임아웃 핸들러 예약 */
    private fun scheduleBundleTimeout() {
        bundleTimeoutRunnable?.let { debounceHandler.removeCallbacks(it) }
        bundleTimeoutRunnable = Runnable {
            if (BaeminBundleSession.isActive()) {
                SessionStats.onBundleTimeout(this)
                // v3.19: 타임아웃에서도 suppression 등록
                val suppressionKeys = BaeminBundleSession.getSuppressionKeys()
                val timeoutSessId = sessionManager?.getActiveSession()?.sessionId ?: ""
                sessionManager?.registerBundleSuppression(suppressionKeys, timeoutSessId)
                sessionManager?.finalizeActiveSession("bundle_timeout")
                val bundleCall = BaeminBundleSession.finalizeOnTimeout()
                if (bundleCall != null) {
                    val result = CallFilter.judge(bundleCall, this)
                    val pendingCall = PendingCall(bundleCall, bundleCall, result, bundleCall.point, null)
                    lastCallDetectedTime = System.currentTimeMillis()
                    lastAccessibilityCallTime = System.currentTimeMillis()
                    processDeliveryCall(pendingCall, System.currentTimeMillis())
                }
            }
        }
        debounceHandler.postDelayed(bundleTimeoutRunnable!!, BaeminBundleSession.SESSION_TIMEOUT_MS)
    }

    /** 개별 콜 처리 (TTS + 로그 + 진동 등) */
    private fun processDeliveryCall(pending: PendingCall, now: Long) {
        PerfTrace.mark(if (pending.call.platform == "coupang") "COUPANG" else "BAEMIN", "tts_start")
        val call = pending.call
        val enrichedCall = pending.enrichedCall
        val result = pending.result
        val baeminPoint = pending.baeminPoint
        val pickupDistKm = pending.pickupDistKm
        val platformName = call.platform

        // v3.20: 묶음 suppression 조기 체크 (TTS 큐잉 전)
        val sessId = sessionManager?.getCurrentOrLastSessionId()
        if (sessionManager?.isSuppressed(call.storeName, call.price, sessId) == true) {
            Log.d("DeliveryFilter", "묶음 suppression 차단: ${call.storeName}|${call.price}")
            OtwFileLogger.log("DeliveryFilter", "묶음 suppression 차단: ${call.storeName}|${call.price}")
            DropReason.recordDrop(DropReason.DROP_DUPLICATE, "bundle suppression ${call.storeName}|${call.price}")
            return
        }

        // v3.24: 배민 묶음→단건 중복 방지 (묶음 후 10초 내 단건 = 부분콜 DROP)
        // FIX-MULTI-SPLIT: 묶음에 없던 가게의 단건 = 분리 재요청 → 통과
        if (call.platform == "baemin" && !call.isMulti && call.bundleCount <= 1
            && lastBundleTime > 0 && now - lastBundleTime < 10_000) {
            val isSplitReOffer = call.storeName.isNotBlank() &&
                lastBundleStoreNames.isNotEmpty() &&
                lastBundleStoreNames.none { call.storeName.contains(it) || it.contains(call.storeName) }
            if (isSplitReOffer) {
                OtwFileLogger.log("DeliveryFilter", "SPLIT_RE_OFFER: ${call.price}원 store='${call.storeName}' (묶음에 없던 가게)")
            } else {
                Log.d("DeliveryFilter", "묶음 단건 중복 DROP: ${call.price}원 (묶음 ${lastBundlePrice}원 ${(now - lastBundleTime)/1000}초 전)")
                OtwFileLogger.log("DeliveryFilter", "묶음 단건 중복 DROP: ${call.price}원 (묶음 ${lastBundlePrice}원)")
                DropReason.recordDrop(DropReason.DROP_DUPLICATE, "bundle_single_dedup baemin ${call.price}원")
                return
            }
        }

        // P0 fix: 같은 플랫폼+금액+storeName 중복 DROP — 쿠팡 60초, 기타 30초
        // FIX-T2CN: orderId 1순위 → storeName → rawText hash fallback
        val storeId = call.orderId ?: call.storeName.ifEmpty { call.rawText.hashCode().toString() }
        val simpleDedupKey = "${call.platform}_${call.price}_${storeId}_"
        val lastSimple = callSpeakHistory.entries.find { it.key.startsWith(simpleDedupKey) }
        val simpleDedupMs = if (call.platform == "coupang") 60_000L else 30_000L
        if (lastSimple != null && now - lastSimple.value < simpleDedupMs) {
            Log.d("DeliveryFilter", "${simpleDedupMs/1000}초 중복 DROP: $simpleDedupKey")
            OtwFileLogger.log("DeliveryFilter", "${simpleDedupMs/1000}초 중복 DROP: $simpleDedupKey")
            DropReason.recordDrop(DropReason.DROP_DUPLICATE, "${simpleDedupMs/1000}s dedup $simpleDedupKey")
            return
        }

        val callKey = "${call.platform}_${call.price}_${storeId}_${call.distance ?: 0}"

        // 안전 조건: 1콜 1음성
        if (callSpeakHistory.containsKey(callKey)) {
            Log.d("DeliveryFilter", "중복 콜 건너뜀: $callKey")
            OtwFileLogger.log("DeliveryFilter", "중복 콜 건너뜀: $callKey")
            DropReason.recordDrop(DropReason.DROP_DUPLICATE, "callSpeakHistory $callKey")
            return
        }

        // 안전 조건: 2초 쿨다운
        if (now - lastSpeakTime < 2000) {
            Log.d("DeliveryFilter", "쿨다운 중 - 건너뜀")
            OtwFileLogger.log("DeliveryFilter", "쿨다운 중 - 건너뜀")
            DropReason.recordDrop(DropReason.DROP_COOLDOWN, "speakTime cooldown 2s")
            return
        }

        // 묶음 총액 기록 (부분 파싱 중복 방지)
        if (call.isMulti) {
            TtsDeduplicator.recordBundleTotal(call.platform, call.price)
            // v3.24: 묶음 시각/금액 기록 (단건 중복 방지용)
            if (call.platform == "baemin") {
                lastBundleTime = now
                lastBundlePrice = call.price
                // FIX-MULTI-SPLIT: 묶음 가게명 목록 기록
                lastBundleStoreNames = call.storeName.split("+").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            }
        }

        // v3.18: SessionManager 경유 (세션 생성/추적용, suppression은 위에서 이미 처리)
        val callSessionEvt = sessionManager?.onEventReceived(
            platformName, call.storeName, call.price, "delivery_process", orderId = call.orderId
        )

        // 로그 기록 (enriched call로 기록하여 포인트 환산거리 포함)
        FilterLog.record(this, enrichedCall, result, baeminPoint,
            eventId = callSessionEvt?.eventId,
            sessionState = callSessionEvt?.state?.name)
        // 마지막 감지 시각 기록 (상태 표시용)
        lastCallDetectedTime = now
        lastAccessibilityCallTime = now

        // 단가 계산
        val effectiveDist = enrichedCall.distance
            val unitPrice = if (effectiveDist != null && effectiveDist > 0)
                (call.price / effectiveDist).toInt() else 0
        val pName = if (call.platform == "coupang") "쿠팡" else "배민"
        val priceStr = formatPrice(call.price)
        val unitKorean = toKoreanNumber(unitPrice)

        // 판정 결과 → 상태 변수 설정 (TTS 여부와 무관하게 항상 실행)
        callSpeakHistory[callKey] = now
        TtsDeduplicator.recordProcessed(call.platform, call.price)
        lastDeliveryReason = result.reason
        lastDeliverySessionId = callSessionEvt?.eventId
        // 내부 verdict (데이터/JudgmentMatch용 — 사용자에게는 노출 X)
        if (result.verdict == CallFilter.Verdict.REJECT) {
            lastDeliveryCall = call
            lastDeliveryVerdict = "비추천"
            lastDeliveryPlatform = platformName
        } else {
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
            lastDeliveryVerdict = if (isTopAccept) "추천" else "보통"
        }

        // ── TTS 3단계 판정 (중복 방지) — TTS만 스킵, 오버레이/DB/로그는 항상 실행 ──
        val shouldSpeak = TtsDeduplicator.shouldSpeak(call.platform, call.price)
        var ttsActuallySpoken = false // v2.2: TTS 실제 발화 추적
        if (!shouldSpeak) {
            Log.d("DeliveryFilter", "TtsDeduplicator 중복 → TTS 스킵, 오버레이/DB는 실행: ${call.platform} ${call.price}원")
            OtwFileLogger.log("DeliveryFilter", "TtsDeduplicator 중복 → TTS 스킵: ${call.platform} ${call.price}원")
        }

        // v3.23: 계기판 철학 — 근거형 메시지 출력
        val evidenceMsg = OutputController.buildMessage(enrichedCall, result)
        val outputMode = OutputController.determineMode(enrichedCall)

        if (shouldSpeak && evidenceMsg != null) {
            lastSpeakTime = now
            ttsActuallySpoken = true
            Log.d("DeliveryFilter", "근거 출력: ${call.price}원 → \"$evidenceMsg\"")
            OtwFileLogger.log("DeliveryFilter", "근거 출력: ${call.price}원 → \"$evidenceMsg\"")

            // 연속 REJECT 기준 하향 (내부 카운터 업데이트만, TTS 메시지 제거)
            CallFilter.updateRejectStreak(result.verdict, this)
            consecutiveRejectCount = CallFilter.getConsecutiveRejectCount()

        } else if (!shouldSpeak) {
            // TtsDeduplicator 중복
        } else {
            // 근거 없음 → SILENT
            OtwFileLogger.log("DeliveryFilter", "근거 없음 → SILENT: ${call.price}원")
        }

        // v3.26: AI 보조 (애매 구간만, 개발자 모드)
        AiAssist.assist(this, enrichedCall)

        // 오버레이 카드 클릭 피드백용 콜 정보 저장
        CardOverlay.setLastCall(call.platform, call.price)

        // OutputController 통합 (TTS + Overlay)
        OutputController.emit(
            ctx = this,
            ttsText = evidenceMsg,
            overlayText = evidenceMsg ?: "${java.text.NumberFormat.getNumberInstance().format(call.price)}원",
            mode = if (evidenceMsg != null) outputMode else OutputMode.OVERLAY_ONLY,
            tts = tts,
            ttsReady = ttsReady,
            pricePerKm = if (unitPrice > 0) unitPrice else null
        )

        // [Hotfix-2 P0-3] DB INSERT를 executor에 위임 (메인 스레드 블로킹 방지)
        val ctx = this
        val dbPlatform = call.platform; val dbPrice = call.price
        val dbDistance = enrichedCall.distance; val dbPoint = baeminPoint
        val dbVerdict = result.verdict.name; val dbReason = result.reason
        val dbBundleCount = call.bundleCount; val dbIsMultiPickup = call.isMultiPickup
        val dbStoreName = call.storeName; val dbDestination = call.destination
        val dbPickupKm = pickupDistKm; val dbTtsSuppressed = !ttsActuallySpoken
        val dbSourceType = V2Event.mapSourceType(call.platform)
        val dbParsingMethod = call.parsingMethod
        val dbDriverAction = when (lastDeliveryVerdict) {
            "추천", "보통" -> "simulated_accept"
            "비추천" -> "simulated_reject"
            else -> "unknown"
        }
        val dbTotalKm = (dbPickupKm ?: 0.0) + (dbDistance ?: 0.0)
        val dbUp = if (dbTotalKm > 0) (dbPrice / dbTotalKm).toInt() else 0
        val dbSessionId = DrivingModeManager.getSessionId(ctx)
        dbExecutor.execute {
            try {
                CallLogDb.get(ctx).insert(
                    platform = dbPlatform, price = dbPrice,
                    distance = dbDistance, unitPrice = dbUp,
                    point = dbPoint, verdict = dbVerdict,
                    reason = dbReason, bundleCount = dbBundleCount,
                    isMultiPickup = dbIsMultiPickup, storeName = dbStoreName,
                    destination = dbDestination, pickupKm = dbPickupKm,
                    ttsSuppressed = dbTtsSuppressed,
                    sourceType = dbSourceType,
                    parsingMethod = dbParsingMethod,
                    driverAction = dbDriverAction,
                    sessionId = dbSessionId
                )
                // 쿠팡: Accessibility에서 가게명 있으면 NLS 레코드도 업데이트
                if (dbPlatform == "coupang" && dbStoreName.isNotBlank()) {
                    CallLogDb.get(ctx).updateStoreNameIfEmpty("coupang", dbPrice, dbStoreName)
                }
            } catch (e: Exception) { Log.w("DeliveryFilter", "DB 저장 실패: ${e.message}") }
        }

        playCallSound(lastDeliveryVerdict)
        vibrate(lastDeliveryVerdict)
        updateNotification()
        checkGoalProgress()
        DailyReport.onCallDetected(this)

        // 판정-행동 매칭: PENDING 이벤트 생성 + 30s 타임아웃
        try {
            JudgmentMatchLogger.onJudgmentIssued(
                ctx = this,
                platform = call.platform,
                price = call.price,
                distanceKm = enrichedCall.distance,
                storeName = call.storeName,
                verdict = lastDeliveryVerdict,
                reason = result.reason,
                sessionId = callSessionEvt?.eventId
            )
        } catch (_: Exception) {}
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

    /** 외부에서 TTS 호출 */
    fun speakTtsPublic(text: String) = speakTts(text)

    /** GPS 상태 운행 모드와 동기화 (외부 호출용) */
    fun syncGpsWithDrivingMode() {
        if (DrivingModeManager.getMode(this) == DrivingMode.DRIVING) {
            if (!gpsActive) startGps()
        } else {
            if (gpsActive) stopGps()
        }
    }

    /** 픽업 거리 검증: 직선거리 → 도로 보정 → 범위 필터 (0.05~10km) */
    private fun validatePickupDistance(straightKm: Double?): Double? {
        if (straightKm == null) return null
        val road = straightKm * ROAD_DISTANCE_FACTOR
        if (road < 0.05 || road > 10.0) {
            OtwFileLogger.log("DistanceFilter", "비정상 거리: ${"%.2f".format(road)}km (직선 ${"%.2f".format(straightKm)}km) → null")
            return null
        }
        return road
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
        PerfTrace.mark("TTS", "tts_spoken")
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
        try { ProximityDetector.clearTarget() } catch (_: Exception) {}

        // FIX-TTS-DELIVERY-FLOW: 시나리오 D - 다음 배달지 안내 (기존 earnings TTS 대체)
        try { DeliveryFlowManager.onDeliveryComplete() } catch (_: Exception) {}

        if (!AdvancedPrefs.isDeliveryCompleteEnabled(this)) return
        val earnings = EarningsTracker.getToday(this)
        speakTts("완료, ${earnings.acceptedCount}건, ${toKoreanNumber(earnings.totalRevenue)}원")
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
                "추천" -> resources.getIdentifier("sound_grab", "raw", packageName)
                "보통" -> resources.getIdentifier("sound_ok", "raw", packageName)
                "비추천" -> resources.getIdentifier("sound_skip", "raw", packageName)
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
            speakTts("목표 달성! 오늘 수고 많으셨어요")
        } else if (progress >= 0.5f && !GoalManager.wasHalfAlerted(this)) {
            GoalManager.markHalfAlerted(this)
            speakTts("절반 왔습니다")
        }
    }

    /** [Hotfix-2 P0-1] AccessibilityService 윈도우 추적 강제 갱신 */
    private fun refreshAccessibilityConnection(triggerPkg: String) {
        try {
            val info = serviceInfo
            if (info == null) {
                Log.e(TAG_ACCESSIBILITY, "serviceInfo null, 재할당 불가: trigger=$triggerPkg")
                OtwFileLogger.log(TAG_ACCESSIBILITY, "serviceInfo null, 재할당 불가: trigger=$triggerPkg")
                return
            }
            val triggerMsg = "재연결 트리거 발동: pkg=$triggerPkg, count=${refreshCount + 1}"
            Log.d(TAG_ACCESSIBILITY, triggerMsg)
            OtwFileLogger.log(TAG_ACCESSIBILITY, triggerMsg)
            serviceInfo = info
            refreshCount++
            val rootCheck = rootInActiveWindow
            val resultMsg = "재연결 결과: pkg=$triggerPkg, root=${if (rootCheck != null) "OK" else "NULL"}, totalCount=$refreshCount"
            Log.d(TAG_ACCESSIBILITY, resultMsg)
            OtwFileLogger.log(TAG_ACCESSIBILITY, resultMsg)
        } catch (e: Exception) {
            Log.e(TAG_ACCESSIBILITY, "재연결 실패: pkg=$triggerPkg", e)
            OtwFileLogger.log(TAG_ACCESSIBILITY, "재연결 실패: pkg=$triggerPkg, ${e.message}")
        }
    }

    /** [Hotfix-2 P0-1] 윈도우 이벤트 Ring buffer 기록 */
    private fun recordWindowEvent(pkg: String, eventType: Int) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
        val eventName = when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT"
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
            else -> "OTHER_$eventType"
        }
        val entry = "[$timestamp] $eventName $pkg"
        synchronized(windowEventRing) {
            windowEventRing.add(entry)
            if (windowEventRing.size > WINDOW_EVENT_RING_SIZE) {
                windowEventRing.removeAt(0)
            }
        }
        Log.d(TAG_ACCESSIBILITY, entry)
        OtwFileLogger.log(TAG_ACCESSIBILITY, entry)
    }

    /** [Hotfix-2 P0-1] Ring buffer 덤프 (외부 호출용) */
    fun dumpWindowEventRing(): List<String> {
        return synchronized(windowEventRing) { windowEventRing.toList() }
    }

    override fun onInterrupt() {
        Log.w(TAG_ACCESSIBILITY, "onInterrupt: 접근성 서비스 중단됨")
        try { OtwFileLogger.logSync(TAG_ACCESSIBILITY, "onInterrupt: instance → null") } catch (_: Exception) {}
        try { CriticalEventDb.get(this).record("SVC_DISC", "onInterrupt") } catch (_: Exception) {}
        instance = null
    }
    override fun onServiceConnected() {
        serviceStartTime = System.currentTimeMillis()
        serviceStartUptime = android.os.SystemClock.uptimeMillis()
        Log.d(TAG_ACCESSIBILITY, "onServiceConnected: 접근성 서비스 연결됨, refreshCount 리셋")
        OtwFileLogger.logSync(TAG_ACCESSIBILITY, "onServiceConnected: 접근성 서비스 연결됨, refreshCount 리셋")
        try { CriticalEventDb.get(this).record("SVC_CONN", "onServiceConnected") } catch (_: Exception) {}
        refreshCount = 0
        lastRefreshTime = 0L
        instance = this
        try { OtwFileLogger.init(this) } catch (_: Exception) {}
        try { FeatureFlags.load(this) } catch (e: Exception) { Log.w("OnTheWay", "FeatureFlags 로드 실패: ${e.message}") }
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
        // 운행 시간 날짜 자동 리셋 (자정 넘어서 앱 재시작 대응)
        try { DrivingModeManager.checkAndResetDate(this) } catch (_: Exception) {}
        // [Hotfix-1] DrivingMode 상태 복구 (프로세스 kill 후 SP=DRIVING + isTracking=false 불일치 대응)
        try {
            if (DrivingModeManager.getMode(this) == DrivingMode.DRIVING && !LocationTracker.isActive()) {
                Log.d("OTW_DRIVING_MODE", "프로세스 재시작 감지 → GPS 수집 재개")
                LocationTracker.startTracking(this)
            }
        } catch (e: Exception) { Log.w("OTW_DRIVING_MODE", "DrivingMode 복구 실패: ${e.message}") }
        try { CallLogDb.get(this).cleanup() } catch (e: Exception) { Log.w("OnTheWay", "DB 정리 실패: ${e.message}") }
        try { DiagnosticLog.init(this) } catch (e: Exception) { Log.w("OnTheWay", "DiagnosticLog 초기화 실패: ${e.message}") }
        try { com.vita.ontheway.diagnostic.CoupangDiagnosticLogger.init(this) } catch (e: Exception) { Log.w("OnTheWay", "CoupangDiagnosticLogger 초기화 실패: ${e.message}") }
        try { com.vita.ontheway.diagnostic.BaeminDiagnosticLogger.init(this) } catch (e: Exception) { Log.w("OnTheWay", "BaeminDiagnosticLogger 초기화 실패: ${e.message}") }
        try {
            val transitionLog = StateTransitionLog(this)
            sessionManager = SessionManager(transitionLog)
            // 3일 이상 된 전이 로그 정리
            transitionLog.clearOld(System.currentTimeMillis() - 3 * 24 * 60 * 60 * 1000L)
        } catch (e: Exception) { Log.w("OnTheWay", "SessionManager 초기화 실패: ${e.message}") }
        // v3.22: Phase 1 계산 엔진 초기화
        try { ReturnTimeEstimator.restore(this) } catch (_: Exception) {}
        try { startStatusAlertLoop() } catch (_: Exception) {}

        // Supabase 미전송 데이터 자동 sync
        try { SupabaseSync.syncPending(this) } catch (_: Exception) {}

        // GPS 근접 TTS 리스너 설정
        try { setupProximityListener() } catch (_: Exception) {}

        // FIX-TTS-DELIVERY-FLOW: 배달 흐름 TTS 콜백 설정
        DeliveryFlowManager.speakCallback = { msg -> speakTts(msg) }

        Log.d("OnTheWay", "OnTheWay 서비스 시작")
    }

    // v3.22: StatusAlertEngine 1분 주기 루프 + 자동 OFF 체크
    private var statusAlertRunnable: Runnable? = null
    private val AUTO_OFF_IDLE_MS = 30 * 60 * 1000L  // 30분 무콜

    private fun startStatusAlertLoop() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        statusAlertRunnable = object : Runnable {
            override fun run() {
                try { StatusAlertEngine.checkAndAlert(this@OnTheWayService) } catch (_: Exception) {}
                try { checkAutoOff() } catch (_: Exception) {}
                handler.postDelayed(this, 60_000)
            }
        }
        handler.postDelayed(statusAlertRunnable!!, 60_000)
    }

    // 마지막 accessibility 경로 콜 감지 (NLS 제외, 자동 OFF 판정용)
    private var lastAccessibilityCallTime: Long = System.currentTimeMillis()

    /** 30분 무콜(accessibility) + 화면 OFF → 운행 자동 OFF */
    private fun checkAutoOff() {
        if (DrivingModeManager.getMode(this) != DrivingMode.DRIVING) return
        val now = System.currentTimeMillis()
        val idle = now - lastAccessibilityCallTime
        val screenOn = (getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager)?.isInteractive == true
        if (idle >= AUTO_OFF_IDLE_MS && !screenOn) {
            DrivingModeManager.setMode(this, DrivingMode.IDLE, "auto_idle_no_call_screen_off")
            stopGps()
            OtwFileLogger.log("DrivingMode", "자동 OFF: ${idle / 60000}분 무콜 + 화면 OFF + GPS 종료")
        }
    }

    private var locationManager: LocationManager? = null
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            currentLat = loc.latitude
            currentLng = loc.longitude
            currentSpeed = loc.speed
            gpsActive = true
            Log.d("OTW_GPS", "위치: $currentLat, $currentLng, 속도: ${currentSpeed}m/s")
            try { ProximityDetector.onLocationUpdate(loc.latitude, loc.longitude) } catch (_: Exception) {}
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { gpsActive = false }
    }

    private fun startGps() {
        if (!AdvancedPrefs.isGpsEnabled(this)) return
        if (gpsActive) return  // 중복 방지
        try {
            locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 30000L, 50f, locationListener
                )
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 30000L, 50f, locationListener
                )
                gpsActive = true
                Log.d("OTW_GPS", "GPS 시작")
                OtwFileLogger.log("OTW_GPS", "서비스 GPS 시작 — requestLocationUpdates")
            } else {
                Log.w("OTW_GPS", "위치 권한 없음 - GPS 비활성화")
                gpsActive = false
            }
        } catch (e: Exception) {
            Log.w("OTW_GPS", "GPS 시작 실패: ${e.message}")
            gpsActive = false
        }
    }

    /** 서비스 GPS 종료 — 운행 OFF 시 호출 */
    private fun stopGps() {
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        gpsActive = false
        OtwFileLogger.log("OTW_GPS", "서비스 GPS 종료 — removeUpdates")
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
        val isDriving = DrivingModeManager.getMode(this) == DrivingMode.DRIVING
        val hourly = if (isDriving) earnings.hourlyRate else 0
        val hourlyEmoji = when { hourly >= 20000 -> "🟢"; hourly >= 18000 -> "🟡"; hourly > 0 -> "🔴"; else -> "" }
        val hourlyStr = if (isDriving && hourly > 0) "$hourlyEmoji ${fmt.format(hourly)}원/h" else "—원/h"
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
                "추천" -> {
                    val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                }
                "보통" -> {
                    vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        } catch (e: Exception) {
            Log.w("OnTheWay", "진동 실패: ${e.message}")
        }
    }

    // ── GPS 근접 TTS ──

    private fun setupProximityListener() {
        ProximityDetector.listener = { event, target ->
            when (event) {
                ProximityDetector.ProximityEvent.PICKUP_NEAR -> {
                    if (FeatureFlags.ttsPreset.ordinal >= TtsPreset.HIGH.ordinal) {
                        speakTts("픽업 도착")
                        OtwFileLogger.log("ProximityTTS", "PICKUP_NEAR TTS: 픽업 도착 (${target.storeName})")
                    }
                }
                ProximityDetector.ProximityEvent.DELIVERY_NEAR -> {
                    if (FeatureFlags.ttsPreset.ordinal >= TtsPreset.MEDIUM.ordinal) {
                        // FIX-TTS-DELIVERY-FLOW: DeliveryFlowManager 경유 (시나리오 B + C 리마인더)
                        try { DeliveryFlowManager.onDeliveryNear() } catch (_: Exception) {}
                        OtwFileLogger.log("ProximityTTS", "DELIVERY_NEAR → DeliveryFlow (${target.storeName})")
                    }
                }
            }
        }
    }

    private fun setProximityTarget(call: DeliveryCall, platform: String) {
        val callKey = "${platform}_${call.price}_${System.currentTimeMillis()}"

        // 주소 → 좌표 변환 (LocationTable 룩업)
        val pickupCoord = KakaoGeocoder.findCoord(this, call.storeName)
        val deliveryCoord = KakaoGeocoder.findCoord(this, call.destination)

        if (pickupCoord == null && deliveryCoord == null) {
            OtwFileLogger.log("ProximityTTS", "좌표 변환 실패 (pickup=${call.storeName}, delivery=${call.destination})")
            return
        }

        // 멀티콜 두번째 가게명 추출
        val nextStore = if (call.isMulti && call.storeName.contains("+")) {
            call.storeName.split("+").getOrNull(1)?.trim()
        } else null

        val target = ProximityDetector.Target(
            callKey = callKey,
            pickupLat = pickupCoord?.lat,
            pickupLng = pickupCoord?.lng,
            deliveryLat = deliveryCoord?.lat,
            deliveryLng = deliveryCoord?.lng,
            storeName = call.storeName,
            customerRequest = lastCustomerRequest,
            nextStoreName = nextStore
        )
        ProximityDetector.setTarget(target)
    }

    override fun onDestroy() {
        try { OtwFileLogger.logSync(TAG_ACCESSIBILITY, "onDestroy: 서비스 종료") } catch (_: Exception) {}
        try { CriticalEventDb.get(this).record("SVC_DISC", "onDestroy") } catch (_: Exception) {}
        try { ProximityDetector.clearTarget() } catch (_: Exception) {}
        ProximityDetector.listener = null
        try { tts?.shutdown() } catch (e: Exception) {}
        tts = null
        ttsReady = false
        try { locationManager?.removeUpdates(locationListener) } catch (e: Exception) {}
        gpsActive = false
        try { CardOverlay.hide() } catch (e: Exception) {}
        BaeminBundleSession.reset()
        bundleTimeoutRunnable?.let { debounceHandler.removeCallbacks(it) }
        DeliveryFlowManager.clearState()
        DeliveryFlowManager.speakCallback = null
        try { dbExecutor.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}
