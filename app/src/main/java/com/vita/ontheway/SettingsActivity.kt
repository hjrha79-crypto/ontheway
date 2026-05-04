package com.vita.ontheway

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.speech.tts.TextToSpeech
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private var testTts: TextToSpeech? = null
    private var testTtsReady = false

    // Sprint 5: GPS status display
    private var gpsModeTv: TextView? = null
    private var gpsLastLocTv: TextView? = null
    private var gpsCountTv: TextView? = null
    private var gpsDurationTv: TextView? = null
    private var gpsIntervalTv: TextView? = null

    private val ttsTestMessages = listOf(
        "201동 1203호, 문 앞",
        "305동 702호",
        "벨 누르지 마세요",
        "비대면, 문 앞",
        "전화 주세요"
    )

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
        filterCard.addView(TextView(this).apply {
            text = "권장: 1,200~1,400원/km (피드백 분석 기반)"
            textSize = 11f; setTextColor(Color.parseColor("#5B6ABF"))
            setPadding(dp(16), 0, dp(16), dp(8))
        })
        val highPriceBar = filterSeekBar(filterCard, "고액 자동 통과", TtsPrefs.getHighPriceThreshold(this), 5000, 15000, 1000, "원")
        filterCard.addView(TextView(this).apply {
            text = "이 금액 이상이면 단가 무관 자동 ACCEPT"
            textSize = 12f; setTextColor(Color.parseColor("#888888"))
            setPadding(dp(16), 0, dp(16), dp(8))
        })
        val grabBar = filterSeekBar(filterCard, "추천 기준", TtsPrefs.getGrabThreshold(this), 7000, 20000, 1000, "원")
        filterCard.addView(TextView(this).apply {
            text = "이 금액 이상이면 무조건 '추천' TTS"
            textSize = 12f; setTextColor(Color.parseColor("#888888"))
            setPadding(dp(16), 0, dp(16), dp(8))
        })
        val multiMinBar = filterSeekBar(filterCard, "묶음 2건 최소", CallFilter.getMultiMinPrice(this), 3000, 8000, 500, "원")

        // "보통" 음성 ON/OFF 토글
        val okVoiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        okVoiceRow.addView(TextView(this).apply {
            text = "\"보통\" 음성 안내"
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

        // ─── v3.19: 블랙리스트 관리 ───
        root.addView(sectionTitle("블랙리스트 관리"))
        val blacklistCard = card()
        val blacklist = StoreManager.getBlacklist(this)
        if (blacklist.isEmpty()) {
            blacklistCard.addView(TextView(this).apply {
                text = "등록된 가게가 없습니다"
                textSize = 14f; setTextColor(Color.parseColor("#999999"))
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
        } else {
            blacklist.forEach { entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                }
                val platformLabel = when (entry.platform) {
                    "coupang" -> "쿠팡"; "baemin" -> "배민"; "kakaot" -> "카카오T"; else -> ""
                }
                row.addView(TextView(this@SettingsActivity).apply {
                    text = if (platformLabel.isNotEmpty()) "${entry.name} ($platformLabel)" else entry.name
                    textSize = 14f; setTextColor(Color.BLACK)
                }, lp(0, WC, 1f))
                row.addView(TextView(this@SettingsActivity).apply {
                    text = "해제"
                    textSize = 13f; setTextColor(Color.parseColor("#E53935"))
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    setOnClickListener {
                        android.app.AlertDialog.Builder(this@SettingsActivity)
                            .setMessage("'${entry.name}' 블랙리스트 해제하시겠습니까?")
                            .setPositiveButton("확인") { _, _ ->
                                StoreManager.removeBlacklist(this@SettingsActivity, entry.name)
                                Toast.makeText(this@SettingsActivity, "${entry.name} 해제됨", Toast.LENGTH_SHORT).show()
                                recreate()
                            }
                            .setNegativeButton("취소", null)
                            .show()
                    }
                })
                blacklistCard.addView(row)
                blacklistCard.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#F0F0F0"))
                }, lp(MP, dp(1)))
            }
        }
        root.addView(blacklistCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // ─── v3.19: 즐겨찾기 관리 ───
        root.addView(sectionTitle("즐겨찾기 관리"))
        val favCard = card()
        val favorites = StoreManager.getFavorites(this)
        if (favorites.isEmpty()) {
            favCard.addView(TextView(this).apply {
                text = "등록된 가게가 없습니다"
                textSize = 14f; setTextColor(Color.parseColor("#999999"))
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
        } else {
            favorites.forEach { entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                }
                val platformLabel = when (entry.platform) {
                    "coupang" -> "쿠팡"; "baemin" -> "배민"; "kakaot" -> "카카오T"; else -> ""
                }
                row.addView(TextView(this@SettingsActivity).apply {
                    text = if (platformLabel.isNotEmpty()) "${entry.name} ($platformLabel)" else entry.name
                    textSize = 14f; setTextColor(Color.BLACK)
                }, lp(0, WC, 1f))
                row.addView(TextView(this@SettingsActivity).apply {
                    text = "해제"
                    textSize = 13f; setTextColor(Color.parseColor("#E53935"))
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    setOnClickListener {
                        android.app.AlertDialog.Builder(this@SettingsActivity)
                            .setMessage("'${entry.name}' 즐겨찾기 해제하시겠습니까?")
                            .setPositiveButton("확인") { _, _ ->
                                StoreManager.removeFavorite(this@SettingsActivity, entry.name)
                                Toast.makeText(this@SettingsActivity, "${entry.name} 해제됨", Toast.LENGTH_SHORT).show()
                                recreate()
                            }
                            .setNegativeButton("취소", null)
                            .show()
                    }
                })
                favCard.addView(row)
                favCard.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#F0F0F0"))
                }, lp(MP, dp(1)))
            }
        }
        root.addView(favCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // ─── v3.20: 피드백 내역 ───
        root.addView(sectionTitle("피드백 내역"))
        val fbCard = card()
        val recentFeedback = FeedbackLogger.getRecent(this, 10)
        if (recentFeedback.isEmpty()) {
            fbCard.addView(TextView(this).apply {
                text = "아직 피드백이 없습니다"
                textSize = 14f; setTextColor(Color.parseColor("#999999"))
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
        } else {
            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            for (fb in recentFeedback) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                }
                // 👍/👎 아이콘
                val isUp = fb.feedback == "up"
                row.addView(TextView(this@SettingsActivity).apply {
                    text = if (isUp) "\uD83D\uDC4D" else "\uD83D\uDC4E"
                    textSize = 18f
                }, lp(WC, WC).apply { marginEnd = dp(8) })

                // 시간 + 플랫폼 + 가게명 + 금액 + 이유
                val infoCol = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val platformLabel = when (fb.platform) {
                    "coupang" -> "쿠팡"; "baemin" -> "배민"; "kakaot" -> "카카오T"; else -> fb.platform
                }
                val timeStr = sdf.format(java.util.Date(fb.ts))
                val storeTxt = if (fb.storeName.isNotBlank()) fb.storeName else "(가게명 없음)"
                infoCol.addView(TextView(this@SettingsActivity).apply {
                    text = "$timeStr  $platformLabel  $storeTxt"
                    textSize = 13f; setTextColor(Color.BLACK); setSingleLine(true)
                })
                val distInfo = if (fb.platformDistanceKm != null || fb.onthewayDistanceKm != null) {
                    val pDist = fb.platformDistanceKm?.let { "${"%.1f".format(it)}km" } ?: "-"
                    val oDist = fb.onthewayDistanceKm?.let { "${"%.1f".format(it)}km" } ?: "-"
                    val diff = fb.distanceDiffKm?.let { "${"%.1f".format(it)}km" } ?: "-"
                    "${platformLabel}표시 $pDist / OTW계산 $oDist / 차이 $diff"
                } else {
                    fb.reasons.joinToString(", ").ifEmpty { "-" }
                }
                infoCol.addView(TextView(this@SettingsActivity).apply {
                    text = "${fmt(fb.price)}원 · $distInfo"
                    textSize = 12f; setTextColor(Color.parseColor("#666666")); setSingleLine(true)
                })
                row.addView(infoCol, lp(0, WC, 1f))

                // 덮어쓰기 표시
                if (fb.overwroteTs != null) {
                    row.addView(TextView(this@SettingsActivity).apply {
                        text = "수정됨"
                        textSize = 11f; setTextColor(Color.parseColor("#FF9800"))
                    })
                }

                fbCard.addView(row)
                fbCard.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#F0F0F0"))
                }, lp(MP, dp(1)))
            }

            // 요약 행
            val allFb = FeedbackLogger.getAll(this)
            val upCount = allFb.count { it.feedback == "up" }
            val downCount = allFb.count { it.feedback == "down" }
            fbCard.addView(TextView(this).apply {
                text = "전체 ${allFb.size}건 (\uD83D\uDC4D $upCount / \uD83D\uDC4E $downCount)"
                textSize = 12f; setTextColor(Color.parseColor("#999999"))
                setPadding(dp(16), dp(8), dp(16), dp(12))
            })
        }
        root.addView(fbCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // ─── 음성 안내 (TTS) 설정 ───
        root.addView(sectionTitle("음성 안내"))
        val ttsCard = card()

        ttsCard.addView(advancedToggle(
            "비추천만 안내",
            "ON이면 ACCEPT 콜은 음성 없이 로그만 기록",
            TtsPrefs.isRejectOnlyEnabled(this)
        ) { checked -> TtsPrefs.setRejectOnly(this, checked) })

        ttsCard.addView(advancedToggle(
            "추천만 안내",
            "ON이면 추천 판정만 음성 출력",
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

        // v3.22 Sprint 4: TTS 테스트 버튼
        ttsCard.addView(TextView(this).apply {
            text = "TTS 테스트 (헬멧 BT 확인)"
            textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { playTtsTest() }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(4), dp(16), dp(4)) })

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
            "추천: 강한 진동, 보통: 보통 진동, 비추천: 없음",
            TtsPrefs.isVibrationEnabled(this)
        ) { checked -> TtsPrefs.setVibration(this, checked) })

        // ─── v3.26: TTS 프리셋 4단계 ───
        ttsCard.addView(TextView(this).apply {
            text = "음성 안내 수준"; textSize = 14f; setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(4))
        })
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        val currentPreset = FeatureFlags.ttsPreset
        TtsPreset.entries.forEach { preset ->
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            itemLayout.addView(TextView(this).apply {
                text = preset.label; textSize = 13f; gravity = Gravity.CENTER
                val isSelected = preset == currentPreset
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#5B6ABF"))
                setBackgroundColor(if (isSelected) Color.parseColor("#5B6ABF") else Color.parseColor("#F0F0F0"))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    FeatureFlags.ttsPreset = preset
                    FeatureFlags.save(this@SettingsActivity)
                    recreate()
                }
            })
            itemLayout.addView(TextView(this).apply {
                text = preset.desc; textSize = 10f; setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            })
            itemLayout.addView(TextView(this).apply {
                text = "\uD83D\uDD0A"; textSize = 16f; gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener { playPresetDemo(preset) }
            })
            presetRow.addView(itemLayout, lp(0, WC, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        ttsCard.addView(presetRow)

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

        // ─── v3.20: 오버레이 권한 ───
        root.addView(sectionTitle("오버레이 권한"))
        val overlayPermCard = card()
        val hasOverlayPerm = Settings.canDrawOverlays(this)
        overlayPermCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(TextView(this@SettingsActivity).apply {
                text = if (hasOverlayPerm) "오버레이 권한: 허용됨" else "오버레이 권한: 미허용"
                textSize = 14f
                setTextColor(if (hasOverlayPerm) Color.parseColor("#4CAF50") else Color.parseColor("#E53935"))
            }, lp(0, WC, 1f))
            if (!hasOverlayPerm) {
                addView(TextView(this@SettingsActivity).apply {
                    text = "권한 허용"
                    textSize = 14f; setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#5B6ABF"))
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    setOnClickListener {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                })
            }
        })
        root.addView(overlayPermCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

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
            "자동 수락 (추천만)",
            "추천 판정된 콜만 자동으로 수락합니다 (60초 쿨다운)",
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

        // v3.22: 운행 모드
        val drivingRow = advancedToggle(
            "운행 모드",
            "콜 수락 시 자동 ON. GPS 궤적 수집 시작. 수동 OFF는 여기서.",
            DrivingModeManager.getMode(this) == DrivingMode.DRIVING
        ) { checked ->
            val mode = if (checked) DrivingMode.DRIVING else DrivingMode.IDLE
            DrivingModeManager.setMode(this, mode)
            Toast.makeText(this, if (checked) "운행 모드 ON" else "운행 모드 OFF", Toast.LENGTH_SHORT).show()
        }
        advCard.addView(drivingRow)

        // v3.22: 운행 시간 표시
        val drivingMs = DrivingModeManager.getTodayDrivingTimeMs(this)
        val drivingMin = (drivingMs / 60000).toInt()
        val drivingHr = drivingMin / 60
        val drivingMinRem = drivingMin % 60
        val drivingTimeStr = if (drivingHr > 0) "${drivingHr}시간 ${drivingMinRem}분" else "${drivingMinRem}분"
        advCard.addView(TextView(this).apply {
            text = "오늘 운행 시간: $drivingTimeStr"
            textSize = 12f; setTextColor(Color.parseColor("#666666"))
            setPadding(dp(16), 0, dp(16), dp(8))
        })

        // v3.4: GPS 위치 사용
        advCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(TextView(this@SettingsActivity).apply {
                text = "GPS 위치 사용"
                textSize = 14f; setTextColor(Color.parseColor("#333333"))
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(this@SettingsActivity).apply {
                val isDriving = DrivingModeManager.getMode(this@SettingsActivity) == DrivingMode.DRIVING
                text = if (isDriving) "운행 모드와 함께 작동 중 (ON)" else "운행 모드와 함께 작동 (OFF)"
                textSize = 12f; setTextColor(if (isDriving) Color.parseColor("#2EAA5E") else Color.parseColor("#888888"))
                setPadding(0, dp(2), 0, 0)
            })
        })

        // v3.3: 피크 자동 조절
        advCard.addView(advancedToggle(
            "피크 자동 조절",
            "피크 시간대 +500원, 비피크 시간대 -500원 자동 조절",
            PeakDetector.isPeakAutoEnabled(this)
        ) { checked -> PeakDetector.setPeakAuto(this, checked) })

        // 쿠팡 진단 모드
        advCard.addView(advancedToggle(
            "쿠팡 진단 모드",
            "쿠팡 Accessibility 이벤트를 DiagnosticLog에 기록 (디버그용)",
            AdvancedPrefs.isCoupangDebugEnabled(this)
        ) { checked -> AdvancedPrefs.setCoupangDebug(this, checked) })

        // v3.14: 진단 로그 관리 UI
        DiagnosticLog.init(this)
        val diagContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }
        val diagCountText = TextView(this).apply {
            text = "진단 로그 ${DiagnosticLog.count()}건 저장됨"
            textSize = 12f; setTextColor(Color.GRAY)
        }
        diagContainer.addView(diagCountText)
        val diagBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        diagBtnRow.addView(Button(this).apply {
            text = "CSV 내보내기"
            textSize = 12f
            setOnClickListener {
                val path = DiagnosticLog.exportCsv(this@SettingsActivity)
                if (path != null) {
                    Toast.makeText(this@SettingsActivity, "저장: $path", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@SettingsActivity, "내보낼 로그 없음", Toast.LENGTH_SHORT).show()
                }
            }
        })
        diagBtnRow.addView(Button(this).apply {
            text = "로그 삭제"
            textSize = 12f
            setOnClickListener {
                DiagnosticLog.clear()
                diagCountText.text = "진단 로그 0건 저장됨"
                Toast.makeText(this@SettingsActivity, "진단 로그 삭제됨", Toast.LENGTH_SHORT).show()
            }
        })
        diagContainer.addView(diagBtnRow)
        advCard.addView(diagContainer)

        // v3.22: 상단 바 오버레이
        advCard.addView(advancedToggle(
            "상단 바 오버레이",
            "판정 결과를 화면 상단 바로 1.5초 표시 (오버레이 권한 필요)",
            FeatureFlags.overlayEnabled
        ) { checked ->
            FeatureFlags.overlayEnabled = checked
            FeatureFlags.save(this)
        })


        // v3.22: 백그라운드 수집
        advCard.addView(advancedToggle(
            "백그라운드 수집",
            "OFF: 운행 모드 ON 시에만 콜 감지 (배터리 절약)\nON: 운행 OFF 시에도 콜 캐치 (배터리 소모 증가)",
            FeatureFlags.backgroundCapture
        ) { checked ->
            FeatureFlags.backgroundCapture = checked
            FeatureFlags.save(this)
        })

        // v3.5: 배터리 절약 모드
        advCard.addView(advancedToggle(
            "배터리 절약 모드",
            "GPS 빈도 자동 조절 (대기 60초, 배달 중 15초, 화면 OFF 120초)",
            AdvancedPrefs.isBatterySaverEnabled(this)
        ) { checked -> AdvancedPrefs.setBatterySaver(this, checked) })

        // v3.3: 연속 넘김 경고
        advCard.addView(advancedToggle(
            "연속 넘김 경고",
            "5건/10건 연속 REJECT 시 TTS 경고",
            AdvancedPrefs.isRejectWarningEnabled(this)
        ) { checked -> AdvancedPrefs.setRejectWarning(this, checked) })

        // v3.3: 배달 완료 안내
        advCard.addView(advancedToggle(
            "배달 완료 안내",
            "배달 완료 감지 시 오늘 실적 TTS 안내",
            AdvancedPrefs.isDeliveryCompleteEnabled(this)
        ) { checked -> AdvancedPrefs.setDeliveryComplete(this, checked) })

        // v3.3: 콜 알림음
        advCard.addView(advancedToggle(
            "콜 알림음",
            "판정별 다른 알림음 재생 (사운드 파일 없으면 기본음)",
            AdvancedPrefs.isCallSoundEnabled(this)
        ) { checked -> AdvancedPrefs.setCallSound(this, checked) })

        // v3.3: 목표 알림
        advCard.addView(advancedToggle(
            "목표 알림",
            "목표 50%/100% 달성 시 TTS 알림",
            GoalManager.isGoalAlertEnabled(this)
        ) { checked -> GoalManager.setGoalAlert(this, checked) })

        // 카카오 API 키 입력
        val kakaoKeyRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        kakaoKeyRow.addView(TextView(this).apply {
            text = "카카오 지오코딩 API 키"
            textSize = 13f; setTextColor(Color.parseColor("#333333"))
            setTypeface(null, Typeface.BOLD)
        })
        kakaoKeyRow.addView(TextView(this).apply {
            text = "정확한 거리 계산용 (미입력 시 동 중심점 사용)"
            textSize = 11f; setTextColor(Color.parseColor("#888888"))
            setPadding(0, dp(2), 0, dp(6))
        })
        val kakaoPrefs = getSharedPreferences("advanced_prefs", MODE_PRIVATE)
        val currentKey = kakaoPrefs.getString("kakao_api_key", "") ?: ""
        val kakaoInput = android.widget.EditText(this).apply {
            hint = "REST API 키 입력"
            setText(currentKey)
            textSize = 13f; setSingleLine(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }
        kakaoKeyRow.addView(kakaoInput)
        val kakaoStatus = TextView(this).apply {
            text = if (currentKey.isNotBlank()) "활성화됨 (캐시 ${KakaoGeocoder.getCacheSize(this@SettingsActivity)}건)" else "비활성화"
            textSize = 11f; setTextColor(if (currentKey.isNotBlank()) Color.parseColor("#2EAA5E") else Color.parseColor("#888888"))
            setPadding(0, dp(4), 0, 0)
        }
        kakaoKeyRow.addView(kakaoStatus)
        kakaoKeyRow.addView(TextView(this).apply {
            text = "저장"; textSize = 13f; setTextColor(Color.parseColor("#5B6ABF"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
            setOnClickListener {
                val key = kakaoInput.text.toString().trim()
                kakaoPrefs.edit().putString("kakao_api_key", key).apply()
                kakaoStatus.text = if (key.isNotBlank()) "저장됨 — 다음 콜부터 적용" else "비활성화"
                kakaoStatus.setTextColor(if (key.isNotBlank()) Color.parseColor("#2EAA5E") else Color.parseColor("#888888"))
                android.widget.Toast.makeText(this@SettingsActivity, if (key.isNotBlank()) "카카오 API 키 저장됨" else "API 키 삭제됨", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        advCard.addView(kakaoKeyRow)

        root.addView(advCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // ─── v3.22 Sprint 5: GPS 상태 ───
        root.addView(sectionTitle("GPS 상태"))
        val gpsCard = card()

        fun gpsRow(label: String): TextView {
            val row = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            row.addView(TextView(this@SettingsActivity).apply {
                text = label; textSize = 13f; setTextColor(Color.parseColor("#666666"))
            }, lp(0, WC, 1f))
            val valueTv = TextView(this@SettingsActivity).apply {
                text = "-"; textSize = 13f; setTextColor(Color.BLACK)
                setTypeface(null, Typeface.BOLD)
            }
            row.addView(valueTv, lp(0, WC, 2f))
            gpsCard.addView(row)
            gpsCard.addView(View(this@SettingsActivity).apply {
                setBackgroundColor(Color.parseColor("#F0F0F0"))
            }, lp(MP, dp(1)))
            return valueTv
        }

        gpsModeTv = gpsRow("현재 모드")
        gpsLastLocTv = gpsRow("마지막 위치")
        gpsCountTv = gpsRow("누적 위치 수")
        gpsDurationTv = gpsRow("오늘 운행 시간")
        gpsIntervalTv = gpsRow("GPS 간격")

        gpsCard.addView(TextView(this).apply {
            text = "새로고침"
            textSize = 14f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#2196F3"))
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { refreshGpsStatus() }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(8), dp(16), dp(12)) })

        root.addView(gpsCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // ─── v3.3: 목표 설정 섹션 ───
        root.addView(sectionTitle("목표 설정"))
        val goalSettCard = card()
        val goalAmtBar = filterSeekBar(goalSettCard, "일일 목표 금액", GoalManager.getGoalAmount(this), 50000, 300000, 10000, "원")
        val goalCntBar = filterSeekBar(goalSettCard, "일일 목표 건수", GoalManager.getGoalCount(this), 10, 100, 5, "건")
        val goalHrBar = filterSeekBar(goalSettCard, "목표 시급", GoalManager.getGoalHourly(this), 5000, 30000, 1000, "원/h")
        goalSettCard.addView(TextView(this).apply {
            text = "목표 저장"
            textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#5B6ABF"))
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                GoalManager.setGoalAmount(this@SettingsActivity, goalAmtBar.second())
                GoalManager.setGoalCount(this@SettingsActivity, goalCntBar.second())
                GoalManager.setGoalHourly(this@SettingsActivity, goalHrBar.second())
                Toast.makeText(this@SettingsActivity, "목표 저장됨", Toast.LENGTH_SHORT).show()
            }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(4), dp(16), dp(16)) })
        root.addView(goalSettCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // ─── v3.5: 백업/복원 ───
        root.addView(sectionTitle("백업/복원"))
        val backupCard = card()
        backupCard.addView(TextView(this).apply {
            text = "백업 (설정 + 즐겨찾기 + 통계)"
            textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#5B6ABF"))
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                val path = BackupManager.backup(this@SettingsActivity)
                if (path != null) Toast.makeText(this@SettingsActivity, "백업 완료: $path", Toast.LENGTH_LONG).show()
                else Toast.makeText(this@SettingsActivity, "백업 실패", Toast.LENGTH_SHORT).show()
            }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(12), dp(16), dp(4)) })
        backupCard.addView(TextView(this).apply {
            text = "복원 (JSON 파일에서)"
            textSize = 15f
            setTextColor(Color.parseColor("#5B6ABF")); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                Toast.makeText(this@SettingsActivity, "백업 파일을 앱 내부 저장소에 넣어주세요", Toast.LENGTH_LONG).show()
            }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(4), dp(16), dp(4)) })
        backupCard.addView(TextView(this).apply {
            text = "튜토리얼 다시보기"
            textSize = 14f
            setTextColor(Color.parseColor("#999999")); gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener {
                OnboardingActivity.reset(this@SettingsActivity)
                startActivity(Intent(this@SettingsActivity, OnboardingActivity::class.java))
            }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(4), dp(16), dp(12)) })
        root.addView(backupCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

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

        // ─── v3.20: 실험 기능 (Experimental flags, 최대 5개) ───
        FeatureFlags.load(this)
        root.addView(sectionTitle("실험 기능"))
        val expCard = card()
        expCard.addView(advancedToggle(
            "오버레이 표시",
            "콜 판정 시 화면 상단에 2초간 오버레이 표시",
            FeatureFlags.overlayEnabled
        ) { checked -> FeatureFlags.overlayEnabled = checked; FeatureFlags.save(this) })
        expCard.addView(advancedToggle(
            "수락 로그 수집",
            "수락 버튼 클릭 및 배달 시작 이벤트 자동 기록",
            FeatureFlags.acceptLoggerEnabled
        ) { checked -> FeatureFlags.acceptLoggerEnabled = checked; FeatureFlags.save(this) })
        expCard.addView(advancedToggle(
            "피드백 시스템",
            "콜 결과에 👍👎 피드백 기록 기능",
            FeatureFlags.feedbackSystemEnabled
        ) { checked -> FeatureFlags.feedbackSystemEnabled = checked; FeatureFlags.save(this) })
        expCard.addView(advancedToggle(
            "TTS 레벨 토글",
            "현재 작동하지 않음 (예비 슬롯)",
            FeatureFlags.ttsLevelToggle
        ) { checked -> FeatureFlags.ttsLevelToggle = checked; FeatureFlags.save(this) })
        expCard.addView(advancedToggle(
            "화면 필터 로그 수집",
            "배민 화면 감지 실패 시 텍스트 기록 (진단용)",
            FeatureFlags.screenFilterLogging
        ) { checked -> FeatureFlags.screenFilterLogging = checked; FeatureFlags.save(this) })
        expCard.addView(advancedToggle(
            "수락 시 피드백 받기 (테스트용)",
            "콜 수락 5초 후 \"왜 수락했는지\" 피드백 팝업 (기본 OFF)",
            FeatureFlags.showAcceptFeedback
        ) { checked -> FeatureFlags.showAcceptFeedback = checked; FeatureFlags.save(this) })
        root.addView(expCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // ─── v3.19.4: 개발자 도구 ───
        root.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        }, lp(MP, dp(1)).apply { setMargins(dp(16), dp(16), dp(16), 0) })
        root.addView(sectionTitle("개발자 도구"))
        val devToolCard = card()
        devToolCard.addView(TextView(this).apply {
            text = "쿠팡 진단 로그 보기"
            textSize = 15f; setTextColor(Color.parseColor("#5B6ABF"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, CoupangDiagnosticViewerActivity::class.java))
            }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(12), dp(16), dp(12)) })
        devToolCard.addView(TextView(this).apply {
            text = "쿠팡이츠 Flutter 접근성 트리 진단 데이터를 확인합니다"
            textSize = 12f; setTextColor(Color.parseColor("#999999"))
            setPadding(dp(16), 0, dp(16), dp(12))
        })

        // 배민 진단 로그 보기
        devToolCard.addView(TextView(this).apply {
            text = "배민 진단 로그 보기"
            textSize = 15f; setTextColor(Color.parseColor("#5B6ABF"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, CoupangDiagnosticViewerActivity::class.java).apply {
                    putExtra(CoupangDiagnosticViewerActivity.EXTRA_FILE_NAME, "baemin_diagnostic.jsonl")
                    putExtra(CoupangDiagnosticViewerActivity.EXTRA_TITLE, "배민 진단 로그")
                })
            }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(12), dp(16), dp(12)) })
        devToolCard.addView(TextView(this).apply {
            text = "배민 접근성 트리 진단 (거리 텍스트 탐색용, FeatureFlags ON 필요)"
            textSize = 12f; setTextColor(Color.parseColor("#999999"))
            setPadding(dp(16), 0, dp(16), dp(12))
        })

        // 복기 화면
        devToolCard.addView(TextView(this).apply {
            text = "복기"
            textSize = 15f; setTextColor(Color.parseColor("#5B6ABF"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, ReviewActivity::class.java))
            }
        }, lp(MP, WC).apply { setMargins(dp(16), dp(12), dp(16), dp(12)) })
        devToolCard.addView(TextView(this).apply {
            text = "오늘 핵심 콜 복기 (잡았어요/안잡았어요)"
            textSize = 12f; setTextColor(Color.parseColor("#999999"))
            setPadding(dp(16), 0, dp(16), dp(12))
        })

        root.addView(devToolCard, lp(MP, WC).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        // ─── 버전 번호 (5회 탭 → 개발자 옵션) ───
        var devTapCount = 0
        var lastDevTapTime = 0L
        root.addView(TextView(this).apply {
            text = "OnTheWay v${BuildConfig.VERSION_NAME}"
            textSize = 12f; setTextColor(Color.parseColor("#BBBBBB"))
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(20))
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastDevTapTime > 3000) devTapCount = 0
                lastDevTapTime = now
                devTapCount++
                if (devTapCount >= 5) {
                    devTapCount = 0
                    Toast.makeText(this@SettingsActivity, "개발자 옵션이 활성화되었습니다", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@SettingsActivity, DeveloperOptionsActivity::class.java))
                }
            }
        }, lp(MP, WC))

        scrollView.addView(root)
        setContentView(scrollView)

        // v3.22 Sprint 4: TTS 테스트 엔진 초기화
        testTts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                testTts?.language = Locale.KOREAN
                testTtsReady = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshGpsStatus()
    }

    private fun refreshGpsStatus() {
        try {
            val mode = DrivingModeManager.getMode(this)
            gpsModeTv?.text = if (mode == DrivingMode.DRIVING) "DRIVING" else "IDLE"

            val traces = CallLogDb.get(this).getRecentTraces(1)
            if (traces.isNotEmpty()) {
                val t = traces[0]
                val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(t.ts))
                gpsLastLocTv?.text = String.format("%.5f, %.5f (%s)", t.lat, t.lng, ts)
            } else {
                gpsLastLocTv?.text = "(없음)"
            }

            gpsCountTv?.text = "${CallLogDb.get(this).getTraceCount()}건"

            val ms = DrivingModeManager.getTodayDrivingTimeMs(this)
            val h = ms / 3_600_000; val m = (ms % 3_600_000) / 60_000; val s = (ms % 60_000) / 1000
            gpsDurationTv?.text = "${h}시간 ${m}분 ${s}초"

            val interval = LocationTracker.getCurrentIntervalMs()
            gpsIntervalTv?.text = when (interval) {
                5000L -> "5초 (주행)"; 10000L -> "10초 (저속)"; 30000L -> "30초 (정지)"
                else -> "${interval}ms"
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        testTts?.stop()
        testTts?.shutdown()
        testTts = null
        super.onDestroy()
    }

    private fun playPresetDemo(preset: TtsPreset) {
        if (!testTtsReady) {
            Toast.makeText(this, "TTS 준비 중. 잠시 후 다시 시도하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        testTts?.speak("추천, 단가 1,800원", TextToSpeech.QUEUE_FLUSH, null, "demo_1")
        if (preset.ordinal >= TtsPreset.MEDIUM.ordinal) {
            h.postDelayed({
                testTts?.speak("문 앞 초인종", TextToSpeech.QUEUE_ADD, null, "demo_2")
            }, 3000)
        }
        if (preset.ordinal >= TtsPreset.HIGH.ordinal) {
            h.postDelayed({
                testTts?.speak("다음 A동", TextToSpeech.QUEUE_ADD, null, "demo_3")
            }, 6000)
        }
        val label = when (preset) {
            TtsPreset.LOW -> "콜 판단만"
            TtsPreset.MEDIUM -> "판단 + 고객 요청"
            TtsPreset.HIGH -> "판단 + 요청 + 순서"
            TtsPreset.ULTRA -> "전체 안내"
        }
        Toast.makeText(this, "$label 미리듣기", Toast.LENGTH_SHORT).show()
    }

    private fun playTtsTest() {
        if (!testTtsReady) {
            Toast.makeText(this, "TTS 준비 중. 잠시 후 다시 시도하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val msg = ttsTestMessages.random()
        testTts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "tts_test_${System.currentTimeMillis()}")
        Toast.makeText(this, "\"$msg\"", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, "저장됨: $path", Toast.LENGTH_LONG).show()
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
