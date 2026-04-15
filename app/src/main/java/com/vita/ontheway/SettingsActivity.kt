package com.vita.ontheway

import android.os.Bundle
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
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

        // ─── 필터 기준 설정 (SeekBar) ───
        root.addView(sectionTitle("필터 기준"))
        val filterCard = card()

        val minPriceBar = filterSeekBar(filterCard, "최소 배달료", CallFilter.getMinPrice(this), 1000, 5000, 500, "원")
        val minUnitBar = filterSeekBar(filterCard, "최소 단가", CallFilter.getMinUnitPrice(this), 1000, 3000, 100, "원/km")
        val highPriceBar = filterSeekBar(filterCard, "고액 콜 기준", TtsPrefs.getHighPriceThreshold(this), 5000, 15000, 1000, "원")
        val grabBar = filterSeekBar(filterCard, "잡으세요 기준", TtsPrefs.getGrabThreshold(this), 7000, 20000, 1000, "원")
        val multiMinBar = filterSeekBar(filterCard, "묶음 2건 최소", CallFilter.getMultiMinPrice(this), 3000, 8000, 500, "원")

        // "괜찮습니다" 음성 ON/OFF 토글
        val okVoiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        okVoiceRow.addView(TextView(this).apply {
            text = "\"괜찮습니다\" 음성 안내"
            textSize = 14f; setTextColor(Color.BLACK)
        }, lp(0, WC, 1f))
        val okToggle = Switch(this).apply {
            isChecked = CallFilter.isOkVoiceEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                CallFilter.setOkVoiceEnabled(this@SettingsActivity, checked)
            }
        }
        okVoiceRow.addView(okToggle)
        filterCard.addView(okVoiceRow)

        filterCard.addView(TextView(this).apply {
            text = "저장"
            textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#5B6ABF"))
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                CallFilter.setMinPrice(this@SettingsActivity, minPriceBar.second())
                CallFilter.setMinUnitPrice(this@SettingsActivity, minUnitBar.second())
                CallFilter.setMultiMinPrice(this@SettingsActivity, multiMinBar.second())
                TtsPrefs.setHighPriceThreshold(this@SettingsActivity, highPriceBar.second())
                TtsPrefs.setGrabThreshold(this@SettingsActivity, grabBar.second())
                Toast.makeText(this@SettingsActivity, "필터 설정 저장됨", Toast.LENGTH_SHORT).show()
            }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(4), dp(16), dp(4)) })

        filterCard.addView(TextView(this).apply {
            text = "기본값 복원"
            textSize = 13f; setTextColor(Color.parseColor("#E53935")); gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(14))
            setOnClickListener {
                CallFilter.setMinPrice(this@SettingsActivity, 3000)
                CallFilter.setMinUnitPrice(this@SettingsActivity, 2000)
                CallFilter.setMultiMinPrice(this@SettingsActivity, 5000)
                TtsPrefs.setHighPriceThreshold(this@SettingsActivity, 7000)
                TtsPrefs.setGrabThreshold(this@SettingsActivity, 10000)
                Toast.makeText(this@SettingsActivity, "기본값 복원됨", Toast.LENGTH_SHORT).show()
                recreate()
            }
        }, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // 오늘 필터 요약
        val detail = FilterLog.getTodayDetail(this)
        filterCard.addView(TextView(this).apply {
            text = "오늘: ${detail.total}건 (REJECT ${detail.reject} / ACCEPT ${detail.accept})\n" +
                "REJECT 평균 ${fmt(detail.rejectAvgPrice)}원 / ACCEPT 평균 ${fmt(detail.acceptAvgPrice)}원"
            textSize = 13f; setTextColor(Color.parseColor("#666666"))
            setPadding(dp(16), dp(4), dp(16), dp(16))
        })

        root.addView(filterCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // ─── 음성 안내 (TTS) 설정 ───
        root.addView(sectionTitle("음성 안내"))
        val ttsCard = card()

        ttsCard.addView(advancedToggle(
            "넘기세요만 안내",
            "ON이면 ACCEPT 콜은 음성 없이 로그만 기록",
            TtsPrefs.isRejectOnlyEnabled(this)
        ) { checked -> TtsPrefs.setRejectOnly(this, checked) })

        ttsCard.addView(advancedToggle(
            "잡으세요만 안내",
            "ON이면 잡으세요 판정만 음성 출력",
            TtsPrefs.isGrabOnlyEnabled(this)
        ) { checked -> TtsPrefs.setGrabOnly(this, checked) })

        // TTS 속도 SeekBar
        val speedBar = filterSeekBar(ttsCard, "TTS 속도", (TtsPrefs.getSpeed(this) * 10).toInt(), 5, 20, 1, "x0.1")

        ttsCard.addView(advancedToggle(
            "볼륨 부스트",
            "ON이면 TTS 발화 시 미디어 볼륨 최대",
            TtsPrefs.isVolBoostEnabled(this)
        ) { checked -> TtsPrefs.setVolBoost(this, checked) })

        // 금액 읽기 방식
        val priceReadRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        val curMode = TtsPrefs.getPriceReadMode(this)
        listOf("한국어" to "korean", "숫자" to "number").forEach { (label, value) ->
            priceReadRow.addView(TextView(this).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER
                val isSelected = value == curMode
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#5B6ABF"))
                setBackgroundColor(if (isSelected) Color.parseColor("#5B6ABF") else Color.parseColor("#F0F0F0"))
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener {
                    TtsPrefs.setPriceReadMode(this@SettingsActivity, value)
                    recreate()
                }
            }, lp(0, WC, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        ttsCard.addView(TextView(this).apply {
            text = "금액 읽기 방식"; textSize = 13f; setTextColor(Color.parseColor("#666666"))
            setPadding(dp(16), dp(8), dp(16), dp(2))
        })
        ttsCard.addView(priceReadRow)

        ttsCard.addView(TextView(this).apply {
            text = "TTS 설정 저장"
            textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#5B6ABF"))
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                TtsPrefs.setSpeed(this@SettingsActivity, speedBar.second() / 10f)
                Toast.makeText(this@SettingsActivity, "TTS 설정 저장됨", Toast.LENGTH_SHORT).show()
            }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(4), dp(16), dp(16)) })

        // v3.2: 진동 알림
        ttsCard.addView(advancedToggle(
            "진동 알림",
            "잡으세요: 강한 진동, 괜찮습니다: 보통 진동, 넘기세요: 없음",
            TtsPrefs.isVibrationEnabled(this)
        ) { checked -> TtsPrefs.setVibration(this, checked) })

        root.addView(ttsCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // ─── v3.2: 다크 모드 ───
        root.addView(sectionTitle("화면"))
        val displayCard = card()
        val darkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val curDark = TtsPrefs.getDarkMode(this)
        listOf("자동" to "auto", "항상 켜기" to "on", "항상 끄기" to "off").forEach { (label, value) ->
            darkRow.addView(TextView(this).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER
                val isSelected = value == curDark
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#5B6ABF"))
                setBackgroundColor(if (isSelected) Color.parseColor("#5B6ABF") else Color.parseColor("#F0F0F0"))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    TtsPrefs.setDarkMode(this@SettingsActivity, value)
                    applyDarkMode(value)
                    recreate()
                }
            }, lp(0, WC, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        displayCard.addView(TextView(this).apply {
            text = "다크 모드"; textSize = 14f; setTextColor(Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(4))
        })
        displayCard.addView(darkRow)
        root.addView(displayCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // ─── 음성 수락 ON/OFF ───
        val voiceAcceptRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        voiceAcceptRow.addView(TextView(this).apply {
            text = "\"잡아\" 음성 수락 (OFF=테스트, ON=실제 수락)"
            textSize = 14f; setTextColor(Color.BLACK)
        }, lp(0, WC, 1f))
        val voiceAcceptToggle = Switch(this).apply {
            isChecked = CallFilter.isVoiceAcceptEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                CallFilter.setVoiceAcceptEnabled(this@SettingsActivity, checked)
                Toast.makeText(this@SettingsActivity,
                    if (checked) "음성 수락 활성화" else "음성 수락 테스트 모드",
                    Toast.LENGTH_SHORT).show()
            }
        }
        voiceAcceptRow.addView(voiceAcceptToggle)
        filterCard.addView(voiceAcceptRow)

        // ─── v3.2: 데이터 내보내기 (공유 가능) ───
        filterCard.addView(TextView(this).apply {
            text = "전체 데이터 내보내기 (CSV)"
            textSize = 14f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#5B6ABF")); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { exportAndShare(false) }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(8), dp(16), dp(4)) })

        filterCard.addView(TextView(this).apply {
            text = "오늘만 내보내기 (CSV)"
            textSize = 14f
            setTextColor(Color.parseColor("#5B6ABF")); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { exportAndShare(true) }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(4), dp(16), dp(16)) })

        // ─── 필터 기록 (최근 20건) ───
        root.addView(sectionTitle("필터 기록 (최근 20건)"))
        val logCard = card()
        val recentLogs = FilterLog.getRecent(this, 20)
        if (recentLogs.isEmpty()) {
            logCard.addView(TextView(this).apply {
                text = "기록 없음"
                textSize = 13f; setTextColor(Color.parseColor("#999999"))
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
        } else {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            for (entry in recentLogs) {
                val ts = sdf.format(java.util.Date(entry.getLong("ts")))
                val platform = entry.optString("platform", "?")
                val price = entry.optInt("price", 0)
                val unitPrice = entry.optInt("unitPrice", 0)
                val verdict = entry.optString("verdict", "?")
                val verdictColor = if (verdict == "REJECT") "#E53935" else "#4CAF50"

                logCard.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(16), dp(6), dp(16), dp(6))
                    addView(TextView(this@SettingsActivity).apply {
                        text = ts; textSize = 12f; setTextColor(Color.parseColor("#999999"))
                    }, lp(WC, WC).apply { marginEnd = dp(8) })
                    addView(TextView(this@SettingsActivity).apply {
                        text = platform; textSize = 12f; setTextColor(Color.parseColor("#5B6ABF"))
                    }, lp(WC, WC).apply { marginEnd = dp(8) })
                    addView(TextView(this@SettingsActivity).apply {
                        text = "${fmt(price)}원"; textSize = 12f; setTextColor(Color.BLACK)
                    }, lp(WC, WC).apply { marginEnd = dp(8) })
                    addView(TextView(this@SettingsActivity).apply {
                        text = if (unitPrice > 0) "${fmt(unitPrice)}원/km" else "-"
                        textSize = 12f; setTextColor(Color.parseColor("#666666"))
                    }, lp(0, WC, 1f))
                    addView(TextView(this@SettingsActivity).apply {
                        text = verdict; textSize = 12f; setTextColor(Color.parseColor(verdictColor))
                        setTypeface(null, Typeface.BOLD)
                    })
                })
            }
        }
        root.addView(logCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // ─── v3.0 고급 기능 섹션 ───
        root.addView(sectionTitle("고급 기능 (v3.0)"))
        val advCard = card()

        // 1. 수익 트래킹
        advCard.addView(advancedToggle(
            "수익 트래킹",
            "수락된 콜의 금액을 자동으로 누적 추적합니다",
            AdvancedPrefs.isEarningsTrackingEnabled(this)
        ) { checked -> AdvancedPrefs.setEarningsTracking(this, checked) })

        // 2. 네비 자동실행
        advCard.addView(advancedToggle(
            "네비 자동실행",
            "콜 수락 시 픽업지로 네비게이션을 자동 실행합니다",
            AdvancedPrefs.isNaviAutoLaunchEnabled(this)
        ) { checked -> AdvancedPrefs.setNaviAutoLaunch(this, checked) })

        // 네비앱 선택
        val naviAppRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }
        val curNavi = AdvancedPrefs.getNaviApp(this)
        listOf("카카오내비" to "kakao_navi", "티맵" to "tmap", "카카오맵" to "kakao_map").forEach { (label, value) ->
            naviAppRow.addView(TextView(this).apply {
                text = label; textSize = 13f
                val isSelected = value == curNavi
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#5B6ABF"))
                setBackgroundColor(if (isSelected) Color.parseColor("#5B6ABF") else Color.parseColor("#F0F0F0"))
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    AdvancedPrefs.setNaviApp(this@SettingsActivity, value)
                    recreate()
                }
            }, lp(0, WC, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        advCard.addView(naviAppRow)

        // 3. 자동 수락
        advCard.addView(advancedToggle(
            "자동 수락 (잡으세요만)",
            "잡으세요 판정된 콜만 자동으로 수락합니다 (60초 쿨다운)",
            AdvancedPrefs.isAutoAcceptEnabled(this)
        ) { checked -> AdvancedPrefs.setAutoAccept(this, checked) })

        // 4. 귀가 방향 필터
        advCard.addView(advancedToggle(
            "귀가 방향 필터",
            "귀가 방향 콜에 보너스, 반대 방향에 페널티를 적용합니다",
            AdvancedPrefs.isDirectionFilterEnabled(this)
        ) { checked -> AdvancedPrefs.setDirectionFilter(this, checked) })

        // 귀가 방향 주소 입력
        val homeDirInput = EditText(this).apply {
            hint = "귀가 방향 주소 (예: 성동구 성수동)"
            setText(AdvancedPrefs.getHomeDirection(this@SettingsActivity))
            textSize = 14f; setTextColor(Color.BLACK); setHintTextColor(Color.parseColor("#BBBBBB"))
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(dp(16), dp(12), dp(16), dp(12)); setSingleLine(true)
        }
        advCard.addView(homeDirInput, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(4)) })
        advCard.addView(TextView(this).apply {
            text = "귀가 방향 저장"
            textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#5B6ABF")); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener {
                AdvancedPrefs.setHomeDirection(this@SettingsActivity, homeDirInput.text.toString().trim())
                Toast.makeText(this@SettingsActivity, "귀가 방향 저장됨", Toast.LENGTH_SHORT).show()
            }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(4), dp(16), dp(12)) })

        // 5. 일별 리포트
        advCard.addView(advancedToggle(
            "일별 리포트",
            "30분간 새 콜이 없으면 오늘 요약 알림을 생성합니다",
            AdvancedPrefs.isDailyReportEnabled(this)
        ) { checked -> AdvancedPrefs.setDailyReport(this, checked) })

        root.addView(advCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // ─── v2.2 진단 모드 섹션 ───
        root.addView(sectionTitle("진단"))
        val diagCard = card()
        diagCard.addView(TextView(this).apply {
            text = "패키지별 이벤트 수 확인"
            textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FF9800"))
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { showDiagnosticDialog() }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(12), dp(16), dp(12)) })
        diagCard.addView(TextView(this).apply {
            text = "Accessibility로 수신된 패키지별 이벤트 수를 표시합니다.\n카카오T가 0건이면 이벤트 자체가 안 오는 것입니다."
            textSize = 12f; setTextColor(Color.parseColor("#999999"))
            setPadding(dp(16), 0, dp(16), dp(12))
        })
        root.addView(diagCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

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

    private fun applyDarkMode(mode: String) {
        val nightMode = when (mode) {
            "on" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            "off" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private fun exportAndShare(todayOnly: Boolean) {
        val path = FilterLog.exportCsv(this)
        if (path == null) {
            Toast.makeText(this, "내보낼 데이터 없음", Toast.LENGTH_SHORT).show()
            return
        }
        val file = java.io.File(path)
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(shareIntent, "CSV 내보내기"))
        } catch (e: Exception) {
            // FileProvider 미설정 시 경로만 표시
            Toast.makeText(this, "저장됨: $path", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDiagnosticDialog() {
        val counts = OnTheWayService.packageEventCount.toList()
            .sortedByDescending { it.second }
        val sb = StringBuilder()

        // 카카오 관련 패키지 먼저
        val kakaoPackages = counts.filter {
            it.first.contains("kakaomobility") || it.first.contains("flexer") ||
            it.first.contains("kakao.taxi")
        }
        if (kakaoPackages.isEmpty()) {
            sb.appendLine("★ 카카오T 관련 이벤트: 0건 (미수신)")
            sb.appendLine("→ Accessibility 이벤트가 오지 않습니다")
            sb.appendLine("→ NotificationListener 대안 경로 사용")
            sb.appendLine()
        } else {
            sb.appendLine("★ 카카오T 관련:")
            kakaoPackages.forEach { (pkg, count) ->
                sb.appendLine("  $pkg: ${count}건")
            }
            sb.appendLine()
        }

        // 배달 플랫폼
        val deliveryPackages = counts.filter {
            it.first.contains("coupang") || it.first.contains("woowahan")
        }
        if (deliveryPackages.isNotEmpty()) {
            sb.appendLine("배달 플랫폼:")
            deliveryPackages.forEach { (pkg, count) ->
                sb.appendLine("  $pkg: ${count}건")
            }
            sb.appendLine()
        }

        // 기타 (상위 10개)
        val others = counts.filter { (pkg, _) ->
            !pkg.contains("kakaomobility") && !pkg.contains("flexer") &&
            !pkg.contains("kakao.taxi") && !pkg.contains("coupang") &&
            !pkg.contains("woowahan")
        }.take(10)
        if (others.isNotEmpty()) {
            sb.appendLine("기타 (상위 10개):")
            others.forEach { (pkg, count) ->
                sb.appendLine("  $pkg: ${count}건")
            }
        }

        if (counts.isEmpty()) {
            sb.appendLine("아직 수신된 이벤트가 없습니다.")
            sb.appendLine("앱을 백그라운드에 두고 다른 앱을 사용해보세요.")
        }

        sb.appendLine()
        sb.appendLine("총 ${counts.sumOf { it.second }}건 / ${counts.size}개 패키지")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("진단: 패키지별 이벤트 수")
            .setMessage(sb.toString())
            .setPositiveButton("확인", null)
            .setNeutralButton("초기화") { _, _ ->
                OnTheWayService.packageEventCount.clear()
                Toast.makeText(this, "카운터 초기화됨", Toast.LENGTH_SHORT).show()
            }
            .show()
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

    private fun advancedToggle(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this@SettingsActivity).apply {
            text = title
            textSize = 14f; setTextColor(Color.BLACK)
        }, lp(0, WC, 1f))
        row.addView(Switch(this@SettingsActivity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
        container.addView(row)
        container.addView(TextView(this@SettingsActivity).apply {
            text = desc
            textSize = 12f; setTextColor(Color.parseColor("#999999"))
            setPadding(0, dp(2), 0, dp(4))
        })
        // 구분선
        container.addView(View(this@SettingsActivity).apply {
            setBackgroundColor(Color.parseColor("#F0F0F0"))
        }, lp(MP, dp(1)))
        return container
    }

    private fun filterInput(parent: LinearLayout, label: String, value: Int): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 13f; setTextColor(Color.parseColor("#666666"))
            setPadding(dp(16), dp(14), dp(16), dp(4))
        })
        val input = EditText(this).apply {
            setText(value.toString())
            textSize = 16f; setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        parent.addView(input, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(4)) })
        return input
    }

    /** SeekBar + 실시간 값 표시. Pair(SeekBar, getValue 람다) 반환 */
    private fun filterSeekBar(
        parent: LinearLayout, label: String, current: Int,
        min: Int, max: Int, step: Int, unit: String
    ): Pair<SeekBar, () -> Int> {
        val valueText = TextView(this).apply {
            text = "${fmt(current)}$unit"
            textSize = 14f; setTextColor(Color.parseColor("#5B6ABF"))
            setTypeface(null, Typeface.BOLD)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(0))
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(Color.parseColor("#666666"))
        }, lp(0, WC, 1f))
        row.addView(valueText)
        parent.addView(row)

        val steps = (max - min) / step
        val seekBar = SeekBar(this).apply {
            this.max = steps
            progress = ((current - min) / step).coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = min + progress * step
                    valueText.text = "${fmt(v)}$unit"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        parent.addView(seekBar, lp(MP, WC).apply { setMargins(dp(16), dp(4), dp(16), dp(8)) })

        return Pair(seekBar) { min + seekBar.progress * step }
    }
}
