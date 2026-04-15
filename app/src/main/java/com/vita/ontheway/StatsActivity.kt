package com.vita.ontheway

import android.os.Bundle
import android.widget.*
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import android.graphics.*
import android.graphics.drawable.*
import org.json.JSONObject

class StatsActivity : AppCompatActivity() {

    private val C_BG      = Color.parseColor("#F5F5F5")
    private val C_CARD    = Color.WHITE
    private val C_ACCENT  = Color.parseColor("#5B6ABF")
    private val C_TEXT    = Color.parseColor("#111111")
    private val C_SUB     = Color.parseColor("#999999")
    private val C_GREEN   = Color.parseColor("#2EAA5E")
    private val C_RED     = Color.parseColor("#E04040")
    private val C_ORANGE  = Color.parseColor("#FF9800")

    private fun pill(color: Int, r: Float = 16f) =
        GradientDrawable().apply { setColor(color); cornerRadius = r }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    private fun fmt(n: Int) = String.format("%,d", n)

    private lateinit var contentLayout: LinearLayout
    private var currentTab = "today"

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
            text = "통계"; textSize = 18f
            setTypeface(null, Typeface.BOLD); setTextColor(C_TEXT)
        })
        layout.addView(headerRow, lp(MP, WC).apply { setMargins(dp(-16), 0, dp(-16), dp(8)) })

        // 탭 전환
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
        }
        listOf("오늘" to "today", "이번주" to "week", "이번달" to "month").forEach { (label, key) ->
            tabRow.addView(TextView(this).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { switchTab(key) }
            }, lp(0, WC, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        layout.addView(tabRow)

        contentLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(contentLayout, lp(MP, WC))

        setContentView(root)
        switchTab("today")
    }

    private fun switchTab(tab: String) {
        currentTab = tab
        contentLayout.removeAllViews()

        // 탭 하이라이트 업데이트
        val tabRow = (contentLayout.parent as LinearLayout).getChildAt(2) as? LinearLayout
        tabRow?.let { row ->
            for (i in 0 until row.childCount) {
                val tv = row.getChildAt(i) as? TextView ?: continue
                val key = listOf("today", "week", "month")[i]
                val selected = key == tab
                tv.setTextColor(if (selected) Color.WHITE else C_ACCENT)
                tv.setBackgroundColor(if (selected) C_ACCENT else Color.parseColor("#F0F0F0"))
                tv.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            }
        }

        val entries = getFilteredEntries(tab)
        buildStats(entries)
    }

    private fun getFilteredEntries(tab: String): List<JSONObject> {
        val all = FilterLog.getAll(this)
        val cal = java.util.Calendar.getInstance()
        val cutoff = when (tab) {
            "today" -> {
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.timeInMillis
            }
            "week" -> {
                cal.set(java.util.Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.timeInMillis
            }
            "month" -> {
                cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.timeInMillis
            }
            else -> 0L
        }
        val result = mutableListOf<JSONObject>()
        for (i in 0 until all.length()) {
            val e = all.getJSONObject(i)
            if (e.optLong("ts", 0) >= cutoff) result.add(e)
        }
        return result
    }

    private fun buildStats(entries: List<JSONObject>) {
        val total = entries.size
        val accepted = entries.count { it.optString("verdict") == "ACCEPT" }
        val rejected = entries.count { it.optString("verdict") == "REJECT" }
        val acceptedCalls = entries.filter { it.optString("verdict") == "ACCEPTED" }
        val totalRevenue = acceptedCalls.sumOf { it.optInt("price", 0) }
        val avgPrice = if (total > 0) entries.sumOf { it.optInt("price", 0) } / total else 0
        val avgUnit = entries.filter { it.optInt("unitPrice", 0) > 0 }.let { filtered ->
            if (filtered.isNotEmpty()) filtered.sumOf { it.optInt("unitPrice", 0) } / filtered.size else 0
        }

        // 시급
        val earnings = EarningsTracker.getToday(this)
        val hourly = earnings.hourlyRate

        // 총합 카드
        contentLayout.addView(makeCard("총합", listOf(
            "감지 건수" to "${total}건",
            "ACCEPT" to "${accepted}건 (${if (total > 0) accepted * 100 / total else 0}%)",
            "REJECT" to "${rejected}건 (${if (total > 0) rejected * 100 / total else 0}%)",
            "수락" to "${acceptedCalls.size}건",
            "평균 금액" to "${fmt(avgPrice)}원",
            "평균 단가" to if (avgUnit > 0) "${fmt(avgUnit)}원/km" else "-",
            "총 매출" to "${fmt(totalRevenue)}원",
            "시급 환산" to if (hourly > 0) "${fmt(hourly)}원/h" else "-"
        ), highlight = "${fmt(totalRevenue)}원"))

        // 플랫폼별
        val platforms = entries.groupBy { it.optString("platform", "?") }
        val platformItems = platforms.map { (platform, list) ->
            val pName = when (platform) { "coupang" -> "쿠팡"; "baemin" -> "배민"; "kakaot" -> "카카오T"; else -> platform }
            val pAvg = if (list.isNotEmpty()) list.sumOf { it.optInt("price", 0) } / list.size else 0
            "$pName ${list.size}건 (평균 ${fmt(pAvg)}원)"
        }
        if (platformItems.isNotEmpty()) {
            contentLayout.addView(makeSimpleCard("플랫폼별", platformItems))
        }

        // 시간대별
        val timeSlots = mapOf(
            "오전 (6~12시)" to (6..11),
            "점심 (12~14시)" to (12..13),
            "오후 (14~18시)" to (14..17),
            "저녁 (18~22시)" to (18..21),
            "야간 (22~6시)" to (22..29) // 22~5 (29=5+24)
        )
        val cal = java.util.Calendar.getInstance()
        val timeItems = timeSlots.map { (label, range) ->
            val inSlot = entries.filter { e ->
                cal.timeInMillis = e.optLong("ts", 0)
                val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
                val adjusted = if (range.last > 23) { if (h < 6) h + 24 else h } else h
                adjusted in range
            }
            val slotAvgUnit = inSlot.filter { it.optInt("unitPrice", 0) > 0 }.let {
                if (it.isNotEmpty()) it.sumOf { e -> e.optInt("unitPrice", 0) } / it.size else 0
            }
            "$label: ${inSlot.size}건" + if (slotAvgUnit > 0) " (단가 ${fmt(slotAvgUnit)}원/km)" else ""
        }
        contentLayout.addView(makeSimpleCard("시간대별 분포", timeItems))

        // 아쉬운 콜 TOP 3
        val regrettable = entries
            .filter { it.optString("verdict") == "REJECT" }
            .sortedByDescending { it.optInt("unitPrice", 0).takeIf { u -> u > 0 } ?: it.optInt("price", 0) }
            .take(3)
        if (regrettable.isNotEmpty()) {
            val regretItems = regrettable.mapIndexed { i, e ->
                val p = e.optInt("price", 0)
                val u = e.optInt("unitPrice", 0)
                val platform = when (e.optString("platform")) { "coupang" -> "쿠팡"; "baemin" -> "배민"; else -> "?" }
                "#${i + 1} $platform ${fmt(p)}원" + if (u > 0) " (단가 ${fmt(u)}원/km)" else ""
            }
            contentLayout.addView(makeSimpleCard("넘긴 콜 중 아쉬운 TOP 3", regretItems))
        }
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

    private fun makeSimpleCard(title: String, items: List<String>): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(C_CARD, 16f)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        card.addView(TextView(this).apply {
            text = title; textSize = 12f; setTextColor(C_SUB)
            letterSpacing = 0.05f; setPadding(0, 0, 0, dp(10))
        })
        items.forEach { item ->
            card.addView(TextView(this).apply {
                text = item; textSize = 13f; setTextColor(C_TEXT)
                setPadding(0, dp(3), 0, dp(3))
            })
        }
        card.layoutParams = LinearLayout.LayoutParams(MP, WC).apply { setMargins(0, 0, 0, dp(12)) }
        return card
    }
}
