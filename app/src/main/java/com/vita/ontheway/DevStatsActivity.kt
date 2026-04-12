package com.vita.ontheway

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

// ──────────────────────────────────────────
// DevStatsActivity — KPI 화면
// Dev Mode 전용 / User Mode 노출 금지
// ──────────────────────────────────────────
class DevStatsActivity : AppCompatActivity() {

    private val C_BG      = Color.parseColor("#07090f")
    private val C_SURFACE = Color.parseColor("#0d1117")
    private val C_BORDER  = Color.parseColor("#161d28")
    private val C_ACCENT  = Color.parseColor("#00e5a0")
    private val C_TEXT    = Color.parseColor("#dce8f0")
    private val C_MUTED   = Color.parseColor("#4a5a6e")
    private val C_WARN    = Color.parseColor("#f5a623")
    private val C_URGENT  = Color.parseColor("#ff4444")

    private val dp get() = resources.displayMetrics.density
    fun Int.dp() = (this * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setBackgroundColor(C_BG)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 20.dp(), 16.dp(), 40.dp())
        }

        val sessions = SessionStore.loadAll(this)
        val summary  = SessionStore.getDailySummary(this)

        // ── 헤더 ────────────────────────────
        container.addView(makeText("VITA · OnTheWay", 11f, C_MUTED, Typeface.MONOSPACE).apply {
            letterSpacing = 0.15f
            setPadding(0, 0, 0, 4.dp())
        })
        container.addView(makeText("Dev KPI 대시보드", 16f, C_ACCENT, null, Typeface.BOLD).apply {
            setPadding(0, 0, 0, 20.dp())
        })

        // ── KPI 카드 4개 ────────────────────
        container.addView(makeKpiGrid(sessions))

        // ── 수익 운영 KPI ────────────────────
        container.addView(makeSectionLabel("수익 운영 KPI"))
        container.addView(makeEarningKpiCard())

        // ── 데이터 품질 ────────────────────
        container.addView(makeSectionLabel("데이터 품질"))
        container.addView(makeDataQualityCard())

        // ── 하루 요약 ───────────────────────
        summary?.let {
            container.addView(makeSectionLabel("오늘 요약"))
            container.addView(makeSummaryCard(it))
        }

        // ── 최근 20개 세션 ──────────────────
        container.addView(makeSectionLabel("최근 ${minOf(sessions.size, 20)}건"))
        SessionStore.loadRecent(this, 20).forEach { session ->
            container.addView(makeSessionRow(session))
        }

        root.addView(container)
        setContentView(root)
    }

    // ── KPI 2x2 그리드 ──────────────────────
    private fun makeKpiGrid(sessions: List<RecommendationSession>): View {
        val acceptRate = SessionStore.getAcceptRate(sessions)
        val urgentRate = SessionStore.getUrgentRate(sessions)
        val avgLoss    = SessionStore.getAverageLossWon(sessions)
        val badRate    = SessionStore.getBadRate(sessions)

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp() }
        }

        // 1행
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
        }
        row1.addView(makeKpiCard("총 콜", "${sessions.size}건", C_TEXT, 1f))
        row1.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(8.dp(), 1) })
        row1.addView(makeKpiCard("추천 따라감", "${"%.0f".format(acceptRate)}%",
            if (acceptRate >= 85) C_ACCENT else C_WARN, 1f))

        // 2행
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row2.addView(makeKpiCard("평균 손실", avgLoss.toFormattedWon(),
            if (avgLoss <= 1000) C_ACCENT else C_WARN, 1f))
        row2.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(8.dp(), 1) })
        row2.addView(makeKpiCard("실패율", "${"%.0f".format(badRate)}%",
            if (badRate <= 15) C_ACCENT else C_URGENT, 1f))

        grid.addView(row1)
        grid.addView(row2)
        return grid
    }

    private fun makeKpiCard(label: String, value: String, valueColor: Int, weight: Float): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_SURFACE)
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight)

            addView(makeText(label, 10f, C_MUTED).apply {
                setPadding(0, 0, 0, 4.dp())
            })
            addView(makeText(value, 20f, valueColor, Typeface.MONOSPACE, Typeface.BOLD))
        }
    }

    // ── 하루 요약 카드 ──────────────────────
    private fun makeSummaryCard(summary: DailySummary): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_SURFACE)
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp() }

            listOf(
                "날짜" to summary.date,
                "총 콜" to "${summary.totalCalls}건",
                "추천 따라감" to "${"%.0f".format(summary.acceptRate)}% (${summary.acceptedCount}건)",
                "급송" to "${summary.urgentCount}건",
                "평균 손실" to summary.avgLossWon.toFormattedWon(),
                "실패율" to "${"%.0f".format(summary.badRate)}%"
            ).forEach { (key, value) ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 3.dp(), 0, 3.dp())
                    addView(makeText(key, 12f, C_MUTED).apply {
                        layoutParams = LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(makeText(value, 12f, C_TEXT, Typeface.MONOSPACE).apply {
                        gravity = Gravity.END
                        layoutParams = LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                })
            }
        }
    }

    // ── 세션 로우 ───────────────────────────
    private fun makeSessionRow(session: RecommendationSession): View {
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(Date(session.timestamp))

        val actionColor = when (session.userAction) {
            UserAction.ACCEPT -> C_ACCENT
            UserAction.SKIP   -> C_MUTED
            UserAction.CANCEL -> C_MUTED
        }
        val resultColor = when (session.result) {
            SessionResult.GOOD   -> C_ACCENT
            SessionResult.NORMAL -> C_TEXT
            SessionResult.BAD    -> C_URGENT
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setBackgroundColor(C_SURFACE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4.dp() }

            // 시간
            addView(makeText(timeStr, 11f, C_MUTED, Typeface.MONOSPACE).apply {
                layoutParams = LinearLayout.LayoutParams(48.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
            })

            // 추천 금액
            addView(makeText(session.recommendedCall.price.toFormattedWon(), 12f, C_TEXT, Typeface.MONOSPACE).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            // 선택 금액
            val selPrice = session.selectedCall?.price?.toFormattedWon() ?: "-"
            addView(makeText(selPrice, 12f, C_TEXT, Typeface.MONOSPACE).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            // loss_won
            val lossText = if (session.lossWon >= 0) "+${session.lossWon.toFormattedWon()}"
                           else session.lossWon.toFormattedWon()
            val lossColor = when {
                session.lossWon > 0  -> C_ACCENT
                session.lossWon < 0  -> C_URGENT
                else                 -> C_MUTED
            }
            addView(makeText(lossText, 11f, lossColor, Typeface.MONOSPACE).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.END
            })

            // 결과
            addView(makeText(session.result.value, 10f, resultColor).apply {
                layoutParams = LinearLayout.LayoutParams(48.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.END
            })

            // 급송 표시
            if (session.isUrgent) {
                addView(makeText("⚡", 12f, C_URGENT).apply {
                    layoutParams = LinearLayout.LayoutParams(24.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
                    gravity = Gravity.END
                })
            }
        }
    }

    // ── 데이터 품질 카드 ────────────────────
    private fun makeDataQualityCard(): View {
        val searchSessions = SearchSessionStore.loadAll(this)
        val quality = SearchSessionStore.getDataQuality(searchSessions)
        val qualityPct = (quality * 100).toInt()
        val qualityColor = when {
            qualityPct >= 80 -> C_ACCENT
            qualityPct >= 50 -> C_WARN
            else             -> C_URGENT
        }
        val qualityLabel = when {
            qualityPct >= 80 -> "GOOD"
            qualityPct >= 50 -> "FAIR"
            else             -> "LOW"
        }

        // 콜 카운터 집계
        val todaySessions = searchSessions.filter {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(Date(it.startedAt)) == SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        }
        val totalReceived = todaySessions.sumOf { it.callsReceived }
        val totalRejected = todaySessions.sumOf { it.callsRejected }
        val totalTimeout  = todaySessions.sumOf { it.callsTimeout }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_SURFACE)
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp() }

            // 품질 헤더
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 8.dp())
                addView(makeText("$qualityPct%", 24f, qualityColor, Typeface.MONOSPACE, Typeface.BOLD).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 8.dp() }
                })
                addView(makeText(qualityLabel, 12f, qualityColor))
            })

            // 품질 바
            addView(View(context).apply {
                setBackgroundColor(C_BORDER)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 4.dp()
                ).apply { bottomMargin = 8.dp() }
            })
            addView(View(context).apply {
                setBackgroundColor(qualityColor)
                layoutParams = LinearLayout.LayoutParams(
                    (resources.displayMetrics.widthPixels * quality * 0.85).toInt(), 4.dp()
                ).apply { bottomMargin = 12.dp(); topMargin = (-4).dp() }
            })

            listOf(
                "오늘 수신 콜" to "${totalReceived}건",
                "거절" to "${totalRejected}건",
                "타임아웃" to "${totalTimeout}건",
                "세션 수" to "${searchSessions.size}건"
            ).forEach { (key, value) ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 3.dp(), 0, 3.dp())
                    addView(makeText(key, 12f, C_MUTED).apply {
                        layoutParams = LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(makeText(value, 12f, C_TEXT, Typeface.MONOSPACE).apply {
                        gravity = Gravity.END
                        layoutParams = LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                })
            }
        }
    }

    // ── 수익 운영 KPI 카드 ──────────────────
    private fun makeEarningKpiCard(): View {
        val searchSessions = SearchSessionStore.loadAll(this)
        val avgMinutesToAccept = SearchSessionStore.getAvgMinutesToAccept(searchSessions)
        val avgEarnedPerHour = SearchSessionStore.getAvgEarnedPerHour(searchSessions)
        val avgEarnedPerKm = SearchSessionStore.getAvgEarnedPerKm(searchSessions)
        val todayEarning = EarningManager.getTodayEarning(this)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_SURFACE)
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp() }

            listOf(
                "평균 콜 획득 시간" to "${"%.1f".format(avgMinutesToAccept)}분",
                "평균 시간당 수익" to avgEarnedPerHour.toFormattedWon(),
                "평균 거리당 수익" to avgEarnedPerKm.toFormattedWon(),
                "오늘 총 수익" to todayEarning.toFormattedWon()
            ).forEach { (key, value) ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 3.dp(), 0, 3.dp())
                    addView(makeText(key, 12f, C_MUTED).apply {
                        layoutParams = LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(makeText(value, 12f, C_TEXT, Typeface.MONOSPACE).apply {
                        gravity = Gravity.END
                        layoutParams = LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                })
            }
        }
    }

    // ── 섹션 레이블 ─────────────────────────
    private fun makeSectionLabel(text: String): TextView =
        makeText(text, 11f, C_MUTED, Typeface.MONOSPACE).apply {
            letterSpacing = 0.1f
            setPadding(0, 16.dp(), 0, 8.dp())
        }

    // ── 텍스트뷰 헬퍼 ───────────────────────
    private fun makeText(
        text: String, size: Float, color: Int,
        typeface: Typeface? = null, style: Int = Typeface.NORMAL
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (typeface != null) setTypeface(typeface, style)
        else if (style != Typeface.NORMAL) setTypeface(null, style)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
}
