package com.vita.ontheway

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
 * 판정자체 ↔ 세부항목(픽업/배달/단가) 상호 배타.
 */
object BidirectionalFeedbackDialog {

    data class MatrixResult(
        val pickupRating: String?,     // "GOOD" / "BAD" / null
        val deliveryRating: String?,
        val priceRating: String?,
        val judgmentRating: String?,
        val entryPoint: String,        // "thumbs_up" / "thumbs_down"
        val platformDistanceKm: Float? = null,
        val onthewayDistanceKm: Float? = null,
        val distanceDiffKm: Float? = null
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

    private class RowButtons(val goodBtn: TextView, val badBtn: TextView, val holder: RatingHolder)

    fun show(
        context: Context,
        entryPoint: String,
        platform: String = "",
        platformDistanceKm: Float? = null,
        onthewayDistanceKm: Float? = null,
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

            val pickupRow = makeRow(context, dp, "픽업 위치", pickup)
            val deliveryRow = makeRow(context, dp, "배달 위치", delivery)
            val priceRow = makeRow(context, dp, "단가/금액", price)

            root.addView(pickupRow.first)
            root.addView(deliveryRow.first)
            root.addView(priceRow.first)

            val detailRows = listOf(pickupRow.second, deliveryRow.second, priceRow.second)

            // 구분선
            root.addView(View(context).apply {
                setBackgroundColor(C_DIVIDER)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { setMargins(0, dp(12), 0, dp(12)) }
            })

            // 판정 자체 (별도 섹션)
            val judgment = RatingHolder()
            val judgmentRow = makeRow(context, dp, "판정 자체", judgment)
            root.addView(judgmentRow.first)

            val judgmentButtons = judgmentRow.second

            // 상호 배타 로직: 판정자체 ↔ 세부항목
            fun clearDetailRows() {
                for (row in detailRows) {
                    row.holder.rating = null
                    row.goodBtn.setBackgroundColor(C_OFF)
                    row.badBtn.setBackgroundColor(C_OFF)
                }
            }

            fun clearJudgmentRow() {
                judgmentButtons.holder.rating = null
                judgmentButtons.goodBtn.setBackgroundColor(C_OFF)
                judgmentButtons.badBtn.setBackgroundColor(C_OFF)
            }

            // 판정자체 버튼에 상호 배타 후처리 추가
            val origJudgGood = judgmentButtons.goodBtn
            val origJudgBad = judgmentButtons.badBtn
            origJudgGood.setOnClickListener {
                if (judgment.rating == "GOOD") {
                    judgment.rating = null; origJudgGood.setBackgroundColor(C_OFF)
                } else {
                    judgment.rating = "GOOD"; origJudgGood.setBackgroundColor(C_GOOD); origJudgBad.setBackgroundColor(C_OFF)
                    clearDetailRows()
                }
            }
            origJudgBad.setOnClickListener {
                if (judgment.rating == "BAD") {
                    judgment.rating = null; origJudgBad.setBackgroundColor(C_OFF)
                } else {
                    judgment.rating = "BAD"; origJudgBad.setBackgroundColor(C_BAD); origJudgGood.setBackgroundColor(C_OFF)
                    clearDetailRows()
                }
            }

            // 세부항목 버튼에 판정자체 해제 후처리 추가
            for (row in detailRows) {
                val goodBtn = row.goodBtn
                val badBtn = row.badBtn
                val holder = row.holder
                goodBtn.setOnClickListener {
                    if (holder.rating == "GOOD") {
                        holder.rating = null; goodBtn.setBackgroundColor(C_OFF)
                    } else {
                        holder.rating = "GOOD"; goodBtn.setBackgroundColor(C_GOOD); badBtn.setBackgroundColor(C_OFF)
                        clearJudgmentRow()
                    }
                }
                badBtn.setOnClickListener {
                    if (holder.rating == "BAD") {
                        holder.rating = null; badBtn.setBackgroundColor(C_OFF)
                    } else {
                        holder.rating = "BAD"; badBtn.setBackgroundColor(C_BAD); goodBtn.setBackgroundColor(C_OFF)
                        clearJudgmentRow()
                    }
                }
            }

            // 배민 표시 거리 입력 (배민일 때만)
            var distanceInput: EditText? = null
            if (platform == "baemin") {
                root.addView(View(context).apply {
                    setBackgroundColor(C_DIVIDER)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                    ).apply { setMargins(0, dp(12), 0, dp(12)) }
                })
                val distRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                distRow.addView(TextView(context).apply {
                    text = "배민 표시 거리 (선택)"; textSize = 14f; setTextColor(C_TEXT)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
                })
                distanceInput = EditText(context).apply {
                    hint = "예: 3.2"; textSize = 14f; setTextColor(C_TEXT); setHintTextColor(C_SUB)
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setBackgroundColor(Color.parseColor("#F5F5F5"))
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f).apply {
                        marginEnd = dp(4)
                    }
                }
                distRow.addView(distanceInput)
                distRow.addView(TextView(context).apply {
                    text = "km"; textSize = 14f; setTextColor(C_SUB)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                root.addView(distRow)
            }

            val dialog = AlertDialog.Builder(context)
                .setTitle("이 콜 어땠나요?")
                .setView(root)
                .setPositiveButton("저장", null)
                .setNegativeButton("취소", null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val userInputDist = distanceInput?.text?.toString()?.trim()
                        ?.toFloatOrNull()
                    val effectivePlatformDist = userInputDist ?: platformDistanceKm
                    val diffKm = if (effectivePlatformDist != null && onthewayDistanceKm != null)
                        effectivePlatformDist - onthewayDistanceKm else null
                    val result = MatrixResult(
                        pickupRating = pickup.rating,
                        deliveryRating = delivery.rating,
                        priceRating = price.rating,
                        judgmentRating = judgment.rating,
                        entryPoint = entryPoint,
                        platformDistanceKm = effectivePlatformDist,
                        onthewayDistanceKm = onthewayDistanceKm,
                        distanceDiffKm = diffKm
                    )
                    if (userInputDist != null) {
                        val otwStr = if (onthewayDistanceKm != null) "${"%.1f".format(onthewayDistanceKm)}km" else "N/A"
                        val diffStr = if (diffKm != null) "${"%.1f".format(diffKm)}km" else "N/A"
                        OtwFileLogger.log("Feedback", "배민 표시 거리 입력: ${"%.1f".format(userInputDist)}km / GPS 이동: $otwStr / 차이: $diffStr")
                    }
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
    ): Pair<LinearLayout, RowButtons> {
        val goodBtn = TextView(context).apply {
            text = "O"; textSize = 16f; setTextColor(C_TEXT)
            setBackgroundColor(C_OFF); gravity = Gravity.CENTER; minHeight = dp(44)
        }
        val badBtn = TextView(context).apply {
            text = "O"; textSize = 16f; setTextColor(C_TEXT)
            setBackgroundColor(C_OFF); gravity = Gravity.CENTER; minHeight = dp(44)
        }

        // Default click listeners (may be overridden for mutual exclusion)
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

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))

            addView(TextView(context).apply {
                text = label; textSize = 15f; setTextColor(C_TEXT)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
            })

            addView(goodBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
            addView(badBtn, LinearLayout.LayoutParams(0, dp(44), 1f))
        }

        return Pair(row, RowButtons(goodBtn, badBtn, holder))
    }
}
