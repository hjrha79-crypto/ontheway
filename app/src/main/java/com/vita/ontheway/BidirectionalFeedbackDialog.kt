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
import android.widget.ScrollView
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
        val distanceDiffKm: Float? = null,
        val memo: String? = null       // 자유 메모
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

    /**
     * 2단계 피드백: 1차 👍/👎 → 2차 (👎만 4축 상세).
     * 👍 = 즉시 저장 + 토스트.
     * 👎 = 기존 4축 show() 호출.
     */
    fun showThumbsFirst(
        context: Context,
        platform: String = "",
        platformDistanceKm: Float? = null,
        onthewayDistanceKm: Float? = null,
        onSave: (MatrixResult) -> Unit
    ) {
        try {
            val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(20), dp(24), dp(16))
                setBackgroundColor(Color.WHITE)
            }

            root.addView(TextView(context).apply {
                text = "이 콜 어땠나요?"
                textSize = 18f; setTextColor(C_TEXT); gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(24))
            })

            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            val dialog = AlertDialog.Builder(context)
                .setView(root)
                .setCancelable(true)
                .create()

            // 👍 버튼
            val upBtn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#F0FFF0"))
                setPadding(dp(16), dp(16), dp(16), dp(16))
                minimumHeight = dp(120)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    val result = MatrixResult(
                        pickupRating = null, deliveryRating = null,
                        priceRating = null, judgmentRating = null,
                        entryPoint = "thumbs_up"
                    )
                    onSave(result)
                    OtwFileLogger.log("Feedback", "👍 즉시 저장")
                    android.widget.Toast.makeText(context, "👍 기록됨", android.widget.Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            upBtn.addView(TextView(context).apply {
                text = "\uD83D\uDC4D"; textSize = 40f; gravity = Gravity.CENTER
            })
            upBtn.addView(TextView(context).apply {
                text = "좋음"; textSize = 14f; setTextColor(C_TEXT); gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })

            // 👎 버튼
            val downBtn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#FFF0F0"))
                setPadding(dp(16), dp(16), dp(16), dp(16))
                minimumHeight = dp(120)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    // 2차: 4축 상세 ���이얼로그
                    show(context, "thumbs_down",
                        platform = platform,
                        platformDistanceKm = platformDistanceKm,
                        onthewayDistanceKm = onthewayDistanceKm,
                        onSave = onSave)
                }
            }
            downBtn.addView(TextView(context).apply {
                text = "\uD83D\uDC4E"; textSize = 40f; gravity = Gravity.CENTER
            })
            downBtn.addView(TextView(context).apply {
                text = "나쁨"; textSize = 14f; setTextColor(C_TEXT); gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })

            btnRow.addView(upBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            btnRow.addView(downBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            root.addView(btnRow)

            // 힌트 텍스트
            root.addView(TextView(context).apply {
                text = "나쁨을 누르면 상세 의견을 남길 수 있어요"
                textSize = 11f; setTextColor(C_SUB); gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
            })

            // 취소 시 👎 기본 저장
            dialog.setOnCancelListener {
                val result = MatrixResult(
                    pickupRating = null, deliveryRating = null,
                    priceRating = null, judgmentRating = null,
                    entryPoint = "thumbs_down"
                )
                onSave(result)
                OtwFileLogger.log("Feedback", "취소 → 👎 기본 저장")
            }

            dialog.show()
        } catch (_: Exception) {}
    }

    fun show(
        context: Context,
        entryPoint: String,
        platform: String = "",
        platformDistanceKm: Float? = null,
        onthewayDistanceKm: Float? = null,
        existing: FeedbackEntry? = null,
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

            // 세부 3항목 (기존값 복원)
            val pickup = RatingHolder(existing?.pickupRating)
            val delivery = RatingHolder(existing?.deliveryRating)
            val price = RatingHolder(existing?.priceRating)

            val pickupRow = makeRow(context, dp, "픽업 위치", pickup, "가까움", "주의")
            val deliveryRow = makeRow(context, dp, "배달 위치", delivery, "좋음", "외지")
            val priceRow = makeRow(context, dp, "단가/금액", price, "좋음", "나쁨")

            root.addView(pickupRow.first)
            root.addView(deliveryRow.first)
            root.addView(priceRow.first)

            val detailRows = listOf(pickupRow.second, deliveryRow.second, priceRow.second)

            // 기존값 버튼 색상 복원
            for (row in detailRows) {
                when (row.holder.rating) {
                    "GOOD" -> row.goodBtn.setBackgroundColor(C_GOOD)
                    "BAD" -> row.badBtn.setBackgroundColor(C_BAD)
                }
            }

            // 구분선
            root.addView(View(context).apply {
                setBackgroundColor(C_DIVIDER)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { setMargins(0, dp(12), 0, dp(12)) }
            })

            // 판정 자체 (별도 섹션)
            val judgment = RatingHolder(existing?.judgmentRating)
            val judgmentRow = makeRow(context, dp, "판정 자체", judgment, "적절", "불만")
            root.addView(judgmentRow.first)

            val judgmentButtons = judgmentRow.second
            when (judgment.rating) {
                "GOOD" -> judgmentButtons.goodBtn.setBackgroundColor(C_GOOD)
                "BAD" -> judgmentButtons.badBtn.setBackgroundColor(C_BAD)
            }

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
                    existing?.platformDistanceKm?.let { setText("%.1f".format(it)) }
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

            // 자유 메모
            root.addView(View(context).apply {
                setBackgroundColor(C_DIVIDER)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { setMargins(0, dp(12), 0, dp(12)) }
            })
            val memoInput = EditText(context).apply {
                hint = "다른 의견 (선택)"; textSize = 13f; setTextColor(C_TEXT); setHintTextColor(C_SUB)
                minLines = 2; maxLines = 4
                setBackgroundColor(Color.parseColor("#F5F5F5"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            root.addView(memoInput)

            val scrollView = ScrollView(context).apply {
                addView(root)
            }

            val dialog = AlertDialog.Builder(context)
                .setTitle("이 콜 어땠나요?")
                .setView(scrollView)
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
                    val memoText = memoInput.text.toString().trim().takeIf { it.isNotEmpty() }
                    val result = MatrixResult(
                        pickupRating = pickup.rating,
                        deliveryRating = delivery.rating,
                        priceRating = price.rating,
                        judgmentRating = judgment.rating,
                        entryPoint = entryPoint,
                        platformDistanceKm = effectivePlatformDist,
                        onthewayDistanceKm = onthewayDistanceKm,
                        distanceDiffKm = diffKm,
                        memo = memoText
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
        holder: RatingHolder,
        goodLabel: String = "좋음",
        badLabel: String = "나쁨"
    ): Pair<LinearLayout, RowButtons> {
        val goodBtn = TextView(context).apply {
            text = goodLabel; textSize = 14f; setTextColor(C_TEXT)
            setBackgroundColor(C_OFF); gravity = Gravity.CENTER; minHeight = dp(44)
        }
        val badBtn = TextView(context).apply {
            text = badLabel; textSize = 14f; setTextColor(C_TEXT)
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
