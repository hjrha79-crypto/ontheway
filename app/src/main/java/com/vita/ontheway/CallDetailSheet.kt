package com.vita.ontheway

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.WindowManager
import android.widget.*

// ──────────────────────────────────────────
// CallDetailSheet
// 콜 수락 전 상세 정보 바텀시트
// 카카오 픽커 상세 화면 수준 + AI 추천 이유
// ──────────────────────────────────────────
// 사용법:
// CallDetailSheet.show(context, callDetail) {
//     // 이 콜 실행 클릭 시
// }
// ──────────────────────────────────────────

data class CallDetail(
    // 기본 정보
    val callId: String,
    val rank: Int,
    val score: Int,
    val isUrgent: Boolean = false,

    // 픽업
    val pickupAddress: String,       // 서울 서초구 양재2동
    val pickupDetail: String = "",   // 신아빌딩
    val pickupDistanceKm: Double,    // 19.8

    // 배송
    val dropoffAddress: String,
    val dropoffDetail: String = "",
    val dropoffDistanceKm: Double,
    val deliveryDeadline: String = "", // 12:42까지 배송

    // 물품
    val itemSize: String = "",       // 초소형 / 소형 / 중형
    val itemInfo: String = "",       // 세 변의 합 70cm · 2kg 이하
    val notice: String = "",         // 유의사항

    // 수익
    val price: Int,

    // 예약
    val isReservation: Boolean = false,
    val reservationTime: String = "", // 내일 09:00 / 3/27(금) 14:30

    // 차량
    val vehicleType: String = "",    // 승용차 / 오토바이
    val callType: String = "",       // 퀵 / 도보 / 한차

    // AI 추천 정보
    val aiReason: String = "",       // 방향 일치 · 금액 높음
    val riskWarning: String = "",    // 복귀 어려움 ⚠
    val nextHint: String = ""        // 이후 콜 연결 가능성 높음
)

object CallDetailSheet {

    private val C_BG      = VitaColors.BG
    private val C_SURFACE = VitaColors.SURFACE
    private val C_BORDER  = VitaColors.BORDER
    private val C_ACCENT  = VitaColors.ACCENT2
    private val C_TEXT    = VitaColors.TEXT2
    private val C_MUTED   = VitaColors.MUTED
    private val C_URGENT  = VitaColors.URGENT
    private val C_WARN    = VitaColors.WARN

    fun show(
        context: Context,
        detail: CallDetail,
        devMode: Boolean = false,
        onExecute: () -> Unit,
        onSkip: () -> Unit
    ) {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            // 화면 90% 차지
            val dm = context.resources.displayMetrics
            setLayout((dm.widthPixels * 0.95).toInt(), (dm.heightPixels * 0.90).toInt())
            setGravity(Gravity.CENTER)
        }

