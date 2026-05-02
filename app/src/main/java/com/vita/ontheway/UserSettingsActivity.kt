package com.vita.ontheway

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.NumberFormat

/**
 * 사용자 모드 설정 화면 (단순, 3개 항목만).
 * 기존 개발자 모드 설정과 같은 SharedPreferences 공유.
 */
class UserSettingsActivity : AppCompatActivity() {

    private val C_BG   = Color.parseColor("#F0F2F5")
    private val C_CARD = Color.WHITE
    private val C_TEXT = Color.parseColor("#1A1A2E")
    private val C_SUB  = Color.parseColor("#6C757D")
    private val C_BLUE = Color.parseColor("#4361EE")

    private val fmt = NumberFormat.getNumberInstance()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        // ── 헤더: 뒤로가기 + 제목 ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        val backBtn = TextView(this).apply {
            text = "\u2190"; textSize = 22f; setTextColor(C_TEXT)
            setPadding(0, 0, dp(12), 0)
        }
        backBtn.setOnClickListener { finish() }
        header.addView(backBtn)
        header.addView(TextView(this).apply {
            text = "설정"; textSize = 20f; setTextColor(C_TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        container.addView(header)

        // ── 1. 목표 금액 ──
        val goalCard = makeCard()
        goalCard.addView(makeLabel("목표 금액"))
        val goalValue = TextView(this).apply {
            textSize = 16f; setTextColor(C_TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        }
        val currentGoal = EarningManager.getGoal(this)
        goalValue.text = "${fmt.format(currentGoal)}원"
        goalCard.addView(goalValue)

        val goalSeekBar = SeekBar(this).apply {
            max = 30  // 5만 ~ 20만 (5천 단위 = 30 steps)
            progress = ((currentGoal - 50000) / 5000).coerceIn(0, 30)
            layoutParams = LinearLayout.LayoutParams(MP, WC)
        }
        goalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val value = 50000 + progress * 5000
                goalValue.text = "${fmt.format(value)}원"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val value = 50000 + sb.progress * 5000
                EarningManager.setGoal(this@UserSettingsActivity, value)
            }
        })
        goalCard.addView(goalSeekBar)
        goalCard.addView(makeHint("5만원 ~ 20만원"))
        container.addView(goalCard)

        // ── 2. 최소 배달료 ──
        val minPriceCard = makeCard()
        minPriceCard.addView(makeLabel("최소 배달료"))
        val minPriceValue = TextView(this).apply {
            textSize = 16f; setTextColor(C_TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        }
        val currentMinPrice = CallFilter.getMinPrice(this)
        minPriceValue.text = "${fmt.format(currentMinPrice)}원"

        val minPriceSeekBar = SeekBar(this).apply {
            max = 10  // 1500 ~ 6500 (500 단위 = 10 steps)
            progress = ((currentMinPrice - 1500) / 500).coerceIn(0, 10)
            layoutParams = LinearLayout.LayoutParams(MP, WC)
        }
        minPriceSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val value = 1500 + progress * 500
                minPriceValue.text = "${fmt.format(value)}원"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val value = 1500 + sb.progress * 500
                CallFilter.setMinPrice(this@UserSettingsActivity, value)
            }
        })
        minPriceCard.addView(minPriceValue)
        minPriceCard.addView(minPriceSeekBar)
        minPriceCard.addView(makeHint("1,500원 ~ 6,500원 (이하 비추천)"))
        container.addView(minPriceCard)

        // ── 3. 최소 단가 ──
        val unitPriceCard = makeCard()
        unitPriceCard.addView(makeLabel("최소 단가"))
        val unitPriceValue = TextView(this).apply {
            textSize = 16f; setTextColor(C_TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        }
        val currentUnitPrice = CallFilter.getMinUnitPrice(this)
        unitPriceValue.text = "${fmt.format(currentUnitPrice)}원/km"

        val unitPriceSeekBar = SeekBar(this).apply {
            max = 10  // 1000 ~ 3500 (250 단위 = 10 steps)
            progress = ((currentUnitPrice - 1000) / 250).coerceIn(0, 10)
            layoutParams = LinearLayout.LayoutParams(MP, WC)
        }
        unitPriceSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val value = 1000 + progress * 250
                unitPriceValue.text = "${fmt.format(value)}원/km"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val value = 1000 + sb.progress * 250
                CallFilter.setMinUnitPrice(this@UserSettingsActivity, value)
            }
        })
        unitPriceCard.addView(unitPriceValue)
        unitPriceCard.addView(unitPriceSeekBar)
        unitPriceCard.addView(makeHint("1,000원/km ~ 3,500원/km (이하 비추천)"))
        container.addView(unitPriceCard)

        root.addView(container)
        setContentView(root)
    }

    private fun makeCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(C_CARD); cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = dp(12) }
        }
    }

    private fun makeLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 14f; setTextColor(C_TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    private fun makeHint(text: String): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 11f; setTextColor(C_SUB)
            setPadding(0, dp(4), 0, 0)
        }
    }
}
