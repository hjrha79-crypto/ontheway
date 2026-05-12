package com.vita.ontheway

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 수락 감지 통합 조정기.
 *
 * Fix W 보강: 2개 진입점
 * - handleAccept(): 기존 호출처 호환 (candidate + 즉시 confirmed)
 * - recordAcceptCandidate(): 신규 호출처 (candidate만, 60초 grace 후 확정)
 *
 * ACCEPT 통계 dedup 전용. 콜 감지 NLS/A11Y dedup은 CrossSourceCallDetectionDedup 담당.
 */
object AcceptCoordinator {

    private const val TAG = "AcceptCoordinator"

    enum class AcceptSource {
        SYSTEM_NOTI, COUPANG_PICKUP, BAEMIN_PROGRESS, FALLBACK, CLICK
    }

    // ── In-memory accepted session cache (LRU-style) ──
    private const val CACHE_MAX_SIZE = 1000
    private val acceptedSessionCache = ConcurrentHashMap<String, Long>()

    // Fix Y v2: stale pending check — CALL_DETECTED → ACCEPT 시간 갭 5분 초과 시 차단
    private const val STALE_PENDING_THRESHOLD_MS = 5 * 60 * 1000L
    private val lastCallDetectedPerPlatform = ConcurrentHashMap<String, Long>()

    // Fix U: 중복 차단 ledger rate-limit (60초 cooldown per key)
    private const val DUPLICATE_LOG_COOLDOWN_MS = 60_000L
    private const val DUPLICATE_LOG_EXPIRE_MS = 3_600_000L
    private val duplicateLogTracker = ConcurrentHashMap<String, Long>()
    internal var timeSource: (() -> Long) = { System.currentTimeMillis() }

    internal fun shouldLogDuplicate(key: String): Boolean {
        val now = timeSource()
        duplicateLogTracker.entries.removeIf { now - it.value > DUPLICATE_LOG_EXPIRE_MS }
        val last = duplicateLogTracker[key] ?: 0
        if (now - last > DUPLICATE_LOG_COOLDOWN_MS) {
            duplicateLogTracker[key] = now
            return true
        }
        return false
    }

    /**
     * Fix Y v2: CALL_DETECTED 시각 기록.
     * OnTheWayService / DeliveryNotificationService에서 콜 감지 시 호출.
     */
    fun recordCallDetected(platform: String, ts: Long = timeSource()) {
        lastCallDetectedPerPlatform[platform] = ts
    }

    /** Fix Y v2: 테스트용 — 특정 platform의 마지막 CALL_DETECTED 시각 */
    internal fun getLastCallDetectedMs(platform: String): Long =
        lastCallDetectedPerPlatform[platform] ?: 0L

    // 테스트 주입용
    internal var ledgerChecker: ((Context, String) -> Boolean)? = null
    internal var ledgerSyncWriter: ((Context, String, String?, String?, String,
        com.vita.ontheway.ledger.LedgerEventType, String, String?) -> Unit)? = null

    /**
     * 하위 호환 진입점: candidate + 즉시 confirmed.
     * 기존 호출처(CoupangAcceptDetector, OnTheWayService 등)에서 사용.
     */
    fun handleAccept(
        ctx: Context, source: AcceptSource,
        price: Int, platform: String,
        storeName: String = "",
        eventId: String = "",
        orderId: String = "",
        explicitIdentityConf: Double = -1.0,
        explicitPriceSource: String = ""
    ) {
        doAccept(ctx, source, price, platform, storeName, eventId, orderId,
            explicitIdentityConf, explicitPriceSource, immediateConfirm = true)
    }

    /**
     * 신규 진입점: candidate만 등록, 60초 grace 후 자동 확정/미확정.
     * EarningsTracker 호출 X (CONFIRMED 전이 시에만).
     * ACCEPT_CANDIDATE ledger만 기록.
     */
    fun recordAcceptCandidate(
        ctx: Context, source: AcceptSource,
        price: Int, platform: String,
        storeName: String = "",
        eventId: String = "",
        orderId: String = "",
        explicitIdentityConf: Double = -1.0,
        explicitPriceSource: String = ""
    ) {
        doAccept(ctx, source, price, platform, storeName, eventId, orderId,
            explicitIdentityConf, explicitPriceSource, immediateConfirm = false)
    }

