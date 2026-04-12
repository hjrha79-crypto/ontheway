package com.vita.ontheway

import android.os.Bundle
import android.view.*
import android.widget.*
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

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

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(0), 0, dp(40))
        }

        // ─── 헤더 ───
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(44), dp(20), dp(14))
        }
        header.addView(TextView(this).apply {
            text = "←"; textSize = 20f; setTextColor(Color.BLACK)
            setPadding(dp(8), 0, dp(16), 0)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "설정"; textSize = 18f; setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(header, lp(MP, WC))
        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")) }, lp(MP, dp(1)))

        // ─── 글자 크기 섹션 ───
        root.addView(sectionTitle("글자 크기"))
        val fontSizeCard = card()
        val currentScale = FontSizeManager.getScale(this)
        val options = listOf(
            "작게" to 0.85f,
            "보통" to 1.0f,
            "크게" to 1.2f,
            "매우 크게" to 1.4f
        )
        val previewText = TextView(this).apply {
            text = "미리보기: 39,400원 송파→강남"
            textSize = 16f * currentScale
            setTextColor(Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        options.forEach { (label, scale) ->
            btnRow.addView(TextView(this).apply {
                text = label; textSize = 14f
                val isSelected = Math.abs(currentScale - scale) < 0.01f
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#5B6ABF"))
                setBackgroundColor(if (isSelected) Color.parseColor("#5B6ABF") else Color.parseColor("#F0F0F0"))
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(12), dp(4), dp(12))
                setOnClickListener {
                    FontSizeManager.setScale(this@SettingsActivity, scale)
                    recreate()
                }
            }, lp(0, WC, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        fontSizeCard.addView(btnRow)
        fontSizeCard.addView(previewText)
        root.addView(fontSizeCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // ─── 목표 수익 섹션 ───
        root.addView(sectionTitle("목표 수익"))
        val goalCard = card()
        val goalAmt = EarningManager.getGoal(this).toString().toInt()
        val goalInput = EditText(this).apply {
            setText(goalAmt.toString())
            textSize = 18f; setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        goalCard.addView(TextView(this).apply {
            text = "일일 목표 금액 (원)"
            textSize = 13f; setTextColor(Color.parseColor("#666666"))
            setPadding(dp(16), dp(16), dp(16), dp(6))
        })
        goalCard.addView(goalInput, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })
        goalCard.addView(TextView(this).apply {
            text = "저장"
            textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#5B6ABF"))
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                val amt = goalInput.text.toString().toIntOrNull() ?: 100000
                EarningManager.setGoal(this@SettingsActivity, amt)
                Toast.makeText(this@SettingsActivity, "목표 ${fmt(amt)}원 저장됨", Toast.LENGTH_SHORT).show()
            }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(4), dp(16), dp(16)) })
        root.addView(goalCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // ─── 차량 종류 섹션 ───
        root.addView(sectionTitle("차량 종류"))
        val vehicleCard = card()
        val vehicleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }
        val cur = EarningManager.getVehicleType(this)
        listOf("오토바이", "승용차", "승합차").forEach { v ->
            vehicleRow.addView(TextView(this).apply {
                text = v; textSize = 15f
                val isSelected = v == cur
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#5B6ABF"))
                setBackgroundColor(if (isSelected) Color.parseColor("#5B6ABF") else Color.parseColor("#F0F0F0"))
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(14), dp(4), dp(14))
                setOnClickListener {
                    EarningManager.setVehicleType(this@SettingsActivity, v)
                    recreate()
                }
            }, lp(0, WC, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        vehicleCard.addView(vehicleRow)
        root.addView(vehicleCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // ─── Shadow Mode KPI 섹션 ───
        root.addView(sectionTitle("Shadow Mode"))
        val shadowCard = card()
        val stats = ShadowLog.getTodayStats(this)?.toString() ?: "데이터 없음"
        shadowCard.addView(TextView(this).apply {
            text = stats
            textSize = 14f; setTextColor(Color.parseColor("#333333"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
        root.addView(shadowCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        scrollView.addView(root)
        setContentView(scrollView)
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f; setTextColor(Color.parseColor("#999999"))
            setPadding(dp(20), dp(20), dp(20), dp(8))
        }
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
    }
}
