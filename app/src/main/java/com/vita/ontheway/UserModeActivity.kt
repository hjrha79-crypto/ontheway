package com.vita.ontheway

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * 사용자 모드 화면 (베타 기사용).
 * 오늘 수익 + 운행 토글 + 최근 콜 리스트만 표시.
 */
class UserModeActivity : AppCompatActivity() {

    private val C_BG       = Color.parseColor("#F5F5F7")
    private val C_CARD     = Color.WHITE
    private val C_TEXT     = Color.parseColor("#1A1A1A")
    private val C_SUB      = Color.parseColor("#888888")
    private val C_ACCENT   = Color.parseColor("#5B6ABF")
    private val C_GREEN    = Color.parseColor("#2ECC71")
    private val C_RED      = Color.parseColor("#E74C3C")
    private val C_IDLE_BG  = Color.parseColor("#5B6ABF")
    private val C_DRIVE_BG = Color.parseColor("#E74C3C")

    private lateinit var earningText: TextView
    private lateinit var driveBtn: TextView
    private lateinit var lastCallText: TextView
    private lateinit var callListLayout: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val fmt = NumberFormat.getNumberInstance()
    private val sdfHm = SimpleDateFormat("HH:mm", Locale.getDefault())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private var logoTapCount = 0
    private var logoLastTap = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try { FeatureFlags.load(this) } catch (_: Exception) {}

        // 온보딩 체크
        if (OnboardingActivity.isFirstRun(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = ScrollView(this).apply { setBackgroundColor(C_BG) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(48))
        }

        // ── 로고 (5탭 → 개발자 모드) ──
        val logo = TextView(this).apply {
            text = "OnTheWay"
            textSize = 22f
            setTextColor(C_TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        logo.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - logoLastTap > 2000) logoTapCount = 0
            logoLastTap = now
            logoTapCount++
            if (logoTapCount >= 5) {
                logoTapCount = 0
                FeatureFlags.devMode = true
                FeatureFlags.save(this)
                Toast.makeText(this, "개발자 모드 활성화", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
        container.addView(logo)

        // ── 오늘 수익 ──
        earningText = TextView(this).apply {
            textSize = 36f
            setTextColor(C_TEXT)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(24))
        }
        container.addView(earningText)

        // ── 운행 시작/중지 버튼 ──
        driveBtn = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(20))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(24) }
        }
        driveBtn.setOnClickListener { toggleDrivingMode() }
        container.addView(driveBtn)

        // ── 접근성 안내 버튼 (최소 설정) ──
        val settingsBtn = TextView(this).apply {
            text = "접근성 권한 설정"
            textSize = 14f
            setTextColor(C_ACCENT)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        }
        settingsBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "설정 열기 실패", Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(settingsBtn)

        // ── 마지막 콜 ──
        val lastCallLabel = TextView(this).apply {
            text = "마지막 콜"
            textSize = 12f
            setTextColor(C_SUB)
            setPadding(0, 0, 0, dp(4))
        }
        container.addView(lastCallLabel)

        lastCallText = TextView(this).apply {
            textSize = 15f
            setTextColor(C_TEXT)
            setPadding(0, 0, 0, dp(16))
        }
        container.addView(lastCallText)

        // ── 최근 콜 리스트 ──
        val recentLabel = TextView(this).apply {
            text = "최근 콜"
            textSize = 12f
            setTextColor(C_SUB)
            setPadding(0, 0, 0, dp(8))
        }
        container.addView(recentLabel)

        callListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(callListLayout)

        root.addView(container)
        setContentView(root)

