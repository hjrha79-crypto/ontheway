package com.vita.ontheway

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

/** v3.21: 피드백 이유 선택 팝업 (배타 로직 포함) */
object FeedbackReasonDialog {

    // 세부 항목 (중복 선택)
    private val DETAIL_LABELS = arrayOf("픽업 위치", "배달 위치", "단가·금액", "기타")
    private val DETAIL_CODES = arrayOf("pickup", "delivery", "price", "other")

    // 전체 판정 (단독)
    private const val VERDICT_LABEL = "추천 판정 자체"
    private const val VERDICT_CODE = "verdict"

    fun show(
        context: Context,
        isUp: Boolean,
        onSave: (List<String>) -> Unit
    ) {
        try {
            val title = if (isUp) "좋은 점 선택" else "아쉬운 점 선택"
            val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(8))
            }

            // 세부 항목 섹션 헤더
            root.addView(TextView(context).apply {
                text = "세부 항목 (중복 선택)"
                textSize = 13f
                setTextColor(Color.parseColor("#888888"))
                setPadding(0, 0, 0, dp(4))
            })

            val detailBoxes = DETAIL_LABELS.map { label ->
                CheckBox(context).apply {
                    text = label
                    textSize = 15f
                    setTextColor(Color.parseColor("#333333"))
                }.also { root.addView(it) }
            }

            // 구분선
            root.addView(LinearLayout(context).apply {
                setBackgroundColor(Color.parseColor("#DDDDDD"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { setMargins(0, dp(12), 0, dp(12)) }
            })

            // 전체 판정 섹션 헤더
            root.addView(TextView(context).apply {
                text = "전체 판정 (단독)"
                textSize = 13f
                setTextColor(Color.parseColor("#888888"))
                setPadding(0, 0, 0, dp(4))
            })

            val verdictBox = CheckBox(context).apply {
                text = VERDICT_LABEL
                textSize = 15f
                setTextColor(Color.parseColor("#333333"))
            }
            root.addView(verdictBox)

            // 배타 로직: "추천 판정 자체" ↔ 세부 항목
            verdictBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    detailBoxes.forEach { it.isChecked = false }
                }
            }
            detailBoxes.forEach { box ->
                box.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        verdictBox.isChecked = false
                    }
                }
            }

            val dialog = AlertDialog.Builder(context)
                .setTitle(title)
                .setView(root)
                .setPositiveButton("저장", null)  // null → 수동 제어
                .setNegativeButton("취소", null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val selected = mutableListOf<String>()
                    detailBoxes.forEachIndexed { i, cb ->
                        if (cb.isChecked) selected.add(DETAIL_CODES[i])
                    }
                    if (verdictBox.isChecked) selected.add(VERDICT_CODE)

                    onSave(selected)
                    dialog.dismiss()
                }
            }

            dialog.show()
        } catch (_: Exception) {
            // 크래시 금지
        }
    }
}
