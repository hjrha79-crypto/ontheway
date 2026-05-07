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

    private var serviceStartTime: Long = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        val now = System.currentTimeMillis()
        serviceStartTime = now
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
        OtwFileLogger.logSync(TAG_LIFECYCLE, "NLS disconnected, requesting rebind")
        try { CriticalEventDb.get(this).record("NLS_DISC", "onListenerDisconnected") } catch (_: Exception) {}
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

        // v3.22: 운행 모드 OFF 절전 — 배민/쿠팡 콜 알림은 절전 예외
        if (DrivingModeManager.getMode(this) != DrivingMode.DRIVING && !FeatureFlags.backgroundCapture) {
            if (pkg != PKG_BAEMIN && pkg != PKG_COUPANG) {
                return
            }
        }

        if (pkg !in TARGET_PACKAGES) {
            DropReason.recordDrop(DropReason.DROP_PACKAGE_FILTER, "notification non-target", pkg)
            return
        }

        // 서비스 시작 5초 이전에 게시된 알림 DROP (재시작 시 오래된 큐 방지)
        if (serviceStartTime > 0 && sbn.postTime < serviceStartTime - 5_000) {
            DropReason.recordDrop(DropReason.DROP_STALE, "postTime=${sbn.postTime} < startTime=$serviceStartTime", pkg)
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
            PKG_BAEMIN -> parseBaeminNotification(combined, title, text)
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
            // FIX-NLS-CROSS-SOURCE-DEDUP + PP-GUARD: cross-source dedup
            if (CrossSourceDedup.isProcessed(
                    orderId = call.orderId, platform = call.platform,
                    price = call.price, storeName = call.storeName,
                    source = CrossSourceDedup.SOURCE_NLS)) {
                Log.d("DeliveryNoti", "CrossSourceDedup → 알림 스킵: ${call.platform} ${call.price}원")
                DropReason.recordDrop(DropReason.DROP_DUPLICATE, "cross_source_dedup ${call.platform}_${call.price}")
                continue
            }
            // 기존 TtsDeduplicator fallback (하위 호환)
            if (TtsDeduplicator.wasProcessedWithin(call.platform, call.price)) {
                Log.d("DeliveryNoti", "TtsDedup → 알림 스킵: ${call.platform} ${call.price}원")
                DropReason.recordDrop(DropReason.DROP_DUPLICATE, "notification wasProcessed ${call.platform}_${call.price}")
                continue
            }
            // 쿠팡: Accessibility가 10초 이내 TTS 발화했으면 스킵 (하위 호환)
            if (pkg == PKG_COUPANG && TtsDeduplicator.wasSpokenWithin("coupang", call.price, 10_000)) {
                Log.d("DeliveryNoti", "쿠팡 Accessibility TTS 우선 - 알림 스킵: ${call.price}원")
                continue
            }
            // v3.18: SessionManager 경유
            val session = sessionManager?.onEventReceived(
                call.platform, call.storeName, call.price, "notification_posted"
            )

            // FIX-PICKUP-DISTANCE: NLS 경로에서도 픽업 거리 계산
            val enrichedCall = enrichWithPickupDistance(call)

            val result = CallFilter.judge(enrichedCall, this)
            TtsDeduplicator.recordProcessed(enrichedCall.platform, enrichedCall.price)
            // FIX-NLS-CROSS-SOURCE-DEDUP: NLS 처리 완료 등록
            CrossSourceDedup.markProcessed(
                eventId = session?.eventId, orderId = enrichedCall.orderId,
                platform = enrichedCall.platform, price = enrichedCall.price,
                storeName = enrichedCall.storeName,
                source = CrossSourceDedup.SOURCE_NLS)
            Log.d("DeliveryNoti", "파싱 결과: price=${enrichedCall.price}, result=${result.verdict} (${result.reason})")
            FilterLog.record(this, enrichedCall, result, eventId = session?.eventId, sessionState = session?.state?.name)

            // Bug 3 fix: CallLogDb에도 기록 (UserModeActivity 표시용)
            val ctx = this
            val notiPlatform = enrichedCall.platform; val notiPrice = enrichedCall.price
            val notiDist = enrichedCall.distance; val notiVerdict = result.verdict.name
            val notiReason = result.reason; val notiStore = enrichedCall.storeName
            val notiDest = enrichedCall.destination; val notiBundleCount = enrichedCall.bundleCount
            val notiIsMultiPickup = enrichedCall.isMultiPickup
            val notiPickupKm = enrichedCall.pickupDistanceKm
            val notiTotalKm = (notiPickupKm ?: 0.0) + (notiDist ?: 0.0)
            val notiUp = if (notiTotalKm > 0) (notiPrice / notiTotalKm).toInt() else 0
            ioExecutor.execute {
                try {
                    CallLogDb.get(ctx).insert(
                        platform = notiPlatform, price = notiPrice,
                        distance = notiDist, unitPrice = notiUp,
                        point = null, verdict = notiVerdict,
                        reason = notiReason, bundleCount = notiBundleCount,
                        isMultiPickup = notiIsMultiPickup, storeName = notiStore,
                        destination = notiDest,
                        sourceType = V2Event.mapSourceType(notiPlatform),
                        parsingMethod = V2Event.PARSING_NOTIFICATION,
                        pickupKm = notiPickupKm
                    )
                } catch (e: Exception) { Log.w("DeliveryNoti", "DB 저장 실패: ${e.message}") }
            }

            // 쿠팡은 알림 = 즉시 finalize
            if (enrichedCall.platform == "coupang") {
                sessionManager?.finalizeActiveSession("notification_complete")
            }

            // OnTheWayService의 lastCallDetectedTime도 갱신
            OnTheWayService.instance?.lastCallDetectedTime = now

            if (!TtsDeduplicator.shouldSpeak(enrichedCall.platform, enrichedCall.price)) {
                Log.d("DeliveryNoti", "TtsDeduplicator 중복 스킵: ${enrichedCall.platform} ${enrichedCall.price}원")
                continue
            }

            // v3.23: 계기판 철학 — 근거형 메시지
            val evidenceMsg = OutputController.buildMessage(enrichedCall, result)
            val outputMode = OutputController.determineMode(enrichedCall)
            Log.d("DeliveryNoti", "근거 출력: ${enrichedCall.price}원 → \"${evidenceMsg ?: "SILENT"}\"")

            CardOverlay.setLastCall(enrichedCall.platform, enrichedCall.price)
            OutputController.emit(
                ctx = this,
                ttsText = evidenceMsg,
                overlayText = evidenceMsg ?: "${java.text.NumberFormat.getNumberInstance().format(enrichedCall.price)}원",
                mode = if (evidenceMsg != null) outputMode else OutputMode.OVERLAY_ONLY,
                tts = tts,
                ttsReady = ttsReady
            )
        }

        // 오래된 처리 기록 정리
        processedNotifs.entries.removeIf { now - it.value > 60_000 }

        // FIX-SELFPING: 배민/쿠팡 알림 처리 후 accessibility health check
        checkAccessibilityHealth(now)
    }

    /**
     * FIX-PICKUP-DISTANCE: NLS 경로에서도 픽업 거리 계산.
     * OnTheWayService의 GPS 좌표 + KakaoGeocoder 활용.
     */
    private fun enrichWithPickupDistance(call: DeliveryCall): DeliveryCall {
        // FIX-NLS-DISTANCE-V2: NLS 거리(총거리)와 GPS 픽업거리는 별도 필드
        // NLS distance = call.distance (단가 판정용, 총거리)
        // GPS pickup = call.pickupDistanceKm (UI/TTS 픽업 표시용)
        // → 둘 다 계산, skip 하지 않음

        try {
            val lat = OnTheWayService.currentLat
            val lng = OnTheWayService.currentLng
            if (lat == 0.0 || lng == 0.0) return call

            val addr = call.storeName.ifEmpty { call.destination }
            if (addr.isBlank()) return call

            val straight = KakaoGeocoder.distanceTo(this, lat, lng, addr) ?: return call
            val road = straight * OnTheWayService.ROAD_DISTANCE_FACTOR
            // 동일 필터: 50m~10km 범위만 유효
            if (road < 0.05 || road > 10.0) return call

            Log.d("DeliveryNoti", "GPS 픽업 거리: $addr → ${"%.1f".format(road)}km")
            return call.copy(pickupDistanceKm = road)
        } catch (e: Exception) {
            Log.w("DeliveryNoti", "GPS 픽업 거리 계산 실패: ${e.message}")
        }
        return call
    }

    /**
     * FIX-SELFPING: Accessibility 서비스 health check.
     * 배민/쿠팡 알림 수신 직후 호출 — 서비스 인스턴스 사망 감지 시 TTS 1회 안내.
     */
    private var lastHealthAlertTime: Long = 0L
    private fun checkAccessibilityHealth(now: Long) {
        // 운행 모드 아니면 체크 불필요
        if (DrivingModeManager.getMode(this) != DrivingMode.DRIVING) return

        val health = AccessibilityHealthMonitor.check(
            instanceAlive = OnTheWayService.instance != null,
            lastEventTimeMs = OnTheWayService.lastAccessibilityEventTime,
            nowMs = now
        )

        if (health.status == AccessibilityHealthMonitor.Status.ALIVE) return

        // 5분 내 중복 알림 방지
        if (now - lastHealthAlertTime < 300_000L) return
        lastHealthAlertTime = now

        Log.w("A11yHealth", "NLS health check: ${health.status} - ${health.message}")
        OtwFileLogger.logSync("A11yHealth", "NLS 감지: ${health.status} - ${health.message}")
        try { CriticalEventDb.get(this).record("ACCESSIBILITY_DEAD", null, health.message) } catch (_: Exception) {}

        // TTS 안내 1회
        if (ttsReady) {
            speakTts("온더웨이 접근성 연결을 확인해 주세요")
        }
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
    // FIX-COUPANG-MULTI: 알림 텍스트에서 멀티 키워드 진단
    private val NLS_MULTI_KEYWORDS = listOf("멀티", "두 건", "묶음", "2건", "3건")

    private fun parseCoupangNotification(text: String): List<DeliveryCall> {
        // FIX-COUPANG-MULTI: 멀티 키워드 감지 시 진단 로그
        val multiHits = NLS_MULTI_KEYWORDS.filter { text.contains(it) }
        if (multiHits.isNotEmpty()) {
            OtwFileLogger.logSync("CoupangNLS_MULTI", "멀티 키워드 감지: ${multiHits.joinToString(",")} | raw=${text.take(200)}")
        }
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

        // FIX-STORE-NAME-V2: 쿠팡 알림에서 가게명 추출
        // "멀티 [가게명] [가격]원" 또는 "[가게명] [가격]원" 패턴
        val storeName = extractCoupangStoreFromNotif(text, price)

        return listOf(DeliveryCall(
            price = price, distance = distance, isMulti = isMulti,
            platform = "coupang", rawText = text,
            storeName = storeName,
            parsingMethod = V2Event.PARSING_NOTIFICATION
        ))
    }

    /** FIX-STORE-NAME-V2: 쿠팡 알림에서 가게명 추출 */
    private fun extractCoupangStoreFromNotif(text: String, price: Int): String {
        // "멀티 [가게명] X,XXX원" 패턴: 가격 직전 한글 토큰
        val priceStr = java.text.NumberFormat.getNumberInstance().format(price) + "원"
        val idx = text.indexOf(priceStr)
        if (idx < 0) {
            // comma 없는 형태도 시도
            val idx2 = text.indexOf("${price}원")
            if (idx2 > 0) {
                val before = text.substring(0, idx2).trim()
                return extractLastStoreToken(before)
            }
            return ""
        }
        val before = text.substring(0, idx).trim()
        return extractLastStoreToken(before)
    }

    private fun extractLastStoreToken(before: String): String {
        // "멀티 페리카나 오포점" → "페리카나 오포점"
        // 끝에서 한글+영문+숫자+공백+특수문자로 된 가게명 추출
        val candidate = before
            .replace(Regex("^(멀티|단일|일반|대량 주문,?)\\s*"), "")
            .trim()
        if (candidate.length in 2..30 &&
            Regex("[가-힣a-zA-Z]").containsMatchIn(candidate) &&
            !candidate.contains("거리할증") && !candidate.contains("지원금")) {
            Log.d("DeliveryNoti", "쿠팡 알림 가게명: '$candidate'")
            return candidate
        }
        return ""
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
    // FIX-STORE-NAME-V2: 배민 알림에서 가게명 추출 패턴
    private val BAEMIN_NOTIF_STORE_PATTERN = Regex("""픽업지\s*[:\s]*([가-힣a-zA-Z0-9\s&/·\-.(),']{2,30})""")

    private fun parseBaeminNotification(text: String, title: String = "", body: String = ""): List<DeliveryCall> {
        // FIX-STORE-NAME-V2: 배민 알림 원문 로깅 (coupang과 동일)
        logBaeminNotifRaw(text)

        // "배달료 7,010원" 또는 "7,010원"
        val priceMatch = Regex("(?:배달료\\s*)?([\\d,]+)\\s*원").find(text) ?: return emptyList()
        val price = priceMatch.groupValues[1].replace(",", "").toIntOrNull() ?: return emptyList()
        if (price !in 500..100000) return emptyList()

        // FIX-STORE-NAME-V2: 알림 텍스트에서 가게명 추출 (title 우선)
        val storeName = extractStoreFromNotification(text, title)

        // FIX-NLS-DISTANCE: 알림 텍스트에서 거리 추출 (배민 자체 표기, 100% 정확)
        val nlsDistance = BaeminParser.parseNlsDistance(text)
        if (nlsDistance != null) {
            Log.d("DeliveryNoti", "배민 NLS 거리: ${nlsDistance}km")
        }

        // 묶음 감지: "[N건 묶음]" 패턴
        val isMulti = text.contains("묶음")

        // FIX-NLS-ORDERID: 알림 텍스트에서 orderId 추출 시도
        val orderId = BaeminParser.parseNlsOrderId(text)

        return listOf(DeliveryCall(
            price = price, distance = nlsDistance, isMulti = isMulti,
            platform = "baemin", rawText = text,
            storeName = storeName,
            orderId = orderId,
            parsingMethod = V2Event.PARSING_NOTIFICATION
        ))
    }

    /** FIX-STORE-NAME-V2: 배민 알림 텍스트에서 가게명 추출 */
    private fun extractStoreFromNotification(text: String, title: String = ""): String {
        // 1. "픽업지: [가게명]" 또는 "픽업지 [가게명]" 패턴
        val match = BAEMIN_NOTIF_STORE_PATTERN.find(text)
        if (match != null) {
            val candidate = match.groupValues[1].trim()
            if (candidate.isNotBlank() && !BaeminParser.isBlacklistedPattern(candidate)) {
                Log.d("DeliveryNoti", "배민 알림 가게명(픽업지): '$candidate'")
                return candidate
            }
        }
        // 2. title이 가게명일 수 있음 (배민 알림: title="가게명", text="배달료 X원")
        if (title.isNotBlank() && title.length in 2..30 &&
            !title.contains("배민") && !title.contains("알림") &&
            !title.contains("원") && !BaeminParser.isBlacklistedPattern(title) &&
            Regex("[가-힣a-zA-Z]").containsMatchIn(title)) {
            Log.d("DeliveryNoti", "배민 알림 가게명(title): '$title'")
            return title
        }
        return ""
    }

    /** FIX-STORE-NAME-V2: 배민 알림 원문 로깅 */
    private fun logBaeminNotifRaw(text: String) {
        val entryStr = try {
            JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("pkg", PKG_BAEMIN)
                put("text", text)
            }.toString()
        } catch (e: Exception) { return }
        val dir = filesDir
        ioExecutor.execute {
            try {
                val file = File(dir, "baemin_notif_raw.jsonl")
                file.appendText(entryStr + "\n")
                val lines = file.readLines()
                if (lines.size > 200) {
                    file.writeText(lines.takeLast(200).joinToString("\n") + "\n")
                }
            } catch (_: Exception) {}
        }
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
