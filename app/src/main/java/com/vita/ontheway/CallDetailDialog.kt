package com.vita.ontheway

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * 콜 상세 다이얼로그 (MainActivity / UserModeActivity 공용).
 * DB 행 ID로 조회하여 표시.
 */
object CallDetailDialog {

    private val nf = NumberFormat.getNumberInstance()
    private val sdfHms = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun showById(context: Context, rowId: Long, onFeedbackChanged: (() -> Unit)? = null) {
        try {
            val db = CallLogDb.get(context).readableDatabase
            val cursor = db.rawQuery(
                "SELECT timestamp, platform, price, distance, unitPrice, point, verdict, reason, " +
                "bundleCount, isMultiPickup, storeName, destination, pickupKm FROM ${CallLogDb.TABLE} WHERE id = ?",
                arrayOf(rowId.toString())
            )
            cursor.use {
                if (!it.moveToFirst()) return
                val ts = it.getLong(0)
                val platform = it.getString(1) ?: ""
                val price = it.getInt(2)
                val dist = if (!it.isNull(3)) it.getDouble(3) else -1.0
                val unitPrice = it.getInt(4)
                val point = if (!it.isNull(5)) it.getDouble(5) else -1.0
                val verdict = it.getString(6) ?: ""
                val reason = it.getString(7) ?: ""
                val bundleCount = it.getInt(8)
                val isMultiPickup = it.getInt(9) == 1
                val storeName = it.getString(10) ?: ""
                val destination = it.getString(11) ?: ""
                val pickupKm = if (!it.isNull(12)) it.getDouble(12) else -1.0

                showInternal(context, ts, platform, price, dist, unitPrice, point,
                    verdict, reason, bundleCount, isMultiPickup, storeName, destination, pickupKm, onFeedbackChanged)
            }
        } catch (_: Exception) {}
    }

    private fun showInternal(
        context: Context, ts: Long, platform: String, price: Int, dist: Double,
        unitPrice: Int, point: Double, verdict: String, reason: String,
        bundleCount: Int, isMultiPickup: Boolean, storeName: String,
        destination: String, pickupKm: Double, onFeedbackChanged: (() -> Unit)? = null
    ) {
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }
        val pName = when (platform) {
            "coupang" -> "쿠팡"; "baemin" -> "배민"; "kakaot" -> "카카오T"; else -> platform
        }

        // 내부 verdict (데이터용, FeedbackLogger 전달용)
        val verdictKr = when {
            verdict == "REJECT" -> "REJECT"
            reason.contains("추천") || reason.contains("잡으세요") -> "ACCEPT_TOP"
            else -> "ACCEPT"
        }

