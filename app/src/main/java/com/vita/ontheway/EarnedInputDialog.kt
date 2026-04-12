package com.vita.ontheway

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.WindowManager
import android.widget.*

// ──────────────────────────────────────────
// EarnedInputDialog
// 콜 완료 후 실제 수익 입력
// 목적: actualEarnedWon 누락 방지
// ──────────────────────────────────────────
// 사용법:
// EarnedInputDialog.show(context, sessionId, expectedWon) { actual ->
//     SessionStore.updateEarned(context, sessionId, actual,
//         ResultEvaluator.evaluate(actual, expectedWon))
// }
// ──────────────────────────────────────────

object EarnedInputDialog {

    private val C_BG      = Color.parseColor("#0d1117")
    private val C_ACCENT  = Color.parseColor("#00e5a0")
    private val C_TEXT    = Color.parseColor("#dce8f0")
    private val C_MUTED   = Color.parseColor("#4a5a6e")
    private val C_SURFACE = Color.parseColor("#111820")

    fun show(
        context: Context,
        sessionId: String,
        expectedWon: Int,
        onConfirm: (actualEarnedWon: Int) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        val dp = context.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
            setPadding(20.dp(), 24.dp(), 20.dp(), 20.dp())
        }

        // 타이틀
        root.addView(TextView(context).apply {
            text = "콜 완료"
            textSize = 15f
            setTextColor(C_ACCENT)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4.dp() }
        })

        // 예상 수익 표시
        root.addView(TextView(context).apply {
            text = "예상 수익: ${expectedWon.toFormattedWon()}"
            textSize = 12f
            setTextColor(C_MUTED)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp() }
        })

        // 실제 수익 입력
        root.addView(TextView(context).apply {
            text = "실제 수익 입력"
            textSize = 12f
            setTextColor(C_MUTED)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.dp() }
        })

        val input = EditText(context).apply {
            hint = "예: 18400"
            textSize = 18f
            setTextColor(C_TEXT)
            setHintTextColor(C_MUTED)
            setBackgroundColor(C_SURFACE)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20.dp() }
        }
        root.addView(input)

        // 빠른 입력 버튼 (자주 쓰는 금액)
        root.addView(TextView(context).apply {
            text = "빠른 입력"
            textSize = 11f
            setTextColor(C_MUTED)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.dp() }
        })

        val quickRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20.dp() }
        }
        listOf(8000, 10000, 15000, 20000).forEach { amount ->
            quickRow.addView(TextView(context).apply {
                text = "${amount / 1000}천"
                textSize = 12f
                setTextColor(C_ACCENT)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#091a10"))
                setPadding(0, 8.dp(), 0, 8.dp())
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 6.dp()
                }
                setOnClickListener { input.setText(amount.toString()) }
            })
        }
        root.addView(quickRow)

        // 버튼 행
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 나중에 입력
        btnRow.addView(TextView(context).apply {
            text = "나중에"
            textSize = 13f
            setTextColor(C_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, 12.dp(), 0, 12.dp())
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8.dp()
            }
            setOnClickListener { dialog.dismiss() }
        })

        // 확인
        btnRow.addView(TextView(context).apply {
            text = "확인"
            textSize = 14f
            setTextColor(Color.parseColor("#001a0d"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 12.dp(), 0, 12.dp())
            setBackgroundColor(C_ACCENT)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            setOnClickListener {
                val text = input.text.toString().trim()
                val amount = text.toIntOrNull()
                if (amount == null || amount <= 0) {
                    Toast.makeText(context, "금액을 입력해 주세요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                onConfirm(amount)
                dialog.dismiss()
            }
        })

        root.addView(btnRow)
        dialog.setContentView(root)
        dialog.show()
    }
}