    private fun doAccept(
        ctx: Context, source: AcceptSource,
        price: Int, platform: String,
        storeName: String, eventId: String, orderId: String,
        explicitIdentityConf: Double, explicitPriceSource: String,
        immediateConfirm: Boolean
    ) {
        if (price <= 0) return

        // Fix Y v2: stale pending check — 직전 CALL_DETECTED와 현재 시각 차이 5분 초과 시 차단
        val lastDetected = lastCallDetectedPerPlatform[platform] ?: 0L
        if (lastDetected > 0) {
            val now = timeSource()
            val gapMs = now - lastDetected
            if (gapMs > STALE_PENDING_THRESHOLD_MS) {
                OtwFileLogger.log(TAG, "STALE_PENDING_BLOCKED: ${gapMs / 1000}s gap, $source ${price}원 $platform store='$storeName'")
                Log.d(TAG, "STALE_PENDING_BLOCKED: ${gapMs / 1000}s gap")
                try {
                    val csId = com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(
                        eventId = eventId.ifBlank { null },
                        orderId = orderId.ifBlank { null },
                        fingerprint = com.vita.ontheway.ledger.CallSessionRegistry.buildFingerprint(storeName, price, platform)
                    )
                    val derivedJson = org.json.JSONObject().apply {
                        put("price", price)
                        put("storeName", storeName)
                        put("source", source.name)
                        put("gap_ms", gapMs)
                        put("last_call_detected_ms", lastDetected)
                    }.toString()
                    com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                        ctx, csId, eventId, orderId, platform,
                        com.vita.ontheway.ledger.LedgerEventType.STALE_PENDING_BLOCKED,
                        "stale_guard", derivedJson
                    )
                } catch (_: Exception) {}
                return
            }
        }

        val callSessionId = com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(
            eventId = eventId.ifBlank { null },
            orderId = orderId.ifBlank { null },
            fingerprint = com.vita.ontheway.ledger.CallSessionRegistry.buildFingerprint(storeName, price, platform)
        )

        // putIfAbsent 기반 선점
        val now = System.currentTimeMillis()
        val prev = acceptedSessionCache.putIfAbsent(callSessionId, now)
        if (prev != null) {
            recordBlocked(ctx, callSessionId, eventId, orderId, platform, price, storeName, source)
            return
        }

        val fromLedger = checkLedger(ctx, callSessionId)
        if (fromLedger) {
            recordBlocked(ctx, callSessionId, eventId, orderId, platform, price, storeName, source)
            return
        }

        // identity / joinEligible 계산
        val identityConf = if (explicitIdentityConf >= 0) explicitIdentityConf else when {
            orderId.isNotBlank() -> 0.9
            eventId.isNotBlank() -> 0.7
            storeName.isNotBlank() -> 0.5
            else -> 0.3
        }
        val priceSource = explicitPriceSource.ifBlank {
            when (source) {
                AcceptSource.COUPANG_PICKUP -> "offered"
                AcceptSource.BAEMIN_PROGRESS -> "accepted"
                AcceptSource.SYSTEM_NOTI -> "accepted"
                AcceptSource.CLICK -> "accepted"
                AcceptSource.FALLBACK -> "accepted_estimated"
            }
        }
        val joinEligible = identityConf >= 0.7 && priceSource != "accepted_estimated"

        if (immediateConfirm) {
            // 하위 호환: DRIVER_ACCEPTED 동기 ledger + 즉시 EarningsTracker
            writeLedgerSync(ctx, callSessionId, eventId, orderId, platform, source,
                identityConf, joinEligible, priceSource, storeName, price)
        }
        // immediateConfirm=false: ACCEPT_CANDIDATE ledger는 AcceptLifecycle이 기록

        evictIfNeeded()

        // AcceptLifecycle에 등록
        val lat = OnTheWayService.currentLat
        val lng = OnTheWayService.currentLng
        AcceptLifecycle.markAcceptCandidate(
            ctx, callSessionId, eventId, orderId, platform,
            price, storeName, source, lat, lng, joinEligible,
            immediateConfirm = immediateConfirm
        )

