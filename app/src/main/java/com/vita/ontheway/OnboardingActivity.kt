package com.vita.ontheway

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/** v3.5 온보딩 튜토리얼: 첫 실행 시 3장 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "onboarding"
        fun isFirstRun(ctx: Context) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("first_run", true)
        fun markDone(ctx: Context) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("first_run", false).apply()
        fun reset(ctx: Context) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("first_run", true).apply()
    }

    private var currentPage = 0
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private data class Page(val title: String, val desc: String, val color: String, val hasButton: Boolean = false)

    private val pages = listOf(
        Page("콜 자동 분석", "OnTheWay가 배달 콜을\n자동으로 분석합니다", "#5B6ABF"),
        Page("3단계 판정", "넘기세요 / 괜찮습니다 / 잡으세요\n로 알려드립니다", "#2EAA5E"),
        Page("권한 설정", "접근성 서비스와 알림 권한을\n켜주세요", "#FF9800", true)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.parseColor(pages[0].color)
        window.navigationBarColor = Color.parseColor(pages[0].color)

        showPage(0)
    }

    private fun showPage(index: Int) {
        currentPage = index
        val page = pages[index]

        window.statusBarColor = Color.parseColor(page.color)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor(page.color))
            setPadding(dp(40), dp(60), dp(40), dp(60))
        }

        // 페이지 인디케이터
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(40))
        }
        for (i in pages.indices) {
            dots.addView(View(this).apply {
                setBackgroundColor(if (i == index) Color.WHITE else Color.parseColor("#80FFFFFF"))
            }, LinearLayout.LayoutParams(dp(if (i == index) 24 else 8), dp(8)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        root.addView(dots)

        // 제목
        root.addView(TextView(this).apply {
            text = page.title; textSize = 32f
            setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        })

        // 설명
        root.addView(TextView(this).apply {
            text = page.desc; textSize = 18f
            setTextColor(Color.parseColor("#E0FFFFFF"))
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.4f)
            setPadding(0, 0, 0, dp(40))
        })

        // 권한 설정 버튼 (3장)
        if (page.hasButton) {
            root.addView(TextView(this).apply {
                text = "접근성 서비스 설정"; textSize = 16f
                setTextColor(Color.parseColor(page.color))
                setBackgroundColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }, LinearLayout.LayoutParams(MP, WC).apply { setMargins(0, 0, 0, dp(12)) })

            root.addView(TextView(this).apply {
                text = "알림 접근 설정"; textSize = 16f
                setTextColor(Color.parseColor(page.color))
                setBackgroundColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
                setOnClickListener { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
            }, LinearLayout.LayoutParams(MP, WC).apply { setMargins(0, 0, 0, dp(30)) })
        }

        // 다음/시작 버튼
        val isLast = index == pages.size - 1
        root.addView(TextView(this).apply {
            text = if (isLast) "시작하기" else "다음"
            textSize = 18f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(page.color))
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(18))
            setOnClickListener {
                if (isLast) {
                    markDone(this@OnboardingActivity)
                    startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                    finish()
                } else {
                    showPage(index + 1)
                }
            }
        }, LinearLayout.LayoutParams(MP, WC))

        // 건너뛰기
        if (!isLast) {
            root.addView(TextView(this).apply {
                text = "건너뛰기"; textSize = 14f
                setTextColor(Color.parseColor("#80FFFFFF"))
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
                setOnClickListener {
                    markDone(this@OnboardingActivity)
                    startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                    finish()
                }
            })
        }

        setContentView(root)
    }
}
