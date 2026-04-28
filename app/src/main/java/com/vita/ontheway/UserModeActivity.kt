package com.vita.ontheway

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class UserModeActivity : AppCompatActivity() {

    // ── 컬러 팔레트 ──
    private val C_BG       = Color.parseColor("#FFFFFF")
    private val C_CARD     = Color.parseColor("#F8F9FA")
    private val C_TEXT     = Color.parseColor("#1A1A2E")
    private val C_SUB      = Color.parseColor("#6C757D")
    private val C_BTN_GO   = Color.parseColor("#4361EE")
    private val C_BTN_STOP = Color.parseColor("#EF233C")
    private val C_GREEN    = Color.parseColor("#06D6A0")
    private val C_RED      = Color.parseColor("#EF233C")

    private lateinit var earningText: TextView
    private lateinit var earningSubText: TextView
    private lateinit var driveBtn: TextView
    private lateinit var statsCard: LinearLayout
    private lateinit var statHourly: TextView
    private lateinit var statDistance: TextView
    private lateinit var statReturn: TextView
    private lateinit var statWait: TextView
    private lateinit var lastCallCard: LinearLayout
    private lateinit var callListLayout: LinearLayout
    private lateinit var accessibilityBanner: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val fmt = NumberFormat.getNumberInstance()
    private val sdfHm = SimpleDateFormat("HH:mm", Locale.getDefault())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private var logoTapCount = 0
    private var logoLastTap = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { FeatureFlags.load(this) } catch (_: Exception) {}

        if (OnboardingActivity.isFirstRun(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish(); return
        }

        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = ScrollView(this).apply { setBackgroundColor(C_BG) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(40))
        }

        // ═══ 1. 상단 헤더 ═══
        val logo = TextView(this).apply {
            text = "OnTheWay"; textSize = 14f; setTextColor(C_SUB)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(8))
        }
        logo.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - logoLastTap > 2000) logoTapCount = 0
            logoLastTap = now
            logoTapCount++
            if (logoTapCount >= 5) {
                logoTapCount = 0
                FeatureFlags.devMode = true; FeatureFlags.save(this)
                Toast.makeText(this, "개발자 모드 활성화", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java)); finish()
            }
        }
        container.addView(logo)

        container.addView(TextView(this).apply {
            text = "오늘 수익"; textSize = 14f; setTextColor(C_SUB)
            gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
        })

        earningText = TextView(this).apply {
            textSize = 48f; setTextColor(C_TEXT); gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        container.addView(earningText)

        earningSubText = TextView(this).apply {
            textSize = 12f; setTextColor(C_SUB); gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(24))
        }
        container.addView(earningSubText)

        // ═══ 접근성 권한 경고 배너 (운행 버튼 위) ═══
        accessibilityBanner = TextView(this).apply {
            text = "\u26A0 접근성 권한 필요 - 탭하여 설정"
            textSize = 13f; setTextColor(Color.parseColor("#856404"))
            background = roundRect(Color.parseColor("#FFF3CD"), 8)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(12) }
            visibility = View.GONE
        }
        accessibilityBanner.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
        }
        container.addView(accessibilityBanner)

        // ═══ 2. 운행 버튼 ═══
        driveBtn = TextView(this).apply {
            textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(20))
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(MP, dp(64)).apply { bottomMargin = dp(16) }
        }
        driveBtn.setOnClickListener { toggleDrivingMode() }
        container.addView(driveBtn)

        // ═══ 3. 통계 카드 (운행 중일 때만 표시) ═══
        statsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(C_CARD, 12)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(16) }
            visibility = View.GONE
        }

        val statsRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(8) }
        }
        statHourly = statCell("시간당", statsRow1)
        statDistance = statCell("총 거리", statsRow1)
        statsCard.addView(statsRow1)

        val statsRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        statReturn = statCell("복귀", statsRow2)
        statWait = statCell("대기", statsRow2)
        statsCard.addView(statsRow2)
        container.addView(statsCard)

        // ═══ 4. 마지막 콜 카드 ═══
        lastCallCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(C_CARD, 12)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(20) }
        }
        container.addView(lastCallCard)

        // ═══ 5. 최근 콜 리스트 ═══
        container.addView(TextView(this).apply {
            text = "최근 콜"; textSize = 14f; setTextColor(C_TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(8))
        })

        callListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(callListLayout)

        root.addView(container)
        setContentView(root)
        refreshUI(); startAutoRefresh()
    }

    override fun onResume() {
        super.onResume()
        try { FeatureFlags.load(this) } catch (_: Exception) {}
        if (FeatureFlags.devMode) {
            startActivity(Intent(this, MainActivity::class.java)); finish(); return
        }
        refreshUI()
    }

    // ── 운행 토글 ──
    private fun toggleDrivingMode() {
        val current = DrivingModeManager.getMode(this)
        val newMode = if (current == DrivingMode.DRIVING) DrivingMode.IDLE else DrivingMode.DRIVING
        DrivingModeManager.setMode(this, newMode)
        if (newMode == DrivingMode.DRIVING) {
            LocationTracker.startTracking(this)
            ReturnTimeEstimator.saveStartLocation(this, OnTheWayService.currentLat, OnTheWayService.currentLng)
            StatusAlertEngine.reset()
        } else {
            LocationTracker.stopTracking()
        }
        refreshUI()
    }

    // ── UI 갱신 ──
    private fun refreshUI() {
        val earning = EarningManager.getTodayEarning(this)
        val callCount = EarningManager.getTodayCallCount(this)
        earningText.text = "${fmt.format(earning)}원"
        earningSubText.text = "오늘 ${callCount}콜"

        val isDriving = DrivingModeManager.getMode(this) == DrivingMode.DRIVING
        driveBtn.text = if (isDriving) "운행 중지" else "운행 시작"
        driveBtn.background = roundRect(if (isDriving) C_BTN_STOP else C_BTN_GO, 16)

        // 통계 (운행 중일 때만)
        if (isDriving) {
            statsCard.visibility = View.VISIBLE
            statHourly.text = EarningsCalculator.calculateHourlyRate(this)?.let { "${fmt.format(it)}원" } ?: "—"
            statDistance.text = EarningsCalculator.calculateTotalDistance(this).let {
                if (it > 0) "${"%.1f".format(it)}km" else "—"
            }
            statReturn.text = ReturnTimeEstimator.estimateReturnMinutes()?.let { "${it}분" } ?: "—"
            statWait.text = EarningsCalculator.calculateWaitTime(this).let { if (it > 0) "${it}분" else "—" }
        } else {
            statsCard.visibility = View.GONE
        }

        // 마지막 콜
        refreshLastCallCard()

        // 최근 콜
        refreshCallList()

        // 접근성 권한 확인
        accessibilityBanner.visibility = if (isAccessibilityEnabled()) View.GONE else View.VISIBLE
    }

    private fun refreshLastCallCard() {
        lastCallCard.removeAllViews()
        lastCallCard.addView(TextView(this).apply {
            text = "마지막 콜"; textSize = 10f; setTextColor(C_SUB)
            setPadding(0, 0, 0, dp(4))
        })
        try {
            val db = CallLogDb.get(this).readableDatabase
            val cursor = db.rawQuery(
                "SELECT timestamp, platform, price, verdict, reason FROM ${CallLogDb.TABLE} ORDER BY timestamp DESC LIMIT 1", null
            )
            cursor.use {
                if (it.moveToFirst()) {
                    val ts = it.getLong(0)
                    val platform = it.getString(1) ?: ""
                    val price = it.getInt(2)
                    val verdict = it.getString(3) ?: ""
                    val reason = it.getString(4) ?: ""
                    val pName = platformName(platform)
                    val vLabel = verdictLabel(verdict, reason)

                    val row = LinearLayout(this@UserModeActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    row.addView(TextView(this@UserModeActivity).apply {
                        text = "${sdfHm.format(Date(ts))}  $pName  ${fmt.format(price)}원"
                        textSize = 15f; setTextColor(C_TEXT)
                        layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                    })
                    // 판정 pill 뱃지
                    row.addView(makeVerdictPill(vLabel))
                    lastCallCard.addView(row)
                } else {
                    lastCallCard.addView(TextView(this).apply {
                        text = "아직 콜 없음"; textSize = 15f; setTextColor(C_SUB)
                    })
                }
            }
        } catch (_: Exception) {
            lastCallCard.addView(TextView(this).apply {
                text = "아직 콜 없음"; textSize = 15f; setTextColor(C_SUB)
            })
        }
    }

    private fun makeVerdictPill(label: String): TextView {
        val isReject = label == "넘기세요"
        val bgColor = if (isReject) Color.parseColor("#FFEBEE") else Color.parseColor("#E8F5E9")
        val textColor = if (isReject) C_RED else C_GREEN
        return TextView(this).apply {
            text = label; textSize = 12f; setTextColor(textColor)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = roundRect(bgColor, 12)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
    }

    private fun refreshCallList() {
        callListLayout.removeAllViews()
        try {
            val db = CallLogDb.get(this).readableDatabase
            val cursor = db.rawQuery(
                "SELECT id, timestamp, platform, price, verdict, reason FROM ${CallLogDb.TABLE} ORDER BY timestamp DESC LIMIT 5", null
            )
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val ts = it.getLong(1)
                    val platform = it.getString(2) ?: ""
                    val price = it.getInt(3)
                    val verdict = it.getString(4) ?: ""
                    val reason = it.getString(5) ?: ""
                    callListLayout.addView(makeCallRow(id, ts, platform, price, verdict, reason))
                }
            }
            if (callListLayout.childCount == 0) {
                callListLayout.addView(TextView(this).apply {
                    text = "콜 기록이 없습니다"; textSize = 14f; setTextColor(C_SUB)
                    gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(16))
                })
            }
        } catch (_: Exception) {}
    }

    private fun makeCallRow(id: Long, ts: Long, platform: String, price: Int, verdict: String, reason: String): View {
        val isReject = verdict.contains("REJECT")
        val barColor = if (isReject) C_RED else C_GREEN
        val pName = platformName(platform)

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(MP, dp(56)).apply { bottomMargin = dp(8) }
            background = roundRect(C_CARD, 8)
            elevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true

            // 좌측 색상 바
            addView(View(context).apply {
                val barBg = GradientDrawable().apply {
                    setColor(barColor)
                    cornerRadii = floatArrayOf(dp(8).toFloat(), dp(8).toFloat(), 0f, 0f, 0f, 0f, dp(8).toFloat(), dp(8).toFloat())
                }
                background = barBg
                layoutParams = LinearLayout.LayoutParams(dp(8), MP)
            })

            // 내용
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, MP, 1f)
            }

            content.addView(TextView(context).apply {
                text = sdfHm.format(Date(ts)); textSize = 12f; setTextColor(C_SUB)
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(dp(44), WC)
            })

            content.addView(TextView(context).apply {
                text = pName; textSize = 14f; setTextColor(C_TEXT)
                layoutParams = LinearLayout.LayoutParams(dp(52), WC)
            })

            content.addView(TextView(context).apply {
                text = "${fmt.format(price)}원"; textSize = 18f; setTextColor(C_TEXT)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            })

            // 상태 점
            content.addView(View(context).apply {
                val dotBg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(barColor) }
                background = dotBg
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginStart = dp(12) }
            })

            addView(content)

            // 클릭 → 콜 상세 다이얼로그
            setOnClickListener { CallDetailDialog.showById(this@UserModeActivity, id) }
        }
    }

    // ── 헬퍼 ──
    private fun platformName(code: String) = when (code) {
        "coupang" -> "쿠팡"; "baemin" -> "배민"; "kakaot" -> "카카오T"; else -> code
    }

    private fun verdictLabel(verdict: String, reason: String) = when {
        verdict == "REJECT" -> "넘기세요"
        reason.contains("잡으세요") -> "잡으세요"
        else -> "괜찮습니다"
    }

    private fun roundRect(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun statCell(label: String, parent: LinearLayout): TextView {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        }
        cell.addView(TextView(this).apply {
            text = label; textSize = 10f; setTextColor(C_SUB); gravity = Gravity.CENTER
        })
        val value = TextView(this).apply {
            text = "—"; textSize = 16f; setTextColor(C_TEXT); gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        cell.addView(value)
        parent.addView(cell)
        return value
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == packageName }
        } catch (_: Exception) { false }
    }

    private fun startAutoRefresh() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                refreshUI()
                handler.postDelayed(this, 5000)
            }
        }, 5000)
    }
}
