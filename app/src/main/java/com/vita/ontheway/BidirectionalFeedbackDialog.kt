package com.vita.ontheway

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 양방향 피드백 매트릭스 다이얼로그.
 *
 *              좋음    나쁨
 * 픽업 위치     [O]    [ ]
 * 배달 위치     [ ]    [O]
 * 단가/금액     [O]    [ ]
 * ───────────────────────
 * 판정 자체     [ ]    [ ]
 *
 * 같은 행에서 좋음+나쁨 동시 선택 불가 (배타 토글).
 */
object BidirectionalFeedbackDialog {

    data class MatrixResult(
        val pickupRating: String?,     // "GOOD" / "BAD" / null
        val deliveryRating: String?,
        val priceRating: String?,
        val judgmentRating: String?,
        val entryPoint: String         // "thumbs_up" / "thumbs_down"
    ) {
        fun toReasonsList(): List<String> {
            val list = mutableListOf<String>()
            pickupRating?.let { list.add("pickup_$it") }
            deliveryRating?.let { list.add("delivery_$it") }
            priceRating?.let { list.add("price_$it") }
            judgmentRating?.let { list.add("judgment_$it") }
            return list
        }
    }

    private val C_GOOD = Color.parseColor("#2ECC71")
    private val C_BAD = Color.parseColor("#E74C3C")
    private val C_OFF = Color.parseColor("#E8E8E8")
    private val C_TEXT = Color.parseColor("#333333")
    private val C_SUB = Color.parseColor("#888888")
    private val C_DIVIDER = Color.parseColor("#DDDDDD")

    private class RatingHolder(var rating: String? = null)

    fun show(
        context: Context,
        entryPoint: String,
        onSave: (MatrixResult) -> Unit
    ) {
        try {
            val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(8))
            }

            // 헤더: 빈칸 / 좋음 / 나쁨
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1.2f)
                })
                addView(TextView(context).apply {
                    text = "좋음"; textSize = 13f; setTextColor(C_SUB); gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(context).apply {
                    text = "나쁨"; textSize = 13f; setTextColor(C_SUB); gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            })

            // 세부 3항목
            val pickup = RatingHolder()
            val delivery = RatingHolder()
            val price = RatingHolder()

            root.addView(makeRow(context, dp, "픽업 위치", pickup))
            root.addView(makeRow(context, dp, "배달 위치", delivery))
            root.addView(makeRow(context, dp, "단가/금액", price))

            // 구분선
            root.addView(View(context).apply {
                setBackgroundColor(C_DIVIDER)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { setMargins(0, dp(12), 0, dp(12)) }
            })

            // 판정 자체 (별도 섹션)
            val judgment = RatingHolder()
            root.addView(makeRow(context, dp, "판정 자체", judgment))

            val dialog = AlertDialog.Builder(context)
                .setTitle("이 콜 어땠나요?")
                .setView(root)
                .setPositiveButton("저장", null)
                .setNegativeButton("취소", null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val result = MatrixResult(
                        pickupRating = pickup.rating,
                        deliveryRating = delivery.rating,
                        priceRating = price.rating,
                        judgmentRating = judgment.rating,
                        entryPoint = entryPoint
                    )
                    onSave(result)
                    dialog.dismiss()
                }
            }

            dialog.show()
        } catch (_: Exception) {}
    }

    private fun makeRow(
        context: Context,
        dp: (Int) -> Int,
        label: String,
        holder: RatingHolder
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))

            addView(TextView(context).apply {
                text = label; textSize = 15f; setTextColor(C_TEXT)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
            })

            val goodBtn = TextView(context).apply {
                setBackgroundColor(C_OFF); gravity = Gravity.CENTER; minHeight = dp(44)
            }
            val badBtn = TextView(context).apply {
                setBackgroundColor(C_OFF); gravity = Gravity.CENTER; minHeight = dp(44)
            }

            goodBtn.setOnClickListener {
                if (holder.rating == "GOOD") {
                    holder.rating = null; goodBtn.setBackgroundColor(C_OFF)
                } else {
                    holder.rating = "GOOD"; goodBtn.setBackgroundColor(C_GOOD); badBtn.setBackgroundColor(C_OFF)
                }
            }

            badBtn.setOnClickListener {
                if (holder.rating == "BAD") {
                    holder.rating = null; badBtn.setBackgroundColor(C_OFF)
                } else {
                    holder.rating = "BAD"; badBtn.setBackgroundColor(C_BAD); goodBtn.setBackgroundColor(C_OFF)
                }
            }

            addView(goodBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
            addView(badBtn, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
    }
}
