package com.vita.ontheway

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * v3.26: AI 보조 v0.1 (개발자 모드 전용)
 *
 * MEDIUM/애매 구간만 Claude Haiku 호출.
 * 응답 5단어 이하 강제.
 * 실패 시 룰 결과만 사용 (fallback).
 */
object AiAssist {

    private const val TAG = "AiAssist"
    private const val MODEL = "claude-haiku-4-5-20251001"
    private const val MAX_TOKENS = 30

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AI-Assist").apply { isDaemon = true }
    }

    /**
     * 애매 구간인지 판별 (unitPrice 1400~1700)
     */
    fun isAmbiguous(call: DeliveryCall): Boolean {
        val dist = call.distance ?: return false
        if (dist <= 0) return false
        val unitPrice = (call.price / dist).toInt()
        return unitPrice in 1400..1699
    }

    /**
     * AI 보조 호출 (비동기).
     * 결과는 콜백으로 반환, DB에도 저장.
     */
    fun assist(ctx: Context, call: DeliveryCall, onResult: ((String) -> Unit)? = null) {
        if (!FeatureFlags.aiAssistEnabled || !FeatureFlags.devMode) return
        if (!isAmbiguous(call)) return

        val dist = call.distance ?: return
        val unitPrice = (call.price / dist).toInt()
        val platform = call.platform
        val price = call.price
        val pickupKm = call.pickupDistanceKm ?: 0.0

        executor.execute {
            try {
                val reason = callHaiku(platform, price, pickupKm, dist, unitPrice)
                if (reason != null) {
                    OtwFileLogger.log(TAG, "AI reason: \"$reason\" for ${platform} ${price}원")
                    CallLogDb.get(ctx).updateAiReason(price, platform, reason)
                    onResult?.invoke(reason)
                }
            } catch (e: Exception) {
                Log.w(TAG, "AI assist 실패: ${e.message}")
                OtwFileLogger.log(TAG, "AI assist 실패: ${e.message}")
            }
        }
    }

    private fun callHaiku(platform: String, price: Int, pickupKm: Double, distanceKm: Double, unitPrice: Int): String? {
        val apiKey = Config.ANTHROPIC_API_KEY
        if (apiKey.isBlank()) return null

        val userMsg = "platform=$platform, price=${price}원, pickupKm=${"%.1f".format(pickupKm)}, distanceKm=${"%.1f".format(distanceKm)}, unitPrice=${unitPrice}원/km"

        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            put("system", "You are a delivery assistant. Give ONE reason in 4 words or less. Korean only. No sentences.")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMsg)
                })
            })
        }

        val url = URL("https://api.anthropic.com/v1/messages")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.doOutput = true
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code in 200..299) {
                val respText = conn.inputStream.bufferedReader().readText()
                val resp = JSONObject(respText)
                val content = resp.getJSONArray("content")
                if (content.length() > 0) {
                    val text = content.getJSONObject(0).getString("text").trim()
                    // 5단어 이하 강제
                    val words = text.split(Regex("\\s+"))
                    if (words.size <= 5) text else words.take(5).joinToString(" ")
                } else null
            } else {
                Log.w(TAG, "Haiku API $code")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haiku 연결 실패: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}
