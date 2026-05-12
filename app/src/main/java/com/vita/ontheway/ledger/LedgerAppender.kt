package com.vita.ontheway.ledger

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Ledger append 비동기 헬퍼.
 * 메인 스레드 영향 X — 단일 백그라운드 스레드에서 DB INSERT.
 */
object LedgerAppender {

    private const val TAG = "LedgerAppender"
    private const val MAX_PAYLOAD_BYTES = 5 * 1024  // 5KB

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Ledger-IO").apply { isDaemon = true }
    }

    /**
     * RAW_NOTIFICATION_SEEN 이벤트 append.
     */
    fun appendNotification(
        ctx: Context,
        pkg: String,
        sbnKey: String,
        sbnId: Int,
        postTime: Long,
        title: String,
        text: String,
        bigText: String
    ) {
        val now = System.currentTimeMillis()
        val mono = SystemClock.elapsedRealtime()
        val platform = packageToPlatform(pkg)
        val rawJson = truncatePayload(JSONObject().apply {
            put("key", sbnKey)
            put("id", sbnId)
            put("package", pkg)
            put("postTime", postTime)
            put("title", title)
            put("text", text)
            put("bigText", bigText)
        }.toString())

        val event = LedgerEvent(
            ledgerEventId = UUID.randomUUID().toString(),
            eventId = sbnKey,
            platform = platform,
            eventType = LedgerEventType.RAW_NOTIFICATION_SEEN,
            sourceChannel = "notification",
            occurredAtWall = now,
            occurredAtMonotonic = mono,
            identityConfidence = 0.5,
            confidence = 1.0,
            rawPayloadJson = rawJson
        )
        appendAsync(ctx, event)
    }

    /**
     * RAW_ACCESSIBILITY_SEEN 이벤트 append.
     */
    fun appendAccessibility(
        ctx: Context,
        pkg: String,
        eventType: Int,
        className: String?,
        texts: List<String>,
        contentDesc: String?,
        nodeCount: Int
    ) {
        val now = System.currentTimeMillis()
        val mono = SystemClock.elapsedRealtime()
        val platform = packageToPlatform(pkg)
        val fingerprint = "$pkg:$eventType:${texts.hashCode()}"
        val rawJson = truncatePayload(JSONObject().apply {
            put("package", pkg)
            put("eventType", eventType)
            put("className", className ?: "")
            put("texts", org.json.JSONArray(texts.take(50)))
            put("contentDescription", contentDesc ?: "")
            put("node_count", nodeCount)
        }.toString())

        val event = LedgerEvent(
            ledgerEventId = UUID.randomUUID().toString(),
            eventId = fingerprint,
            platform = platform,
            eventType = LedgerEventType.RAW_ACCESSIBILITY_SEEN,
            sourceChannel = "accessibility",
            occurredAtWall = now,
            occurredAtMonotonic = mono,
            identityConfidence = 0.3,
            confidence = 1.0,
            rawPayloadJson = rawJson
        )
        appendAsync(ctx, event)
    }

    /**
     * 일반 lifecycle 이벤트 append — 비동기 (CALL_DETECTED, JUDGMENT_ISSUED 등).
     */
    fun appendLifecycle(
        ctx: Context,
        callSessionId: String,
        eventId: String?,
        orderId: String?,
        platform: String,
        type: LedgerEventType,
        sourceChannel: String,
        derivedPayloadJson: String? = null
    ) {
        val event = buildLifecycleEvent(callSessionId, eventId, orderId, platform, type, sourceChannel, derivedPayloadJson)
        appendAsync(ctx, event)
    }

    /**
     * DRIVER_ACCEPTED 전용 동기 insert.
     * crash 시에도 ledger에 기록이 보존되어 재시작 후 dedup이 작동.
     * 호출자가 IO 스레드에서 호출하거나, 단일 insert이므로 <5ms.
     */
    fun appendLifecycleSync(
        ctx: Context,
        callSessionId: String,
        eventId: String?,
        orderId: String?,
        platform: String,
        type: LedgerEventType,
        sourceChannel: String,
        derivedPayloadJson: String? = null
    ) {
        val event = buildLifecycleEvent(callSessionId, eventId, orderId, platform, type, sourceChannel, derivedPayloadJson)
        try {
            LedgerEventsRepository.append(ctx.applicationContext, event)
        } catch (e: Exception) {
            Log.w(TAG, "ledger sync append 실패: ${e.message}")
        }
    }

    /**
     * Fix 4 (v70.8.1): explicitIdentityConf >= 0 → derived payload와 동일값 사용.
     * 미전달 시 기존 자동 추론.
     */
    private fun buildLifecycleEvent(
        callSessionId: String,
        eventId: String?,
        orderId: String?,
        platform: String,
        type: LedgerEventType,
        sourceChannel: String,
        derivedPayloadJson: String?,
        explicitIdentityConf: Double = -1.0
    ): LedgerEvent {
        // Fix 4: derived payload의 identity_confidence 우선 사용
        val fromPayload = derivedPayloadJson?.let {
            try { JSONObject(it).optDouble("identity_confidence", -1.0) } catch (_: Exception) { -1.0 }
        } ?: -1.0
        val identityConf = when {
            explicitIdentityConf >= 0 -> explicitIdentityConf
            fromPayload >= 0 -> fromPayload
            !orderId.isNullOrBlank() -> 1.0
            !eventId.isNullOrBlank() -> 0.8
            else -> 0.5
        }
        return LedgerEvent(
            ledgerEventId = UUID.randomUUID().toString(),
            callSessionId = callSessionId,
            eventId = eventId,
            orderId = orderId,
            platform = platform,
            eventType = type,
            sourceChannel = sourceChannel,
            occurredAtWall = System.currentTimeMillis(),
            occurredAtMonotonic = SystemClock.elapsedRealtime(),
            identityConfidence = identityConf,
            confidence = 0.9,
            derivedPayloadJson = derivedPayloadJson
        )
    }

    private fun appendAsync(ctx: Context, event: LedgerEvent) {
        val appCtx = ctx.applicationContext
        executor.execute {
            try {
                LedgerEventsRepository.append(appCtx, event)
            } catch (e: Exception) {
                Log.w(TAG, "ledger append 실패: ${e.message}")
            }
        }
    }

    /**
     * DiagnosticLogger가 수집한 node-level 접근성 트리를 ledger에 append.
     * entries = traverseTree에서 수집한 노드별 JSONObject 리스트.
     * 전체를 하나의 RAW_ACCESSIBILITY_SEEN 이벤트로 묶어 저장.
     */
    fun appendDiagnosticAccessibility(
        ctx: Context,
        pkg: String,
        entries: List<JSONObject>
    ) {
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        val mono = SystemClock.elapsedRealtime()
        val platform = packageToPlatform(pkg)

        val payload = JSONObject().apply {
            put("package", pkg)
            put("source", "diagnostic_tree_walk")
            put("node_count", entries.size)
            val nodesArray = org.json.JSONArray()
            for (entry in entries) {
                nodesArray.put(entry)
            }
            put("nodes", nodesArray)
        }

        val rawJson = truncatePayload(payload.toString())

        val event = LedgerEvent(
            ledgerEventId = UUID.randomUUID().toString(),
            eventId = "$pkg:diag:$now",
            platform = platform,
            eventType = LedgerEventType.RAW_ACCESSIBILITY_SEEN,
            sourceChannel = "accessibility_diagnostic",
            occurredAtWall = now,
            occurredAtMonotonic = mono,
            identityConfidence = 0.5,
            confidence = 1.0,
            rawPayloadJson = rawJson
        )
        appendAsync(ctx, event)
    }

    // [Source-Only 원칙 v1.0]
    // kakao_picker는 raw 로깅만. 분기 추가 금지.
    // 검증 종료: 2026-07-12 또는 10건 누적 후 평가.
    internal fun packageToPlatform(pkg: String): String = when {
        pkg.contains("woowahan") -> "baemin"
        pkg.contains("coupang") -> "coupang"
        pkg == "com.kakaomobility.flexer" -> "kakao_picker"
        pkg.contains("kakaomobility") || pkg.contains("flexer") -> "kakaot"
        else -> "system"
    }

    /**
     * FIX-RAW-PAYLOAD-VALID-JSON-TRUNCATE: JSON payload 5KB 초과 시 valid JSON 보장 truncate.
     *
     * 알고리즘:
     * 1. ≤5KB → 그대로
     * 2. JSON 파싱 → nodes/texts 배열 절반씩 줄이기 → 5KB 이하
     * 3. 배열 비워도 초과 → 배열=[] + 메타만 보존
     * 4. JSON 파싱 실패 → fallback 메타 JSON
     *
     * 출력 항상 valid JSON (파싱 100% 성공 보장).
     */
    fun truncatePayload(json: String): String {
        val originalSize = json.toByteArray().size
        if (originalSize <= MAX_PAYLOAD_BYTES) return json

        return try {
            val obj = JSONObject(json)
            truncateJsonObject(obj, originalSize)
        } catch (_: Exception) {
            // JSON 파싱 실패 → fallback 메타
            JSONObject().apply {
                put("_truncated", true)
                put("_original_size", originalSize)
                put("_reason", "json_parse_failed")
            }.toString()
        }
    }

    private fun truncateJsonObject(obj: JSONObject, originalSize: Int): String {
        obj.put("_truncated", true)
        obj.put("_original_size", originalSize)

        // 축소 대상 배열 필드 (우선순위)
        val arrayFields = listOf("nodes", "texts")
        // 축소 대상 문자열 필드
        val stringFields = listOf("bigText", "text", "contentDescription")

        // 1단계: 문자열 필드 500자 이하로 축소
        for (key in stringFields) {
            if (obj.has(key)) {
                val value = obj.opt(key)?.toString() ?: continue
                if (value.length > 500) {
                    obj.put(key, value.take(500) + "...")
                }
            }
        }
        if (obj.toString().toByteArray().size <= MAX_PAYLOAD_BYTES) return obj.toString()

        // 2단계: 배열 필드 절반씩 줄이기
        for (key in arrayFields) {
            if (!obj.has(key)) continue
            val arr = obj.optJSONArray(key) ?: continue
            val originalCount = arr.length()
            if (originalCount == 0) continue

            obj.put("_original_${key}_count", originalCount)

            var kept = originalCount
            while (kept > 0) {
                kept = kept / 2
                val trimmed = org.json.JSONArray()
                for (i in 0 until kept) {
                    trimmed.put(arr.get(i))
                }
                obj.put(key, trimmed)
                obj.put("_kept_${key}_count", kept)
                val result = obj.toString()
                if (result.toByteArray().size <= MAX_PAYLOAD_BYTES) {
                    return result
                }
            }

            // 배열 전부 제거
            obj.put(key, org.json.JSONArray())
            obj.put("_kept_${key}_count", 0)
            val result = obj.toString()
            if (result.toByteArray().size <= MAX_PAYLOAD_BYTES) return result
        }

        // 3단계: 문자열 필드 추가 축소 (100자)
        for (key in stringFields + listOf("texts")) {
            if (obj.has(key)) {
                val value = obj.opt(key)?.toString() ?: continue
                if (value.length > 100) {
                    obj.put(key, value.take(100) + "...")
                }
            }
        }
        val finalResult = obj.toString()
        if (finalResult.toByteArray().size <= MAX_PAYLOAD_BYTES) return finalResult

        // 4단계: 최소 메타만 보존
        return JSONObject().apply {
            put("_truncated", true)
            put("_original_size", originalSize)
            put("_reason", "exceeded_after_full_reduction")
            // 핵심 메타 보존
            if (obj.has("package")) put("package", obj.optString("package"))
            if (obj.has("source")) put("source", obj.optString("source"))
            if (obj.has("node_count")) put("node_count", obj.optInt("node_count"))
            if (obj.has("eventType")) put("eventType", obj.optInt("eventType"))
        }.toString()
    }
}