        // Quarantine 자동 분류
        try {
            if (source == AcceptSource.FALLBACK || (eventId.isBlank() && orderId.isBlank())) {
                CallLogDb.get(ctx).markQuarantined(callSessionId,
                    com.vita.ontheway.ledger.QuarantineReason.FALLBACK_ACCEPT,
                    "source=$source")
            }
        } catch (_: Exception) {}

        val mode = if (immediateConfirm) "CONFIRMED" else "CANDIDATE"
        OtwFileLogger.log(TAG, "수락 $mode: $source ${price}원 $platform store='$storeName' session=${callSessionId.take(8)}")
        Log.d(TAG, "수락 $mode: $source ${price}원 $platform store='$storeName' session=${callSessionId.take(8)}")
    }

    private fun writeLedgerSync(
        ctx: Context, callSessionId: String,
        eventId: String, orderId: String, platform: String,
        source: AcceptSource,
        identityConf: Double, joinEligible: Boolean,
        priceSource: String, storeName: String, price: Int
    ) {
        try {
            val sourceChannel = when (source) {
                AcceptSource.COUPANG_PICKUP -> "notification"
                AcceptSource.BAEMIN_PROGRESS -> "screen"
                AcceptSource.SYSTEM_NOTI -> "notification"
                AcceptSource.CLICK -> "screen"
                AcceptSource.FALLBACK -> "fallback"
            }
            val acceptStoreSource = when (source) {
                AcceptSource.BAEMIN_PROGRESS -> "baemin_screen"
                AcceptSource.COUPANG_PICKUP -> "coupang_screen"
                AcceptSource.SYSTEM_NOTI -> "${platform}_nls"
                AcceptSource.CLICK -> "${platform}_screen"
                AcceptSource.FALLBACK -> "${platform}_fallback"
            }
            val derivedJson = org.json.JSONObject().apply {
                put("price", price)
                put("storeName", storeName)
                put("source", source.name)
                put("identity_confidence", identityConf)
                put("join_eligible", joinEligible)
                put("price_source", priceSource)
                put("source_channel", sourceChannel)
                put("storeName_source", acceptStoreSource)
            }.toString()
            val writer = ledgerSyncWriter
            if (writer != null) {
                writer(ctx, callSessionId, eventId, orderId, platform,
                    com.vita.ontheway.ledger.LedgerEventType.DRIVER_ACCEPTED,
                    "user_action", derivedJson)
            } else {
                com.vita.ontheway.ledger.LedgerAppender.appendLifecycleSync(
                    ctx, callSessionId, eventId, orderId, platform,
                    com.vita.ontheway.ledger.LedgerEventType.DRIVER_ACCEPTED,
                    "user_action", derivedJson
                )
            }
        } catch (_: Exception) {}
    }

    private fun recordBlocked(
        ctx: Context, callSessionId: String,
        eventId: String, orderId: String, platform: String,
        price: Int, storeName: String, source: AcceptSource
    ) {
        val allBlank = eventId.isBlank() && orderId.isBlank() && storeName.isBlank()
        val identityConf = when {
            orderId.isNotBlank() -> 0.9
            eventId.isNotBlank() -> 0.7
            storeName.isNotBlank() -> 0.5
            else -> 0.3
        }
        val isOrphan = allBlank || identityConf <= 0.5 || source == AcceptSource.FALLBACK
        val blockType = if (isOrphan) {
            com.vita.ontheway.ledger.LedgerEventType.DUPLICATE_ACCEPT_SUSPECTED
        } else {
            com.vita.ontheway.ledger.LedgerEventType.DUPLICATE_ACCEPT_BLOCKED
        }

        val label = if (isOrphan) "SUSPECTED" else "BLOCKED"

        // Fix U: rate-limit — 동일 key 60초 이내 ledger 중복 기록 방지
        val dedupKey = "$platform:${callSessionId.take(16)}:${source.name}"
        if (!shouldLogDuplicate(dedupKey)) return

        OtwFileLogger.log(TAG, "PERMANENT_DEDUP($label): $source ${price}원 $platform session=${callSessionId.take(8)}")

        try {
            val derivedJson = org.json.JSONObject().apply {
                put("price", price)
                put("storeName", storeName)
                put("source", source.name)
                put("blocked_at_ms", timeSource())
                put("original_session_id", callSessionId)
                if (isOrphan) put("quarantine_reason", "orphan_blank_storename_dedup")
            }.toString()
            com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                ctx, callSessionId, eventId, orderId, platform,
                blockType, "user_action", derivedJson
            )
        } catch (_: Exception) {}
    }

    private fun checkLedger(ctx: Context, callSessionId: String): Boolean {
        val checker = ledgerChecker
        return if (checker != null) {
            checker(ctx, callSessionId)
        } else {
            try {
                com.vita.ontheway.ledger.LedgerEventsRepository.hasAcceptedSession(ctx, callSessionId)
            } catch (_: Exception) { false }
        }
    }

    private fun evictIfNeeded() {
        if (acceptedSessionCache.size > CACHE_MAX_SIZE) {
            val oldest = acceptedSessionCache.entries.minByOrNull { it.value }
            oldest?.let { acceptedSessionCache.remove(it.key) }
        }
    }

    /** FilterLog 최근 콜 요약 */
    data class RecentCall(val price: Int, val platform: String, val eventId: String, val orderId: String, val storeName: String, val ts: Long = 0)

    /** platform 무관 최근 콜 (legacy fallback용) */
    fun getRecentCall(ctx: Context, windowMs: Long = 60_000): RecentCall? {
        return getRecentCall(ctx, platform = null, windowMs = windowMs)
    }

    /**
     * Fix Q-0c: platform 필터 지원 최근 콜.
     * @param platform null이면 모든 플랫폼, non-null이면 해당 플랫폼만
     */
    fun getRecentCall(ctx: Context, platform: String?, windowMs: Long = 60_000): RecentCall? {
        val recent = FilterLog.getRecent(ctx, 5)
        val now = System.currentTimeMillis()
        for (entry in recent) {
            val ts = entry.optLong("ts", 0)
            if (now - ts > windowMs) continue
            val entryPlatform = entry.optString("platform", "unknown")
            if (platform != null && entryPlatform != platform) continue
            val price = entry.optInt("price", 0)
            if (price <= 0) continue
            return RecentCall(
                price = price,
                platform = entryPlatform,
                eventId = entry.optString("eventId", ""),
                orderId = entry.optString("orderId", ""),
                storeName = entry.optString("storeName", ""),
                ts = ts
            )
        }
        return null
    }

    /** 하위 호환: getRecentPrice */
    fun getRecentPrice(ctx: Context, windowMs: Long = 60_000): Pair<Int, String>? {
        val rc = getRecentCall(ctx, windowMs) ?: return null
        return rc.price to rc.platform
    }

    // ── Fix V+: BAEMIN_PROGRESS → ACCEPT 검증 (5순위 매칭) ──

    private const val VERIFY_WINDOW_MS = 5 * 60 * 1000L
    private const val PRICE_TOLERANCE = 300

    /** 매칭 결과 (analytic용) */
    data class VerifyResult(val verified: Boolean, val matchedVia: String?)

    /**
     * 5순위 매칭:
     * 1. orderId exact match
     * 2. eventId exact match
     * 3. call_session_id (sessionId) exact match
     * 4. price±300 + storeName fuzzy (양쪽 non-blank + 후보 1개)
     * 5. 그 외 → unverified
     */
    fun verifyAcceptCandidate(
        ctx: Context, platform: String,
        price: Int, storeName: String,
        sessionId: String?,
        orderId: String?,
        lastCallDetectedMs: Long
    ): VerifyResult {
        val now = System.currentTimeMillis()
        if (now - lastCallDetectedMs > VERIFY_WINDOW_MS) {
            OtwFileLogger.log(TAG, "FIX-V+: 시간 윈도우 초과 (${(now - lastCallDetectedMs) / 1000}s)")
            return VerifyResult(false, null)
        }

        val recent = FilterLog.getRecent(ctx, 10)
        val windowEntries = recent.filter { entry ->
            val ts = entry.optLong("ts", 0)
            now - ts <= VERIFY_WINDOW_MS && entry.optString("platform", "") == platform
        }

        // 1순위: orderId exact match
        if (!orderId.isNullOrBlank()) {
            for (entry in windowEntries) {
                val entryOrderId = entry.optString("orderId", "")
                if (entryOrderId.isNotBlank() && entryOrderId == orderId) {
                    OtwFileLogger.log(TAG, "FIX-V+: orderId 매칭 ($orderId)")
                    return VerifyResult(true, "orderId")
                }
            }
        }

        // 2순위: eventId exact match
        if (!sessionId.isNullOrBlank()) {
            for (entry in windowEntries) {
                val entryEventId = entry.optString("eventId", "")
                if (entryEventId.isNotBlank() && entryEventId == sessionId) {
                    OtwFileLogger.log(TAG, "FIX-V+: eventId 매칭 ($sessionId)")
                    return VerifyResult(true, "eventId")
                }
            }
        }

        // 3순위: call_session_id exact match (FilterLog에 session_id가 있는 경우)
        if (!sessionId.isNullOrBlank()) {
            for (entry in windowEntries) {
                val entryState = entry.optString("state", "")
                if (entryState.isNotBlank() && entryState == sessionId) {
                    OtwFileLogger.log(TAG, "FIX-V+: session_id 매칭 ($sessionId)")
                    return VerifyResult(true, "session_id")
                }
            }
        }

        // 4순위: price±300 + storeName fuzzy (양쪽 non-blank + 후보 1개)
        if (storeName.isNotBlank()) {
            val fuzzyMatches = windowEntries.filter { entry ->
                val entryPrice = entry.optInt("price", 0)
                val entryStore = entry.optString("storeName", "")
                entryPrice > 0 &&
                    kotlin.math.abs(entryPrice - price) <= PRICE_TOLERANCE &&
                    entryStore.isNotBlank() &&
                    storeNameFuzzyMatch(storeName, entryStore)
            }
            if (fuzzyMatches.size == 1) {
                OtwFileLogger.log(TAG, "FIX-V+: fuzzy 매칭 (price=$price store='$storeName')")
                return VerifyResult(true, "fuzzy")
            }
            if (fuzzyMatches.size > 1) {
                OtwFileLogger.log(TAG, "FIX-V+: fuzzy ambiguous (${fuzzyMatches.size}건) → unverified")
                return VerifyResult(false, null)
            }
        }

        // 5순위: 모두 실패 → unverified
        OtwFileLogger.log(TAG, "FIX-V+: 매칭 실패 (price=$price store='$storeName' orderId=$orderId)")
        return VerifyResult(false, null)
    }

    internal fun storeNameFuzzyMatch(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.contains(b) || b.contains(a)) return true
        if (a.length >= 2 && b.length >= 2 && a.take(2) == b.take(2)) return true
        return false
    }

    fun recordUnverifiedAccept(
        ctx: Context, source: AcceptSource,
        price: Int, platform: String,
        storeName: String, eventId: String, orderId: String
    ) {
        val callSessionId = com.vita.ontheway.ledger.CallSessionRegistry.getOrCreateSessionId(
            eventId = eventId.ifBlank { null },
            orderId = orderId.ifBlank { null },
            fingerprint = com.vita.ontheway.ledger.CallSessionRegistry.buildFingerprint(storeName, price, platform)
        )
        if (acceptedSessionCache.containsKey(callSessionId)) return
        try {
            val derivedJson = org.json.JSONObject().apply {
                put("price", price)
                put("storeName", storeName)
                put("source", source.name)
                put("reason", "unverified_baemin_progress")
                put("blocked_at_ms", System.currentTimeMillis())
            }.toString()
            com.vita.ontheway.ledger.LedgerAppender.appendLifecycle(
                ctx, callSessionId, eventId, orderId, platform,
                com.vita.ontheway.ledger.LedgerEventType.ACCEPT_SUSPECTED,
                "screen_unverified", derivedJson
            )
        } catch (_: Exception) {}
        OtwFileLogger.log(TAG, "FIX-V: ACCEPT_SUSPECTED_UNVERIFIED: $source ${price}원 $platform store='$storeName'")
    }

    fun resetForTest() {
        acceptedSessionCache.clear()
        duplicateLogTracker.clear()
        lastCallDetectedPerPlatform.clear()
        ledgerChecker = null
        ledgerSyncWriter = null
        timeSource = { System.currentTimeMillis() }
        com.vita.ontheway.ledger.CallSessionRegistry.resetForTest()
    }
}