        refreshUI()
        startAutoRefresh()
    }

    override fun onResume() {
        super.onResume()
        try { FeatureFlags.load(this) } catch (_: Exception) {}
        if (FeatureFlags.devMode) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        refreshUI()
    }

    private fun toggleDrivingMode() {
        val current = DrivingModeManager.getMode(this)
        val newMode = if (current == DrivingMode.DRIVING) DrivingMode.IDLE else DrivingMode.DRIVING
        DrivingModeManager.setMode(this, newMode)
        if (newMode == DrivingMode.DRIVING) {
            LocationTracker.startTracking(this)
        } else {
            LocationTracker.stopTracking()
        }
        refreshUI()
    }

    private fun refreshUI() {
        // 수익
        val earning = EarningManager.getTodayEarning(this)
        earningText.text = "오늘 ${fmt.format(earning)}원"

        // 운행 버튼
        val isDriving = DrivingModeManager.getMode(this) == DrivingMode.DRIVING
        driveBtn.text = if (isDriving) "운행 중지" else "운행 시작"
        driveBtn.setBackgroundColor(if (isDriving) C_DRIVE_BG else C_IDLE_BG)

        // 마지막 콜
        val lastCall = getLastCallInfo()
        lastCallText.text = lastCall ?: "아직 콜 없음"

        // 최근 콜 리스트
        refreshCallList()
    }

    private fun getLastCallInfo(): String? {
        return try {
            val db = CallLogDb.get(this).readableDatabase
            val cursor = db.rawQuery(
                "SELECT timestamp, platform, price, verdict FROM ${CallLogDb.TABLE} ORDER BY timestamp DESC LIMIT 1",
                null
            )
            cursor.use {
                if (it.moveToFirst()) {
                    val ts = it.getLong(0)
                    val platform = it.getString(1) ?: ""
                    val price = it.getInt(2)
                    val pName = when (platform) {
                        "coupang" -> "쿠팡"; "baemin" -> "배민"; "kakaot" -> "카카오T"; else -> platform
                    }
                    "${sdfHm.format(Date(ts))} $pName ${fmt.format(price)}원"
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun refreshCallList() {
        callListLayout.removeAllViews()
        try {
            val db = CallLogDb.get(this).readableDatabase
            val cursor = db.rawQuery(
                "SELECT timestamp, platform, price, verdict FROM ${CallLogDb.TABLE} ORDER BY timestamp DESC LIMIT 5",
                null
            )
            cursor.use {
                while (it.moveToNext()) {
                    val ts = it.getLong(0)
                    val platform = it.getString(1) ?: ""
                    val price = it.getInt(2)
                    val verdict = it.getString(3) ?: ""
                    callListLayout.addView(makeCallRow(ts, platform, price, verdict))
                }
            }
            if (callListLayout.childCount == 0) {
                callListLayout.addView(TextView(this).apply {
                    text = "콜 기록이 없습니다"
                    textSize = 14f
                    setTextColor(C_SUB)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(16), 0, dp(16))
                })
            }
        } catch (_: Exception) {}
    }

    private fun makeCallRow(ts: Long, platform: String, price: Int, verdict: String): View {
        val isReject = verdict.contains("REJECT") || verdict.contains("넘기세요")
        val bgColor = if (isReject) Color.parseColor("#FFF5F5") else Color.parseColor("#F5FFF5")
        val pName = when (platform) {
            "coupang" -> "쿠팡"; "baemin" -> "배민"; "kakaot" -> "카카오T"; else -> platform
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bgColor)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }

            // 시각
            addView(TextView(context).apply {
                text = sdfHm.format(Date(ts))
                textSize = 13f
                setTextColor(C_SUB)
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT)
            })

            // 플랫폼
            addView(TextView(context).apply {
                text = pName
                textSize = 14f
                setTextColor(C_TEXT)
                layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT)
            })

            // 금액
            addView(TextView(context).apply {
                text = "${fmt.format(price)}원"
                textSize = 15f
                setTextColor(C_TEXT)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })

            // 상태 점
            addView(View(context).apply {
                setBackgroundColor(if (isReject) C_RED else C_GREEN)
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    marginStart = dp(12)
                }
            })
        }
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