        // 플랫폼 색상
        val platformColor = when (platform) {
            "baemin" -> Color.parseColor("#FF2D55")
            "coupang" -> Color.parseColor("#FF6B00")
            "kakaot" -> Color.parseColor("#F7B731")
            else -> Color.parseColor("#6C757D")
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        // 금액 헤더
        container.addView(TextView(context).apply {
            text = "${nf.format(price)}원"; textSize = 24f; setTextColor(Color.parseColor("#1A1A2E"))
            setTypeface(typeface, Typeface.BOLD)
        })

        // 플랫폼 (플랫폼 고유 색상)
        container.addView(TextView(context).apply {
            text = pName; textSize = 14f
            setTextColor(platformColor); setPadding(0, dp(2), 0, 0)
        })

        fun divider() {
            container.addView(View(context).apply {
                setBackgroundColor(Color.parseColor("#EEEEEE"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { topMargin = dp(12); bottomMargin = dp(12) }
            })
        }

        fun section(label: String, value: String) {
            container.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
                addView(TextView(context).apply {
                    text = label; textSize = 14f; setTextColor(Color.parseColor("#6C757D"))
                    layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                addView(TextView(context).apply {
                    text = value; textSize = 14f; setTextColor(Color.parseColor("#1A1A2E"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            })
        }

        divider()

        // 거리
        if (platform == "baemin" && point > 0) {
            val pointKm = BaeminParser.convertPointToKm(point)
            section("거리", "약 ${"%.1f".format(pointKm)}km (${"%.1f".format(point)}P)")
            if (pointKm > 0) section("단가", "${nf.format((price / pointKm).toInt())}원/km")
        } else if (dist >= 0) {
            section("거리", "${"%.1f".format(dist)}km")
            if (unitPrice > 0) section("단가", "${nf.format(unitPrice)}원/km")
        }

        if (pickupKm > 0) {
            section("픽업", "${"%.1f".format(pickupKm)}km")
            if (dist > 0) {
                val totalKm = pickupKm + dist
                section("총거리", "${"%.1f".format(totalKm)}km (${nf.format((price / totalKm).toInt())}원/km)")
            }
        }

        if (bundleCount > 1) {
            val perItem = price / bundleCount
            val pickupStr = if (isMultiPickup) " · 다중픽업" else ""
            section("묶음", "${bundleCount}건 (건당 ${nf.format(perItem)}원$pickupStr)")
        }

        if (storeName.isNotEmpty()) {
            val cleaned = StoreNameCleaner.clean(storeName)
            if (cleaned.isNotEmpty()) section("가게", cleaned.joinToString("\n"))
        }
        if (destination.isNotEmpty() && !destination.contains("검색하기")) {
            section("목적지", destination)
        }

        divider()
        val dummyCall = DeliveryCall(price = price, distance = if (dist >= 0) dist else null,
            isMulti = bundleCount > 1, platform = platform, bundleCount = bundleCount)
        val evidenceReason = OutputController.toEvidenceReason(reason, price, dummyCall)
        val displayReason = evidenceReason.ifEmpty { simplifyReason(reason, verdict) }
        if (displayReason.isNotEmpty()) section("사유", displayReason)
        divider()
        section("감지", sdfHms.format(Date(ts)))

        // 👍👎
        divider()
        val sessionId = "s_${ts}_${price}"
        val existingFb = FeedbackLogger.findByCall(context, ts, price)
        OtwFileLogger.log("FeedbackCheck", "ts=$ts price=$price sid=$sessionId → found=${existingFb != null}${if (existingFb != null) " fb=${existingFb.feedback} esid=${existingFb.sessionId}" else ""}")

        fun openFeedbackDialog(isEdit: Boolean, existingEntry: FeedbackEntry?) {
            val ep = existingEntry?.entryPoint ?: "thumbs_up"
            val otwDist = if (dist >= 0) dist.toFloat() else null
            BidirectionalFeedbackDialog.show(context, ep,
                platform = platform,
                onthewayDistanceKm = otwDist,
                existing = existingEntry) { matrix ->
                if (isEdit && existingEntry != null) {
                    val updated = existingEntry.copy(
                        feedback = existingEntry.feedback,
                        reasons = matrix.toReasonsList(),
                        pickupRating = matrix.pickupRating,
                        deliveryRating = matrix.deliveryRating,
                        priceRating = matrix.priceRating,
                        judgmentRating = matrix.judgmentRating,
                        entryPoint = matrix.entryPoint,
                        platformDistanceKm = matrix.platformDistanceKm,
                        onthewayDistanceKm = matrix.onthewayDistanceKm,
                        distanceDiffKm = matrix.distanceDiffKm
                    )
                    FeedbackLogger.updateBySessionId(context, sessionId, updated)
                    Toast.makeText(context, "피드백 수정됨", Toast.LENGTH_SHORT).show()
                } else {
                    val fb = if (ep == "thumbs_up") "up" else "down"
                    val ptVal = if (point > 0) point.toFloat() else null
                    FeedbackLogger.log(context, platform = platform, store = storeName,
                        price = price, distanceKm = if (dist >= 0) dist else 0.0,
                        verdict = verdictKr, reason = reason, sessionId = sessionId,
                        feedback = fb, reasons = matrix.toReasonsList(),
                        driverAction = if (fb == "up") "accepted" else "rejected",
                        pickupRating = matrix.pickupRating, deliveryRating = matrix.deliveryRating,
                        priceRating = matrix.priceRating, judgmentRating = matrix.judgmentRating,
                        entryPoint = matrix.entryPoint,
                        platformDistanceKm = matrix.platformDistanceKm,
                        onthewayDistanceKm = matrix.onthewayDistanceKm,
                        distanceDiffKm = matrix.distanceDiffKm,
                        pointValue = ptVal)
                    val emoji = if (ep == "thumbs_up") "\uD83D\uDC4D" else "\uD83D\uDC4E"
                    Toast.makeText(context, "$emoji 기록됨", Toast.LENGTH_SHORT).show()
                }
                onFeedbackChanged?.invoke()
            }
        }

        if (existingFb != null) {
            val fbRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
            }
            val fbEmoji = if (existingFb.feedback == "up") "\uD83D\uDC4D" else "\uD83D\uDC4E"
            fbRow.addView(TextView(context).apply {
                text = "$fbEmoji 피드백 완료"
                textSize = 14f; setTextColor(Color.parseColor("#999999"))
            })
            fbRow.addView(TextView(context).apply {
                text = "수정하기"; textSize = 13f; setTextColor(Color.parseColor("#4CC9F0"))
                setPadding(dp(12), 0, 0, 0)
                setOnClickListener { openFeedbackDialog(true, existingFb) }
            })
            container.addView(fbRow)
        } else {
            val feedbackRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(4))
            }
            fun fbBtn(emoji: String, isUp: Boolean): TextView {
                return TextView(context).apply {
                    text = emoji; textSize = 20f; gravity = Gravity.CENTER
                    setBackgroundColor(Color.parseColor("#F0F0F0"))
                    setPadding(dp(24), dp(8), dp(24), dp(8))
                    setOnClickListener {
                        val epStr = if (isUp) "thumbs_up" else "thumbs_down"
                        val otwDist = if (dist >= 0) dist.toFloat() else null
                        BidirectionalFeedbackDialog.show(context, epStr,
                            platform = platform,
                            onthewayDistanceKm = otwDist) { matrix ->
                            val fb = if (isUp) "up" else "down"
                            val ptVal2 = if (point > 0) point.toFloat() else null
                            FeedbackLogger.log(context, platform = platform, store = storeName,
                                price = price, distanceKm = if (dist >= 0) dist else 0.0,
                                verdict = verdictKr, reason = reason, sessionId = sessionId,
                                feedback = fb, reasons = matrix.toReasonsList(),
                                driverAction = if (fb == "up") "accepted" else "rejected",
                                pickupRating = matrix.pickupRating, deliveryRating = matrix.deliveryRating,
                                priceRating = matrix.priceRating, judgmentRating = matrix.judgmentRating,
                                entryPoint = matrix.entryPoint,
                                platformDistanceKm = matrix.platformDistanceKm,
                                onthewayDistanceKm = matrix.onthewayDistanceKm,
                                distanceDiffKm = matrix.distanceDiffKm,
                                pointValue = ptVal2)
                            Toast.makeText(context, "$emoji 기록됨", Toast.LENGTH_SHORT).show()
                            onFeedbackChanged?.invoke()
                        }
                    }
                }
            }
            feedbackRow.addView(fbBtn("\uD83D\uDC4D", true),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
            feedbackRow.addView(fbBtn("\uD83D\uDC4E", false))
            container.addView(feedbackRow)
        }

        AlertDialog.Builder(context).setView(container).setPositiveButton("확인", null).show()
    }

    private fun simplifyReason(raw: String, verdict: String): String {
        val simplified = when {
            verdict == "REJECT" && raw.contains("묶음 최소") -> {
                Regex("""묶음 최소\s*([\d,]+)원""").find(raw)?.let { "묶음 최소 ${it.groupValues[1]}원 미달" } ?: raw
            }
            verdict == "REJECT" && raw.contains("단가") && raw.contains("미달") -> {
                Regex("""단가\s*([\d,]+)원/km""").find(raw)?.let { "단가 ${it.groupValues[1]}원/km 미달" } ?: raw
            }
            verdict == "REJECT" && raw.contains("최소배달료") -> {
                Regex("""([\d,]+)원 미달""").find(raw)?.let { "최소 ${it.groupValues[1]}원 미달" } ?: raw
            }
            raw.contains("묶음 통과") -> "묶음 통과"
            raw.contains("최소배달료 통과") -> "통과"
            raw.length > 40 -> raw.take(40) + "..."
            else -> raw
        }
        // FORBIDDEN_WORDS 필터: 판단형 단어 포함 시 빈 문자열 반환
        if (OutputController.FORBIDDEN_WORDS.any { simplified.contains(it) }) return ""
        return simplified
    }
}
