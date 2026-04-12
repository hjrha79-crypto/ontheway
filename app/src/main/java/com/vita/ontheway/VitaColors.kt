package com.vita.ontheway

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object VitaColors {
    val BG       = Color.parseColor("#000000")  // 순검정
    val SURFACE  = Color.parseColor("#111111")  // 카드 배경
    val CARD     = Color.parseColor("#1A1A1A")  // 카드 내부
    val BORDER   = Color.parseColor("#333333")  // 구분선
    val ACCENT   = Color.parseColor("#3399FF")  // 파란색 (버튼/강조)
    val ACCENT2  = Color.parseColor("#3399FF")  // 파란색 통일
    val TEXT     = Color.parseColor("#FFFFFF")  // 흰색 텍스트
    val TEXT2    = Color.parseColor("#FFFFFF")  // 흰색 텍스트
    val SUB      = Color.parseColor("#888888")  // 보조 텍스트
    val MUTED    = Color.parseColor("#555555")  // 흐린 텍스트
    val TEAL_DIM = Color.parseColor("#2277CC")  // 파란색 dim
    val URGENT   = Color.parseColor("#FF4444")  // 긴급 (빨간색)
    val WARN     = Color.parseColor("#FF9900")  // 경고 (주황)
    val GREEN    = Color.parseColor("#00CC66")  // 성공/긍정
    val RED      = Color.parseColor("#FF4444")  // 실패/부정
    val HINT_BG  = Color.parseColor("#1A1A1A")  // 힌트 배경
    val HINT_FG  = Color.parseColor("#888888")  // 힌트 텍스트
}

object VitaUI {
    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun roundedBg(color: Int, radiusDp: Float = 8f, context: Context): GradientDrawable {
        val r = context.dp(radiusDp.toInt()).toFloat()
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = r
        }
    }

    fun sectionTitle(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(VitaColors.MUTED)
        setTypeface(null, Typeface.BOLD)
        setPadding(0, context.dp(12), 0, context.dp(6))
    }

    fun label(context: Context, text: String, size: Float = 14f, color: Int = VitaColors.TEXT2): TextView =
        TextView(context).apply {
            this.text = text; textSize = size; setTextColor(color)
        }

    fun chip(context: Context, text: String, fg: Int = VitaColors.ACCENT, bg: Int = VitaColors.SURFACE): TextView =
        TextView(context).apply {
            this.text = text; textSize = 12f; setTextColor(fg)
            background = roundedBg(bg, 16f, context)
            setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
        }

    fun primaryButton(context: Context, text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text; textSize = 15f
            setTextColor(Color.parseColor("#000000"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedBg(VitaColors.ACCENT, 8f, context)
            setPadding(0, context.dp(14), 0, context.dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }

    fun secondaryButton(context: Context, text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text; textSize = 13f
            setTextColor(VitaColors.MUTED); gravity = Gravity.CENTER
            setPadding(0, context.dp(14), 0, context.dp(14))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }

    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(VitaColors.BORDER)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { topMargin = context.dp(8); bottomMargin = context.dp(8) }
    }

    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(VitaColors.SURFACE, 12f, context)
        setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = context.dp(8) }
    }
}
