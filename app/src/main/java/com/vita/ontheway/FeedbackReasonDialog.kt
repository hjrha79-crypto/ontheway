package com.vita.ontheway

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast

/** v3.19: 피드백 이유 선택 팝업 (다중 선택) */
object FeedbackReasonDialog {

    private val REASON_LABELS = arrayOf(
        "픽업 위치", "배달 위치", "경로·방향", "단가·금액", "추천 판정 자체", "기타"
    )
    private val REASON_CODES = arrayOf(
        "pickup", "delivery", "route", "price", "verdict", "other"
    )

    fun show(
        context: Context,
        isUp: Boolean,
        onSave: (List<String>) -> Unit
    ) {
        try {
            val title = if (isUp) "좋은 점 선택 (여러 개 가능)" else "아쉬운 점 선택 (여러 개 가능)"
            val checked = BooleanArray(REASON_LABELS.size)

            AlertDialog.Builder(context)
                .setTitle(title)
                .setMultiChoiceItems(REASON_LABELS, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("저장") { _, _ ->
                    val selected = mutableListOf<String>()
                    checked.forEachIndexed { i, v -> if (v) selected.add(REASON_CODES[i]) }
                    onSave(selected)
                }
                .setNegativeButton("취소", null)
                .show()
        } catch (_: Exception) {
            // 크래시 금지
        }
    }
}