        val dp = context.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val root = ScrollView(context).apply {
            setBackgroundColor(Color.parseColor("#000000"))
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 24.dp(), 20.dp(), 28.dp())
        }

        // ── 상단 닫기 버튼 ────────────────────
        container.addView(LinearLayout(context).apply {
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dp() }
            addView(TextView(context).apply {
                text = "✕"; textSize = 18f; setTextColor(Color.parseColor("#888888"))
                setPadding(12.dp(), 4.dp(), 4.dp(), 4.dp())
                setOnClickListener { dialog.dismiss() }
            })
        })

        // ── 헤더 (배지 + 금액) ──────────────
        container.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp() }

            addView(TextView(context).apply {
                text = when {
                    detail.isUrgent -> "⚡ 급송"
                    detail.aiReason.contains("잡으세요") -> "● 잡으세요"
                    detail.aiReason.contains("좋습니다") -> "● 좋습니다"
                    detail.aiReason.contains("괜찮습니다") -> "● 괜찮습니다"
                    detail.aiReason.contains("넘기세요") -> "⚠ 넘기세요"
                    detail.rank == 1 -> "● 1위 추천"
                    else -> "${detail.rank}순위"
                }
                textSize = 11f
                setTextColor(if (detail.isUrgent) Color.parseColor("#FF4444") else Color.parseColor("#3399FF"))
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(TextView(context).apply {
                text = "${String.format("%,d", detail.price)}원"
                textSize = 52f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            })
        })

        // ── 예약 배너 (예약 콜일 때만) ──────
        if (detail.isReservation && detail.reservationTime.isNotBlank()) {
            container.addView(TextView(context).apply {
                text = "🕐 ${detail.reservationTime} 픽업예약"
                textSize = 12f
                setTextColor(Color.parseColor("#3399FF"))
                setBackgroundColor(Color.parseColor("#1A1A1A"))
                setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 12.dp() }
            })
        }

        // ── 픽업 / 배송 정보 ────────────────
        val pickupRows = mutableListOf<Pair<String,String>>()
        if (detail.pickupAddress.isNotBlank()) {
            val distStr = if (detail.pickupDistanceKm > 0) "${detail.pickupDistanceKm}km" else ""
            pickupRows.add(detail.pickupAddress to distStr)
        }
        if (detail.pickupDetail.isNotBlank()) pickupRows.add(detail.pickupDetail to "")
        if (pickupRows.isNotEmpty()) container.addView(makeSection(context, "픽업", pickupRows))

        // 배송 정보 있을 때만 표시
        val dropoffRows = mutableListOf<Pair<String,String>>()
        if (detail.dropoffAddress.isNotBlank()) {
            val distStr = if (detail.dropoffDistanceKm > 0) "${detail.dropoffDistanceKm}km" else ""
            dropoffRows.add(detail.dropoffAddress to distStr)
        }
        if (detail.dropoffDetail.isNotBlank()) dropoffRows.add(detail.dropoffDetail to "")
        if (detail.deliveryDeadline.isNotBlank()) dropoffRows.add("마감" to detail.deliveryDeadline)
        if (dropoffRows.isNotEmpty()) container.addView(makeSection(context, "배송", dropoffRows))

        // ── 물품 정보 ───────────────────────
        val itemStr = listOf(detail.itemSize, detail.itemInfo).filter { it.isNotBlank() }.joinToString("  ")
        if (itemStr.isNotBlank()) {
            container.addView(makeInfoRow(context, "물품", itemStr))
        }

        // ── 유의사항 ────────────────────────
        if (detail.notice.isNotBlank()) {
            container.addView(makeInfoRow(context, "유의사항", detail.notice, C_WARN))
        }

        // ── AI 추천 이유 ────────────────────
        if (detail.aiReason.isNotBlank()) {
            container.addView(TextView(context).apply {
                text = "✦ ${detail.aiReason}"
                textSize = 12f
                setTextColor(Color.parseColor("#3399FF"))
                setBackgroundColor(Color.parseColor("#1A1A1A"))
                setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 6.dp() }
            })
        }

        // ── 리스크 경고 ─────────────────────
        if (detail.riskWarning.isNotBlank()) {
            container.addView(TextView(context).apply {
                text = detail.riskWarning
                textSize = 11f
                setTextColor(C_WARN)
                setBackgroundColor(Color.parseColor("#1A1A1A"))
                setPadding(12.dp(), 7.dp(), 12.dp(), 7.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 6.dp() }
            })
        }

        // ── 다음 콜 힌트 ────────────────────
        if (detail.nextHint.isNotBlank()) {
            container.addView(TextView(context).apply {
                text = detail.nextHint
                textSize = 11f
                setTextColor(Color.parseColor("#888888"))
                setBackgroundColor(Color.parseColor("#1A1A1A"))
                setPadding(12.dp(), 7.dp(), 12.dp(), 7.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 12.dp() }
            })
        }

        // ── Dev Mode 정보 ───────────────────
        if (devMode) {
            container.addView(TextView(context).apply {
                text = "score: ${detail.score}  |  rank: ${detail.rank}  |  urgent: ${detail.isUrgent}"
                textSize = 10f
                setTextColor(Color.parseColor("#888888"))
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, 0, 12.dp())
            })
        }

        // ── 카카오맵으로 보기 ────────────────
        container.addView(TextView(context).apply {
            text = "🗺 카카오맵에서 경로 보기"
            textSize = 12f
            setTextColor(Color.parseColor("#3399FF"))
            gravity = Gravity.CENTER
            setPadding(0, 8.dp(), 0, 16.dp())
            setOnClickListener {
                try {
                    val uri = Uri.parse("kakaomap://look?p=${Uri.encode(detail.dropoffAddress)}")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                } catch (e: Exception) {
                    val uri = Uri.parse("geo:0,0?q=${Uri.encode(detail.dropoffAddress)}")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }
            }
        })

        // ── 버튼 ────────────────────────────
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnRow.addView(TextView(context).apply {
            text = "건너뛰기"
            textSize = 15f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, 70.dp(), 1f).apply {
                marginEnd = 8.dp()
            }
            setOnClickListener { dialog.dismiss(); onSkip() }
        })

        btnRow.addView(TextView(context).apply {
            text = if (detail.isUrgent) "⚡ 이 콜 실행" else "이 콜 실행"
            textSize = 18f
            setTextColor(Color.parseColor("#000000"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(if (detail.isUrgent) Color.parseColor("#FF4444") else Color.parseColor("#3399FF"))
            layoutParams = LinearLayout.LayoutParams(0, 70.dp(), 2f)
            setOnClickListener { dialog.dismiss(); onExecute() }
        })

        container.addView(btnRow)
        root.addView(container)
        dialog.setContentView(root)
        dialog.show()
    }

    // ── 섹션 (픽업/배송) ────────────────────
    private fun makeSection(
        context: Context,
        label: String,
        rows: List<Pair<String, String>>
    ): LinearLayout {
        val dp = context.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp() }

            addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#888888"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 6.dp() }
            })

            rows.filter { it.first.isNotBlank() }.forEach { (left, right) ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(context).apply {
                        text = left
                        textSize = 22f
                        setTextColor(Color.parseColor("#FFFFFF"))
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    if (right.isNotBlank()) {
                        addView(TextView(context).apply {
                            text = right
                            textSize = 14f
                            setTextColor(Color.parseColor("#3399FF"))
                            setTypeface(null, Typeface.BOLD)
                        })
                    }
                })
            }
        }
    }

    private fun makeInfoRow(
        context: Context, label: String, value: String, color: Int = Color.parseColor("#8ca0b4")
    ): LinearLayout {
        val dp = context.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.dp() }

            addView(TextView(context).apply {
                text = label
                textSize = 11f
                setTextColor(Color.parseColor("#888888"))
                layoutParams = LinearLayout.LayoutParams(60.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(context).apply {
                text = value
                textSize = 12f
                setTextColor(color)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }
}
