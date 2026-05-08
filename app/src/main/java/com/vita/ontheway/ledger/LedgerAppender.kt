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

    private fun packageToPlatform(pkg: String): String = when {
        pkg.contains("woowahan") -> "baemin"
        pkg.contains("coupang") -> "coupang"
        pkg.contains("kakaomobility") || pkg.contains("flexer") -> "kakaot"
        else -> "system"
    }

    /**
     * JSON payload 5KB 초과 시 truncate.
     */
    fun truncatePayload(json: String): String {
        if (json.toByteArray().size <= MAX_PAYLOAD_BYTES) return json
        return try {
            val obj = JSONObject(json)
            obj.put("_truncated", true)
            // 긴 필드 잘라내기
            for (key in listOf("texts", "bigText", "text", "contentDescription")) {
                if (obj.has(key)) {
                    val value = obj.opt(key)?.toString() ?: continue
                    if (value.length > 500) {
                        obj.put(key, value.take(500) + "...")
                    }
                }
            }
            val result = obj.toString()
            if (result.toByteArray().size > MAX_PAYLOAD_BYTES) {
                result.take(MAX_PAYLOAD_BYTES) + "\"_hard_truncated\":true}"
            } else result
        } catch (_: Exception) {
            json.take(MAX_PAYLOAD_BYTES)
        }
    }
}
