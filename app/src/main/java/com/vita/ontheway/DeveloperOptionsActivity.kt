package com.vita.ontheway

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * v3.20: 개발자 옵션 (숨김 진입 — 버전 번호 5회 탭)
 * Future 플래그 토글. 실제 로직 미연결 (그릇만).
 */
class DeveloperOptionsActivity : AppCompatActivity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FeatureFlags.load(this)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(40))
        }

        // 헤더
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
            text = "개발자 옵션"; textSize = 18f; setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(header, lp(MP, WC))
        root.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }, lp(MP, dp(1)))

        // 경고 배너
        root.addView(TextView(this).apply {
            text = "\u26A0\uFE0F 실험용 기능. 불안정하거나 데이터 오염 가능."
            textSize = 13f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#E53935"))
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }, lp(MP, WC))

        // Future 플래그 토글
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        card.addView(devToggle(
            "시급 표시", "EarningManager 시급 계산 및 화면 표시",
            FeatureFlags.hourlyWageDisplay
        ) { checked -> FeatureFlags.hourlyWageDisplay = checked; FeatureFlags.save(this) })

        card.addView(devToggle(
            "개인 프리셋", "운전자별 필터 프리셋 저장/불러오기",
            FeatureFlags.personalPreset
        ) { checked -> FeatureFlags.personalPreset = checked; FeatureFlags.save(this) })

        card.addView(devToggle(
            "지역 히트맵", "지역별 콜 빈도 시각화",
            FeatureFlags.regionHeatmap
        ) { checked -> FeatureFlags.regionHeatmap = checked; FeatureFlags.save(this) })

        card.addView(devToggle(
            "연결성 TTS", "Accessibility/Notification 연결 상태 음성 알림",
            FeatureFlags.connectivityTTS
        ) { checked -> FeatureFlags.connectivityTTS = checked; FeatureFlags.save(this) })

        card.addView(devToggle(
            "배민 거리 자동탭", "배민 ? 버튼 자동 클릭하여 거리 확보",
            FeatureFlags.baeminDistanceAutoTap
        ) { checked -> FeatureFlags.baeminDistanceAutoTap = checked; FeatureFlags.save(this) })

        card.addView(devToggle(
            "근접 TTS", "픽업/배달지 GPS 근접 시 음성 안내",
            FeatureFlags.proximityTTS
        ) { checked -> FeatureFlags.proximityTTS = checked; FeatureFlags.save(this) })

        root.addView(card, lp(MP, WC).apply { setMargins(dp(16), dp(16), dp(16), dp(8)) })

        // ─── AI API 키 설정 ───
        val aiCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        aiCard.addView(TextView(this).apply {
            text = "AI 보조 (Claude Haiku)"; textSize = 15f
            setTextColor(Color.BLACK); setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })

        // 현재 키 마스킹 표시
        val currentKey = ApiKeyManager.getApiKey(this)
        val hasKey = currentKey.isNotBlank()
        val keyStatusTv = TextView(this).apply {
            text = if (hasKey) "현재 키: ${ApiKeyManager.maskKey(currentKey)}" else "키 미설정"
            textSize = 12f
            setTextColor(if (hasKey) Color.parseColor("#4CAF50") else Color.parseColor("#E53935"))
            setPadding(0, 0, 0, dp(8))
        }
        aiCard.addView(keyStatusTv)

        // 입력 필드
        val keyInput = EditText(this).apply {
            hint = "sk-ant-api03-..."
            textSize = 13f; setTextColor(Color.BLACK)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        aiCard.addView(keyInput, lp(MP, WC).apply { bottomMargin = dp(8) })

        // 저장 / 삭제 버튼 행
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        // AI 토글 (키 없으면 비활성화)
        val aiSwitch = Switch(this).apply {
            isChecked = FeatureFlags.aiAssistEnabled && hasKey
            isEnabled = hasKey
            setOnCheckedChangeListener { _, v ->
                FeatureFlags.aiAssistEnabled = v
                FeatureFlags.save(this@DeveloperOptionsActivity)
            }
        }
        val aiToggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        aiToggleRow.addView(TextView(this).apply {
            text = "AI 보조 활성화"; textSize = 13f; setTextColor(Color.BLACK)
        }, lp(0, WC, 1f))
        aiToggleRow.addView(aiSwitch)

        btnRow.addView(TextView(this).apply {
            text = "저장"; textSize = 14f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#5B6ABF"))
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener {
                val raw = keyInput.text.toString().trim()
                if (raw.isBlank()) {
                    Toast.makeText(this@DeveloperOptionsActivity, "키를 입력하세요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                ApiKeyManager.saveApiKey(this@DeveloperOptionsActivity, raw)
                keyInput.setText("")
                keyStatusTv.text = "현재 키: ${ApiKeyManager.maskKey(raw)}"
                keyStatusTv.setTextColor(Color.parseColor("#4CAF50"))
                aiSwitch.isEnabled = true
                Toast.makeText(this@DeveloperOptionsActivity, "API 키 저장됨", Toast.LENGTH_SHORT).show()
            }
        }, lp(0, WC, 1f).apply { setMargins(0, 0, dp(4), 0) })

        btnRow.addView(TextView(this).apply {
            text = "삭제"; textSize = 14f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E53935"))
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener {
                ApiKeyManager.clearApiKey(this@DeveloperOptionsActivity)
                keyStatusTv.text = "키 미설정"
                keyStatusTv.setTextColor(Color.parseColor("#E53935"))
                aiSwitch.isChecked = false
                aiSwitch.isEnabled = false
                FeatureFlags.aiAssistEnabled = false
                FeatureFlags.save(this@DeveloperOptionsActivity)
                Toast.makeText(this@DeveloperOptionsActivity, "API 키 삭제됨", Toast.LENGTH_SHORT).show()
            }
        }, lp(0, WC, 1f).apply { setMargins(dp(4), 0, 0, 0) })

        aiCard.addView(btnRow)
        aiCard.addView(aiToggleRow)
        aiCard.addView(TextView(this).apply {
            text = "애매 구간(단가 1400~1700원/km)에서만 Claude Haiku 호출"
            textSize = 11f; setTextColor(Color.parseColor("#999999"))
            setPadding(0, dp(4), 0, 0)
        })

        root.addView(aiCard, lp(MP, WC).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) })

        // 개발자 통계 (DevStatsActivity) 진입
        root.addView(TextView(this).apply {
            text = "개발자 통계 (KPI 대시보드)"
            textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#5B6ABF"))
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                startActivity(Intent(this@DeveloperOptionsActivity, DevStatsActivity::class.java))
            }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) })

        scrollView.addView(root)
        setContentView(scrollView)
    }

    private fun devToggle(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this@DeveloperOptionsActivity).apply {
            text = title; textSize = 14f; setTextColor(Color.BLACK)
        }, lp(0, WC, 1f))
        row.addView(Switch(this@DeveloperOptionsActivity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
        container.addView(row)
        container.addView(TextView(this@DeveloperOptionsActivity).apply {
            text = desc
            textSize = 12f; setTextColor(Color.parseColor("#999999"))
            setPadding(0, dp(2), 0, dp(4))
        })
        container.addView(View(this@DeveloperOptionsActivity).apply {
            setBackgroundColor(Color.parseColor("#F0F0F0"))
        }, lp(MP, dp(1)))
        return container
    }
}
