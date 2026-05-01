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

class UserModeActivity : AppCompatActivity() {

    private val C_BG       = Color.parseColor("#0F0F1A")
    private val C_SUB      = Color.parseColor("#6C757D")
    private val C_GREEN    = Color.parseColor("#00F5A0")
    private val C_WHITE    = Color.WHITE
    private val C_BTN_GO_S = Color.parseColor("#00F5A0")
    private val C_BTN_GO_E = Color.parseColor("#00C9FF")
    private val C_BTN_ST_S = Color.parseColor("#EF233C")
    private val C_BTN_ST_E = Color.parseColor("#C1121F")

    private lateinit var hourlyText: TextView
    private lateinit var callCountText: TextView
    private lateinit var driveBtn: TextView
    private lateinit var accessibilityBanner: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val fmt = NumberFormat.getNumberInstance()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private var logoTapCount = 0
    private var logoLastTap = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { FeatureFlags.load(this) } catch (_: Exception) {}
        try { DrivingModeManager.checkAndResetDate(this) } catch (_: Exception) {}

        if (OnboardingActivity.isFirstRun(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish(); return
        }

        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG

        val root = ScrollView(this).apply { setBackgroundColor(C_BG) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(40))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // ═══ 1. 상단 헤더 (로고 + 설정 아이콘) ═══
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        headerRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), 1)
        })
        val logo = TextView(this).apply {
            text = "OnTheWay"; textSize = 14f; setTextColor(C_SUB)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
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
        headerRow.addView(logo)
        val settingsBtn = TextView(this).apply {
            text = "\u2699"; textSize = 20f; setTextColor(C_SUB)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        }
        settingsBtn.setOnClickListener {
            startActivity(Intent(this, UserSettingsActivity::class.java))
        }
        headerRow.addView(settingsBtn)
        container.addView(headerRow)

        // ═══ 2. 시간당 수익 ═══
        container.addView(TextView(this).apply {
            text = "시간당 수익"; textSize = 14f; setTextColor(C_SUB)
            gravity = Gravity.CENTER; setPadding(0, dp(32), 0, 0)
        })

        hourlyText = TextView(this).apply {
            text = "0원/h"; textSize = 56f; setTextColor(C_SUB); gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        container.addView(hourlyText)

        callCountText = TextView(this).apply {
            textSize = 12f; setTextColor(C_SUB); gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(48))
        }
        container.addView(callCountText)

        // ═══ 3. 접근성 권한 경고 배너 (운행 버튼 위) ═══
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

        // ═══ 4. 운행 버튼 ═══
        driveBtn = TextView(this).apply {
            textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(20))
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(MP, dp(64)).apply { bottomMargin = dp(16) }
        }
        driveBtn.setOnClickListener { toggleDrivingMode() }
        container.addView(driveBtn)

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

    private fun refreshUI() {
        val callCount = EarningManager.getTodayCallCount(this)
        val goal = EarningManager.getGoal(this)
        val isDriving = DrivingModeManager.getMode(this) == DrivingMode.DRIVING
        val hourlyRate = EarningsCalculator.calculateHourlyRate(this)

        hourlyText.text = hourlyRate?.let { "${fmt.format(it)}원/h" } ?: "0원/h"
        hourlyText.setTextColor(when {
            hourlyRate == null || hourlyRate <= 0L -> C_SUB
            goal > 0 && hourlyRate >= goal -> C_GREEN
            isDriving -> C_WHITE
            else -> C_SUB
        })
        callCountText.text = "오늘 ${callCount}콜"

        driveBtn.text = if (isDriving) "운행 중지" else "운행 시작"
        driveBtn.background = if (isDriving)
            gradientRect(C_BTN_ST_S, C_BTN_ST_E, 16)
        else
            gradientRect(C_BTN_GO_S, C_BTN_GO_E, 16)

        accessibilityBanner.visibility = if (isAccessibilityEnabled()) View.GONE else View.VISIBLE
    }

    private fun roundRect(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun gradientRect(startColor: Int, endColor: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(startColor, endColor)
        ).apply { cornerRadius = dp(radiusDp).toFloat() }
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == packageName }
        } catch (_: Exception) { false }
    }

    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            refreshUI()
            handler.postDelayed(this, 5000)
        }
    }

    private fun startAutoRefresh() {
        handler.postDelayed(autoRefreshRunnable, 5000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoRefreshRunnable)
        super.onDestroy()
    }
}
