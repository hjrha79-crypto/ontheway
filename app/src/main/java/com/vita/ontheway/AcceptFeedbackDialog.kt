package com.vita.ontheway

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 수락 피드백 다이얼로그.
 * FeedbackReasonDialog와 동일 패턴 — 사유 5개 체크박스 + 기타 주관식.
 */
object AcceptFeedbackDialog {

    private val REASON_LABELS = arrayOf("단가 좋음", "거리 적절", "가게 익숙", "픽업 빠름", "기타")
    private val REASON_CODES = arrayOf("good_price", "good_distance", "familiar_store", "fast_pickup", "other")

    fun show(
        context: Context,
        platform: String,
        storeName: String,
        price: Int,
        distanceKm: Double,
        verdict: String,
        reason: String,
        sessionId: String?,
        onDone: () -> Unit
    ) {
        try {
            val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(8))
            }

            // 콜 정보 요약
            val fmt = java.text.NumberFormat.getNumberInstance()
            val pName = when (platform) {
                "coupang" -> "쿠팡"; "baemin" -> "배민"; "kakaot" -> "카카오T"; else -> platform
            }
            root.addView(TextView(context).apply {
                text = "$pName ${fmt.format(price)}원" +
                    if (storeName.isNotBlank()) " · $storeName" else ""
                textSize = 14f
                setTextColor(Color.parseColor("#333333"))
                setPadding(0, 0, 0, dp(12))
            })

            // 사유 체크박스
            root.addView(TextView(context).apply {
                text = "왜 이 콜을 수락했나요? (중복 선택)"
                textSize = 13f
                setTextColor(Color.parseColor("#888888"))
                setPadding(0, 0, 0, dp(4))
            })

            val boxes = REASON_LABELS.mapIndexed { i, label ->
                CheckBox(context).apply {
                    text = label
                    textSize = 15f
                    setTextColor(Color.parseColor("#333333"))
                }.also { root.addView(it) }
            }

            // 기타 입력 필드 (마지막 체크박스 = "기타" 선택 시 활성화)
            val otherInput = EditText(context).apply {
                hint = "기타 사유 입력..."
                textSize = 14f
                setTextColor(Color.parseColor("#333333"))
                setHintTextColor(Color.parseColor("#BBBBBB"))
                setBackgroundColor(Color.parseColor("#F5F5F5"))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                visibility = android.view.View.GONE
                setSingleLine(true)
            }
            root.addView(otherInput, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, 0) })

            // "기타" 체크 시 입력 필드 토글
            boxes.last().setOnCheckedChangeListener { _, isChecked ->
                otherInput.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            }

            val dialog = AlertDialog.Builder(context)
                .setTitle("수락 피드백")
                .setView(root)
                .setPositiveButton("확인", null)
                .setNegativeButton("건너뛰기") { d, _ -> d.dismiss(); onDone() }
                .setCancelable(false)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val selected = mutableListOf<String>()
                    boxes.forEachIndexed { i, cb ->
                        if (cb.isChecked) selected.add(REASON_CODES[i])
                    }
                    val otherText = otherInput.text.toString().trim()

                    // 저장 (FeedbackLogger 재사용, driverAction="accepted")
                    try {
                        FeedbackLogger.log(
                            ctx = context,
                            platform = platform,
                            store = storeName,
                            price = price,
                            distanceKm = distanceKm,
                            verdict = verdict,
                            reason = if (otherText.isNotEmpty()) "accept_feedback: $otherText" else "accept_feedback",
                            sessionId = sessionId ?: "af_${System.currentTimeMillis()}",
                            feedback = "accept_reason",
                            reasons = selected,
                            driverAction = "accepted"
                        )
                    } catch (_: Exception) {}

                    dialog.dismiss()
                    onDone()
                }
            }

            dialog.show()
        } catch (_: Exception) {
            onDone()
        }
    }
}
