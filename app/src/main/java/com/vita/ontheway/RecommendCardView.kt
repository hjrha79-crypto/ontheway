package com.vita.ontheway

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView

// ──────────────────────────────────────────
// RecommendCardView v1.1
// 목표: 0.5초 판단 + 1초 실행
// 추가: 이유 압축 / 리스크 경고 / 다음 콜 힌트
// ──────────────────────────────────────────

class RecommendCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val C_BG      = VitaColors.BG
    private val C_SURFACE = VitaColors.SURFACE
    private val C_ACCENT  = VitaColors.ACCENT2
    private val C_TEXT    = VitaColors.TEXT2
    private val C_MUTED   = VitaColors.MUTED
    private val C_URGENT  = VitaColors.URGENT
    private val C_WARN    = VitaColors.WARN
    private val C_HINT    = VitaColors.HINT_BG

    private val dp = context.resources.displayMetrics.density
    fun Int.dp() = (this * dp).toInt()

    private var onExecute: ((RecommendedCall) -> Unit)? = null
    private var onSkip: (() -> Unit)? = null
    private var onDetail: ((RecommendedCall) -> Unit)? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(C_BG)
        setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
    }

    fun bind(
        calls: List<RecommendedCall>,
        driverDirection: String = "",
        cancelCount: Int = 0,
        onExecute: (RecommendedCall) -> Unit,
        onSkip: () -> Unit,
        onDetail: ((RecommendedCall) -> Unit)? = null
    ) {
        this.onExecute = onExecute
        this.onSkip = onSkip
        this.onDetail = onDetail
        stopTimer()
        removeAllViews()
        calls.take(3).forEach { call ->
            val meta = CallCardMetaBuilder.build(
                call = call, driverDirection = driverDirection,
                cancelCount = cancelCount, pickupDistanceKm = call.distanceKm
            )
            addView(buildCard(call, meta))
        }
        addView(buildSkipButton())
    }

    fun stopTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
    }

    private fun buildCard(call: RecommendedCall, meta: CallCardMeta): View {
        val isTop = call.rank == 1
        val isUrgent = call.isUrgent

        val card = CardView(context).apply {
            radius = 14.dp().toFloat()
            cardElevation = if (isTop) 12.dp().toFloat() else 0f
            setCardBackgroundColor(when { isUrgent -> Color.parseColor("#1f0909"); isTop -> Color.parseColor("#091f15"); else -> C_SURFACE })
            alpha = if (isTop || isUrgent) 1.0f else 0.5f
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10.dp() }
        }

        val inner = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(16.dp(), 14.dp(), 16.dp(), if (isTop || isUrgent) 14.dp() else 12.dp())
        }

        // ① 배지 + 타이머 (Top1만) + ▶ 상세
        val badgeRow = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8.dp() }
        }
        badgeRow.addView(TextView(context).apply {
            text = when { isUrgent -> "⚡ 급송"; isTop -> "● 1위 추천"; else -> "${call.rank}순위" }
            textSize = 10f
            setTextColor(when { isUrgent -> C_URGENT; isTop -> C_ACCENT; else -> C_MUTED })
            typeface = Typeface.MONOSPACE
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        // 12초 카운트다운 (Top1/급송만)
        if (isTop || isUrgent) {
            val timerText = TextView(context).apply {
                textSize = 12f; typeface = Typeface.MONOSPACE
                setTextColor(C_ACCENT)
                text = "12s"
            }
            badgeRow.addView(timerText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = 10.dp() })
            startCountdown(timerText, 12)
        }
        // ▶ 상세 버튼
        if (isTop || isUrgent) {
            badgeRow.addView(TextView(context).apply {
                text = "▶"; textSize = 16f; setTextColor(C_MUTED)
                setPadding(8.dp(), 2.dp(), 0, 2.dp())
                setOnClickListener { onDetail?.invoke(call) }
            })
        }
        inner.addView(badgeRow)

        // ② 경로
        inner.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = call.pickup; textSize = if (isTop) 15f else 13f; setTextColor(C_TEXT); setTypeface(null, Typeface.BOLD) })
            addView(TextView(context).apply { text = "  →  "; textSize = 11f; setTextColor(C_MUTED) })
            addView(TextView(context).apply {
                text = call.dropoff; textSize = if (isTop) 15f else 13f
                setTextColor(when { isUrgent -> C_URGENT; isTop -> C_ACCENT; else -> Color.parseColor("#7a9ab4") })
                setTypeface(null, Typeface.BOLD)
            })
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8.dp() }
        })

        // ③ 금액
        inner.addView(TextView(context).apply {
            text = "${String.format("%,d", call.price)}원"
            textSize = if (isTop || isUrgent) 26f else 18f
            setTextColor(if (isTop || isUrgent) C_TEXT else C_MUTED)
            setTypeface(null, Typeface.BOLD); typeface = Typeface.MONOSPACE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8.dp() }
        })

        // ④ 이유 압축
        inner.addView(TextView(context).apply {
            text = meta.reason; textSize = 12f
            setTextColor(when { isUrgent -> Color.parseColor("#ff8080"); isTop -> Color.parseColor("#00c885"); else -> C_MUTED })
            setPadding(10.dp(), 7.dp(), 10.dp(), 7.dp())
            setBackgroundColor(when { isUrgent -> Color.parseColor("#1a0505"); isTop -> Color.parseColor("#091a10"); else -> Color.parseColor("#090e14") })
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6.dp() }
        })

        // ⑤ 리스크 경고 (있을 때만)
        meta.riskWarning?.let {
            inner.addView(TextView(context).apply {
                text = it; textSize = 11f; setTextColor(C_WARN)
                setPadding(10.dp(), 5.dp(), 10.dp(), 5.dp())
                setBackgroundColor(Color.parseColor("#1a1200"))
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6.dp() }
            })
        }

        // ⑥ 다음 콜 힌트 (1위만)
        if (isTop) {
            meta.nextHint?.let {
                inner.addView(TextView(context).apply {
                    text = it; textSize = 11f; setTextColor(Color.parseColor("#4a8a6e"))
                    setPadding(10.dp(), 5.dp(), 10.dp(), 5.dp())
                    setBackgroundColor(C_HINT)
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12.dp() }
                })
            }
        }

        // ⑦ 실행 버튼 — 1위/급송만
        if (isTop || isUrgent) {
            inner.addView(TextView(context).apply {
                text = if (isUrgent) "⚡ 이 콜 실행 (급송)" else "이 콜 실행"
                textSize = 15f
                setTextColor(if (isUrgent) Color.WHITE else Color.parseColor("#001a0d"))
                setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
                setPadding(0, 14.dp(), 0, 14.dp())
                setBackgroundColor(if (isUrgent) C_URGENT else C_ACCENT)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                setOnClickListener { onExecute?.invoke(call) }
            })
        }

        card.addView(inner)
        return card
    }

    private fun startCountdown(timerText: TextView, seconds: Int) {
        var remaining = seconds
        timerText.text = "${remaining}s"
        timerText.setTextColor(C_ACCENT)
        val tick = object : Runnable {
            override fun run() {
                remaining--
                if (remaining > 0) {
                    timerText.text = "${remaining}s"
                    timerText.setTextColor(
                        if (remaining <= 5) Color.parseColor("#FF5555") else C_ACCENT
                    )
                    timerHandler.postDelayed(this, 1000)
                } else {
                    timerText.text = "0s"
                    timerText.setTextColor(Color.parseColor("#FF5555"))
                }
            }
        }
        timerRunnable = tick
        timerHandler.postDelayed(tick, 1000)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopTimer()
    }

    private fun buildSkipButton(): View = TextView(context).apply {
        text = "건너뛰기"; textSize = 13f; setTextColor(C_MUTED); gravity = Gravity.CENTER
        setPadding(0, 14.dp(), 0, 14.dp()); setBackgroundColor(Color.TRANSPARENT)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp() }
        setOnClickListener { onSkip?.invoke() }
    }
}
