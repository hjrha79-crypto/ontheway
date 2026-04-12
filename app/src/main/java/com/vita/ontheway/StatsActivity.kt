package com.vita.ontheway

import android.os.Bundle
import android.widget.*
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import android.graphics.*
import android.graphics.drawable.*

class StatsActivity : AppCompatActivity() {

    private val C_BG      = Color.parseColor("#F5F5F5")
    private val C_CARD    = Color.WHITE
    private val C_ACCENT  = Color.parseColor("#5B6ABF")
    private val C_TEXT    = Color.parseColor("#111111")
    private val C_SUB     = Color.parseColor("#999999")
    private val C_GREEN   = Color.parseColor("#2EAA5E")
    private val C_RED     = Color.parseColor("#E04040")

    private fun pill(color: Int, r: Float = 16f) =
        GradientDrawable().apply { setColor(color); cornerRadius = r }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    private fun fmt(n: Int) = String.format("%,d", n)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = ScrollView(this).apply { setBackgroundColor(C_BG) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(0), dp(16), dp(40))
        }
        root.addView(layout)

        // 헤더
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), dp(44), dp(16), dp(14))
        }
        headerRow.addView(TextView(this).apply {
            text = "←"; textSize = 20f; setTextColor(C_TEXT)
            setPadding(dp(8), 0, dp(16), 0)
            setOnClickListener { finish() }
        })
        headerRow.addView(TextView(this).apply {
            text = "수익 통계"; textSize = 18f
            setTypeface(null, Typeface.BOLD); setTextColor(C_TEXT)
        })
        layout.addView(headerRow, lp(MP, WC).apply { setMargins(dp(-16), 0, dp(-16), dp(16)) })

        // 오늘 수익 카드
        val todayEarning = EarningManager.getTodayEarning(this)
        val todayGoal = EarningManager.getGoal(this)
        val fuelCost = if (todayEarning > 0) EarningManager.getFuelCost(this, 50.0) else 0
        val netEarning = todayEarning - fuelCost
        val vehicle = EarningManager.getVehicleType(this)

        layout.addView(makeCard(
            "오늘 수익",
            listOf(
                "총 수익" to "${fmt(todayEarning)}원",
                "연료비 예상 ($vehicle)" to if (fuelCost > 0) "-${fmt(fuelCost)}원" else "0원",
                "실수익 예상" to "${fmt(netEarning)}원",
                "목표 대비" to "${if (todayGoal > 0) (todayEarning * 100 / todayGoal) else 0}%"
            ),
            highlight = "${fmt(netEarning)}원"
        ))

        // 결과 비교 카드
        val entries = ShadowLog.getAll(this)
        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val todayEntries = entries.filter { it.timestamp.startsWith(todayStr) }
        val todayTotalLoss = todayEntries.sumOf { it.lossWon }
        val aiEarning = todayEarning + todayTotalLoss
        layout.addView(makeCompareCard(todayEarning, aiEarning, todayTotalLoss))

        // Shadow Mode 통계
        val totalRec = todayEntries.size
        val top1Hits = todayEntries.count { it.top1Hit }
        val top3Hits = todayEntries.count { it.top3Hit }
        val top1Rate = if (totalRec > 0) (top1Hits * 100 / totalRec) else 0
        val avgLoss = if (totalRec > 0) todayEntries.sumOf { it.lossWon } / totalRec else 0

        layout.addView(makeCard(
            "오늘 AI 추천 통계",
            listOf(
                "총 추천" to "${totalRec}콜",
                "Top1 적중" to "${top1Hits}콜",
                "Top3 포함" to "${top3Hits}콜",
                "Top1 적중률" to "${top1Rate}%",
                "평균 손실" to "${fmt(avgLoss)}원"
            ),
            highlight = "${top1Rate}%"
        ))

        // 전체 누적
        val allTotal = entries.size
        val allTop1Hits = entries.count { it.top1Hit }
        val allTop1Rate = if (allTotal > 0) (allTop1Hits * 100 / allTotal) else 0
        val totalLoss = entries.sumOf { it.lossWon }

        layout.addView(makeCard(
            "전체 누적 통계",
            listOf(
                "총 추천" to "${allTotal}콜",
                "Top1 적중률" to "${allTop1Rate}%",
                "AI 따랐으면" to "+${fmt(totalLoss)}원"
            ),
            highlight = "${allTop1Rate}%"
        ))

        // 최근 추천 기록
        layout.addView(TextView(this).apply {
            text = "최근 추천 기록"
            textSize = 13f; setTextColor(C_SUB)
            setPadding(dp(4), dp(16), 0, dp(10))
        })

        if (entries.isEmpty()) {
            layout.addView(TextView(this).apply {
                text = "아직 데이터가 없습니다\n카카오T 픽커를 켜주세요"
                textSize = 14f; setTextColor(C_SUB); gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, 0)
            })
        } else {
            entries.reversed().take(20).forEach { e ->
                val actionColor = when {
                    e.top1Hit -> C_GREEN
                    e.missedCall -> C_RED
                    else -> C_SUB
                }
                val actionText = when {
                    e.top1Hit -> "✔ 적중"
                    e.top3Hit -> "○ Top3"
                    e.missedCall -> "✖ 미스"
                    else -> "- 미확인"
                }
                val bestCall = e.availableCalls.firstOrNull { it.id == e.bestCallId }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = pill(C_CARD, 12f)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = lp(0, WC, 1f)
                    addView(TextView(this@StatsActivity).apply {
                        text = "${e.location} | ${bestCall?.direction ?: ""}"
                        textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(C_TEXT)
                    })
                    addView(TextView(this@StatsActivity).apply {
                        text = "${fmt(e.bestCallPrice)}원  |  손실 ${fmt(e.lossWon)}원"
                        textSize = 12f; setTextColor(C_SUB)
                    })
                })
                row.addView(TextView(this).apply {
                    text = actionText; textSize = 13f
                    setTypeface(null, Typeface.BOLD); setTextColor(actionColor)
                })
                layout.addView(row, lp(MP, WC).apply { setMargins(0, 0, 0, dp(6)) })
            }
        }

        setContentView(root)
    }

    private fun makeCompareCard(actual: Int, aiFollow: Int, diff: Int): LinearLayout {
        val isPositive = diff > 0
        val diffColor  = if (isPositive) C_GREEN else if (diff < 0) C_RED else C_SUB
        val diffStr    = if (diff == 0) "데이터 없음" else if (isPositive) "+${fmt(diff)}원" else "${fmt(diff)}원"
        val hasData    = actual > 0

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), if (isPositive) C_GREEN else Color.parseColor("#E0E0E0"))
            }
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        card.layoutParams = LinearLayout.LayoutParams(MP, WC).apply { setMargins(0, 0, 0, dp(12)) }

        card.addView(TextView(this).apply {
            text = "AI 따랐다면?"; textSize = 12f; setTextColor(C_SUB)
            setPadding(0, 0, 0, dp(10))
        })

        if (!hasData) {
            card.addView(TextView(this).apply {
                text = "오늘 운행 후 결과가 여기에 표시됩니다"
                textSize = 13f; setTextColor(C_SUB); gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
            })
        } else {
            card.addView(TextView(this).apply {
                text = diffStr; textSize = 36f
                setTypeface(null, Typeface.BOLD); setTextColor(diffColor)
                setPadding(0, 0, 0, dp(12))
            })
            listOf(
                "실제 수익" to "${fmt(actual)}원",
                "AI 추천 따랐을 때" to "${fmt(aiFollow)}원"
            ).forEach { (label, value) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(3), 0, dp(3))
                }
                row.addView(TextView(this).apply {
                    text = label; textSize = 13f; setTextColor(C_SUB)
                    layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                })
                row.addView(TextView(this).apply {
                    text = value; textSize = 13f
                    setTypeface(null, Typeface.BOLD); setTextColor(C_TEXT)
                })
                card.addView(row)
            }
            val msg = when {
                diff > 5000  -> "AI 추천을 더 믿어보세요 👍"
                diff > 0     -> "AI가 조금 더 좋은 콜을 잡았습니다"
                diff == 0    -> "AI와 동일한 선택을 했습니다"
                else         -> "오늘은 직접 판단이 더 나았습니다"
            }
            card.addView(TextView(this).apply {
                text = msg; textSize = 12f; setTextColor(C_SUB)
                setPadding(0, dp(10), 0, 0)
            })
        }
        return card
    }

    private fun makeCard(title: String, items: List<Pair<String, String>>, highlight: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(C_CARD, 16f)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        card.addView(TextView(this).apply {
            text = title; textSize = 12f; setTextColor(C_SUB)
            letterSpacing = 0.05f; setPadding(0, 0, 0, dp(8))
        })
        card.addView(TextView(this).apply {
            text = highlight; textSize = 32f
            setTypeface(null, Typeface.BOLD); setTextColor(C_TEXT)
            setPadding(0, 0, 0, dp(12))
        })
        items.forEach { (label, value) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = label; textSize = 13f; setTextColor(C_SUB)
                layoutParams = lp(0, WC, 1f)
            })
            row.addView(TextView(this).apply {
                text = value; textSize = 13f
                setTypeface(null, Typeface.BOLD); setTextColor(C_TEXT)
            })
            card.addView(row)
        }
        card.layoutParams = LinearLayout.LayoutParams(MP, WC).apply { setMargins(0, 0, 0, dp(12)) }
        return card
    }
}
