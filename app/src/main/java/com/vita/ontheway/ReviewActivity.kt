package com.vita.ontheway

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReviewActivity : AppCompatActivity() {

    private val C_BG = Color.parseColor("#0F0F1A")
    private val C_CARD = Color.parseColor("#1A1A2E")
    private val C_WHITE = Color.WHITE
    private val C_SUB = Color.parseColor("#A0A0C0")
    private val C_GREEN = Color.parseColor("#00F5A0")
    private val C_RED = Color.parseColor("#FF4D6D")
    private val C_GREEN_BG = Color.parseColor("#0D2A1A")
    private val C_RED_BG = Color.parseColor("#2A0D1A")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var cardContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var completeBtn: TextView
    private lateinit var loadMoreBtn: TextView
    private val cardStates = mutableMapOf<Int, String>() // index -> "ACCEPTED"/"REJECTED"
    private var reviewEntries = mutableListOf<ReviewEntry>()
    private var allCandidates = listOf<ReviewEntry>()
    private var currentPage = 1
    private val PAGE_SIZE = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG

        val db = CallLogDb.get(this)
        val allCalls = db.getTodayCallLogs() // 이미 최신순
        val alreadyReviewed = db.getReviewedCallTimestamps()
        allCandidates = allCalls.filter { it.callTs !in alreadyReviewed }
        reviewEntries = allCandidates.take(PAGE_SIZE).toMutableList()

        // review_log에 삽입
        val alreadyTs = db.getReviewedCallTimestamps()
        for (entry in reviewEntries) {
            if (entry.callTs !in alreadyTs) {
                db.insertReview(entry.callTs, entry.platform, entry.price, entry.verdict, entry.verdictMsg)
            }
        }

        val root = ScrollView(this).apply { setBackgroundColor(C_BG) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(32))
        }

        // 상단 헤더
        val dateStr = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date())
        container.addView(TextView(this).apply {
            text = "오늘 복기 (${allCandidates.size}건)"
            textSize = 22f; setTextColor(C_WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = dateStr
            textSize = 13f; setTextColor(C_SUB)
            setPadding(0, dp(4), 0, dp(20))
        })

        if (allCandidates.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "오늘 복기할 콜이 없습니다"
                textSize = 16f; setTextColor(C_SUB); gravity = Gravity.CENTER
                setPadding(0, dp(60), 0, 0)
            })
        }

        // 카드 리스트
        cardContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((idx, entry) in reviewEntries.withIndex()) {
            cardContainer.addView(buildCard(idx, entry))
        }
        container.addView(cardContainer)

        // 더보기 버튼
        loadMoreBtn = TextView(this).apply {
            text = "더보기"
            textSize = 14f; setTextColor(C_SUB); gravity = Gravity.CENTER
            background = roundRect(Color.parseColor("#1A1A2E"), 8)
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(8) }
            visibility = if (allCandidates.size > PAGE_SIZE) View.VISIBLE else View.GONE
        }
        loadMoreBtn.setOnClickListener { loadMore() }
        container.addView(loadMoreBtn)

        // 하단 상태 + 완료 버튼
        statusText = TextView(this).apply {
            textSize = 14f; setTextColor(C_SUB); gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(12))
        }
        container.addView(statusText)

        completeBtn = TextView(this).apply {
            text = "완료"
            textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(16))
            background = roundRect(Color.parseColor("#333355"), 12)
            isEnabled = false; alpha = 0.4f
            layoutParams = LinearLayout.LayoutParams(MP, WC)
        }
        completeBtn.setOnClickListener { finishReview() }
        container.addView(completeBtn)

        root.addView(container)
        setContentView(root)
        updateStatus()
    }

    private fun loadMore() {
        currentPage++
        val start = (currentPage - 1) * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, allCandidates.size)
        if (start >= allCandidates.size) return

        val newEntries = allCandidates.subList(start, end)
        val db = CallLogDb.get(this)
        val alreadyTs = db.getReviewedCallTimestamps()
        for (entry in newEntries) {
            if (entry.callTs !in alreadyTs) {
                db.insertReview(entry.callTs, entry.platform, entry.price, entry.verdict, entry.verdictMsg)
            }
        }

        val baseIdx = reviewEntries.size
        reviewEntries.addAll(newEntries)
        for ((i, entry) in newEntries.withIndex()) {
            cardContainer.addView(buildCard(baseIdx + i, entry))
        }

        if (end >= allCandidates.size) loadMoreBtn.visibility = View.GONE
        updateStatus()
    }

    private fun buildCard(idx: Int, entry: ReviewEntry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(C_CARD, 12)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(12) }
            tag = idx
        }

        // 시각 + 플랫폼 + 금액
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val timeStr = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(entry.callTs))
        topRow.addView(TextView(this).apply {
            text = timeStr; textSize = 13f; setTextColor(C_SUB)
        })
        topRow.addView(TextView(this).apply {
            text = platformBadge(entry.platform)
            textSize = 11f; setTextColor(Color.WHITE)
            background = roundRect(platformColor(entry.platform), 4)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(WC, WC).apply { leftMargin = dp(8) }
        })
        topRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        topRow.addView(TextView(this).apply {
            text = "${String.format("%,d", entry.price)}원"
            textSize = 18f; setTextColor(C_WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        card.addView(topRow)

        // 가게명/목적지/묶음/거리
        val infoLine = when {
            entry.storeName.isNotBlank() -> entry.storeName
            entry.destination.isNotBlank() -> entry.destination
            else -> "(정보 없음)"
        }
        val extras = mutableListOf<String>()
        if (entry.bundleCount > 1) extras.add("묶음 ${entry.bundleCount}건")
        if (entry.distance != null) extras.add("${String.format("%.1f", entry.distance)}km")
        val infoText = if (extras.isNotEmpty()) "$infoLine · ${extras.joinToString(" · ")}" else infoLine
        card.addView(TextView(this).apply {
            text = infoText
            textSize = 13f; setTextColor(C_WHITE)
            setPadding(0, dp(6), 0, dp(2))
        })

        // 판정 메시지
        if (entry.verdictMsg.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = entry.verdictMsg
                textSize = 12f; setTextColor(C_SUB)
                setPadding(0, dp(2), 0, dp(8))
            })
        }

        // 버튼 행
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        val acceptBtn = TextView(this).apply {
            text = "✅ 잡았어요"
            textSize = 14f; setTextColor(C_GREEN); gravity = Gravity.CENTER
            background = roundRect(Color.parseColor("#1A2E1A"), 8)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f).apply { rightMargin = dp(6) }
        }
        val rejectBtn = TextView(this).apply {
            text = "❌ 안잡았어요"
            textSize = 14f; setTextColor(C_RED); gravity = Gravity.CENTER
            background = roundRect(Color.parseColor("#2E1A1A"), 8)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f).apply { leftMargin = dp(6) }
        }

        // 거리 입력 필드 (배민 + 잡았어요 시에만 표시)
        val distanceInput = EditText(this).apply {
            hint = "배달 거리 (km, 선택)"
            textSize = 13f; setTextColor(C_WHITE); setHintTextColor(Color.parseColor("#555577"))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = roundRect(Color.parseColor("#15152A"), 8)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(8) }
            visibility = View.GONE
        }

        acceptBtn.setOnClickListener {
            cardStates[idx] = "ACCEPTED"
            card.background = roundRect(C_GREEN_BG, 12)
            acceptBtn.alpha = 1f; rejectBtn.alpha = 0.3f
            if (entry.platform == "baemin") distanceInput.visibility = View.VISIBLE
            saveAction(entry, "ACCEPTED", distanceInput)
            updateStatus()
        }
        rejectBtn.setOnClickListener {
            cardStates[idx] = "REJECTED"
            card.background = roundRect(C_RED_BG, 12)
            rejectBtn.alpha = 1f; acceptBtn.alpha = 0.3f
            distanceInput.visibility = View.GONE
            saveAction(entry, "REJECTED", null)
            updateStatus()
        }

        btnRow.addView(acceptBtn)
        btnRow.addView(rejectBtn)
        card.addView(btnRow)
        card.addView(distanceInput)

        return card
    }

    private fun saveAction(entry: ReviewEntry, action: String, distanceInput: EditText?) {
        val dist = distanceInput?.text?.toString()?.toDoubleOrNull()
        CallLogDb.get(this).updateUserAction(entry.callTs, entry.price, action, dist)
    }

    private fun updateStatus() {
        val remaining = reviewEntries.size - cardStates.size
        if (remaining > 0) {
            statusText.text = "${remaining}건 남음"
            statusText.setTextColor(C_SUB)
            completeBtn.isEnabled = false; completeBtn.alpha = 0.4f
            completeBtn.background = roundRect(Color.parseColor("#333355"), 12)
        } else {
            statusText.text = "모든 콜 복기 완료!"
            statusText.setTextColor(C_GREEN)
            completeBtn.isEnabled = true; completeBtn.alpha = 1f
            completeBtn.background = roundRect(Color.parseColor("#00C080"), 12)
        }
    }

    private fun finishReview() {
        // 거리 입력 재저장 (마지막 입력값 반영)
        for ((idx, entry) in reviewEntries.withIndex()) {
            if (cardStates[idx] == "ACCEPTED" && entry.platform == "baemin") {
                val card = cardContainer.getChildAt(idx) as? LinearLayout ?: continue
                val distInput = findDistanceInput(card)
                val dist = distInput?.text?.toString()?.toDoubleOrNull()
                if (dist != null) {
                    CallLogDb.get(this).updateUserAction(entry.callTs, entry.price, "ACCEPTED", dist)
                }
            }
        }
        Toast.makeText(this, "복기 완료!", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun findDistanceInput(card: LinearLayout): EditText? {
        for (i in 0 until card.childCount) {
            val child = card.getChildAt(i)
            if (child is EditText) return child
        }
        return null
    }

    private fun platformBadge(platform: String) = when (platform) {
        "baemin" -> "배민"
        "coupang" -> "쿠팡"
        else -> platform
    }

    private fun platformColor(platform: String) = when (platform) {
        "baemin" -> Color.parseColor("#2FC4B2")
        "coupang" -> Color.parseColor("#E44D2E")
        else -> Color.parseColor("#5566AA")
    }

    private fun roundRect(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }
}
