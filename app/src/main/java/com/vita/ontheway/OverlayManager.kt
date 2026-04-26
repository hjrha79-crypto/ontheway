package com.vita.ontheway

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * v1: 콜 판정 오버레이 — [수락]/[거절] + 거절 시 👍👎 + 사유
 */
object OverlayManager {

    private const val OVERLAY_VISIBLE_MS = 12000L   // 12초 완전 불투명
    private const val OVERLAY_FADE_MS = 8000L      // 8초 페이드
    const val OVERLAY_TOTAL_MS = 20000L             // 총 20초

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }

    private const val COLOR_ACCEPT = "#2ECC71"
    private const val COLOR_REJECT = "#E74C3C"
    private const val COLOR_DEFAULT = "#95A5A6"

    // step2 상태
    private var selectedFeedback: String? = null
    private val selectedReasons = mutableSetOf<String>()

    fun show(context: Context, verdict: String, line1: String, line2: String?) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w("OverlayManager", "오버레이 권한 없음 - 스킵")
            return
        }

        try {
            hide()

            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView = LayoutInflater.from(context).inflate(R.layout.overlay_on_the_way, null)
            val view = overlayView!!

            val line1View = view.findViewById<TextView>(R.id.overlay_line1)
            val line2View = view.findViewById<TextView>(R.id.overlay_line2)

            line1View.text = line1
            if (line2.isNullOrBlank()) {
                line2View.visibility = View.GONE
            } else {
                line2View.text = line2
                line2View.visibility = View.VISIBLE
            }

            // 판정별 배경 색상
            val colorHex = when {
                verdict.contains("잡으세요") || verdict == "ACCEPT" -> COLOR_ACCEPT
                verdict.contains("넘기세요") || verdict == "REJECT" -> COLOR_REJECT
                else -> COLOR_DEFAULT
            }
            val bg = view.background
            if (bg is GradientDrawable) {
                bg.setColor(Color.parseColor(colorHex).let { color ->
                    Color.argb(0xCC, Color.red(color), Color.green(color), Color.blue(color))
                })
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 150
            }

            // Step 1: 수락/거절
            val step1 = view.findViewById<View>(R.id.overlay_step1)
            val step2 = view.findViewById<View>(R.id.overlay_step2)

            view.findViewById<View>(R.id.overlay_accept).setOnClickListener {
                updateDriverAction(context, "simulated_accept")
                Toast.makeText(context, "수락 기록", Toast.LENGTH_SHORT).show()
                hide()
            }

            view.findViewById<View>(R.id.overlay_reject).setOnClickListener {
                updateDriverAction(context, "simulated_reject")
                step1.visibility = View.GONE
                step2.visibility = View.VISIBLE
                handler.removeCallbacks(hideRunnable) // 사용자 입력 시간 확보

                // 페이드 진행 중이면 취소 + 알파 복원
                overlayView?.let {
                    it.animate().cancel()
                    it.alpha = 1.0f
                }
            }

            // Step 2: 👍👎
            val thumbsUp = view.findViewById<View>(R.id.overlay_thumbs_up)
            val thumbsDown = view.findViewById<View>(R.id.overlay_thumbs_down)

            thumbsUp.setOnClickListener {
                selectedFeedback = "up"
                thumbsUp.alpha = 1.0f
                thumbsDown.alpha = 0.5f
            }
            thumbsDown.setOnClickListener {
                selectedFeedback = "down"
                thumbsDown.alpha = 1.0f
                thumbsUp.alpha = 0.5f
            }

            // Step 2: 사유 토글 (5개, 영문 코드, 배타 로직)
            val reasonPickup = view.findViewById<View>(R.id.overlay_reason_pickup)
            val reasonDelivery = view.findViewById<View>(R.id.overlay_reason_delivery)
            val reasonPrice = view.findViewById<View>(R.id.overlay_reason_price)
            val reasonOther = view.findViewById<View>(R.id.overlay_reason_other)
            val reasonVerdict = view.findViewById<View>(R.id.overlay_reason_verdict)
            val detailButtons = listOf(reasonPickup, reasonDelivery, reasonPrice, reasonOther)

            setupDetailReasonToggle(reasonPickup, "pickup", reasonVerdict)
            setupDetailReasonToggle(reasonDelivery, "delivery", reasonVerdict)
            setupDetailReasonToggle(reasonPrice, "price", reasonVerdict)
            setupDetailReasonToggle(reasonOther, "other", reasonVerdict)
            setupVerdictReasonToggle(reasonVerdict, detailButtons)

            // Step 2: 저장
            view.findViewById<View>(R.id.overlay_save).setOnClickListener {
                saveRejectFeedback(context)
            }

            windowManager?.addView(overlayView, params)

            handler.removeCallbacks(hideRunnable)
            // 12초 후 페이드 시작
            handler.postDelayed({
                overlayView?.let { startFadeOut(it) }
            }, OVERLAY_VISIBLE_MS)
            // 20초 후 완전 hide
            handler.postDelayed(hideRunnable, OVERLAY_TOTAL_MS)

            Log.d("OverlayManager", "오버레이 표시: $line1 / $line2 ($verdict)")
        } catch (e: Exception) {
            Log.w("OverlayManager", "오버레이 표시 실패: ${e.message}")
        }
    }

    /** 세부 사유 토글 — 선택 시 verdict 자동 해제 */
    private fun setupDetailReasonToggle(button: View, code: String, verdictButton: View) {
        button.alpha = 0.5f
        button.setOnClickListener {
            if (selectedReasons.contains(code)) {
                selectedReasons.remove(code)
                button.alpha = 0.5f
            } else {
                if (selectedReasons.contains("verdict")) {
                    selectedReasons.remove("verdict")
                    verdictButton.alpha = 0.5f
                }
                selectedReasons.add(code)
                button.alpha = 1.0f
            }
        }
    }

    /** verdict 토글 — 선택 시 세부 4개 자동 해제 */
    private fun setupVerdictReasonToggle(button: View, detailButtons: List<View>) {
        button.alpha = 0.5f
        button.setOnClickListener {
            if (selectedReasons.contains("verdict")) {
                selectedReasons.remove("verdict")
                button.alpha = 0.5f
            } else {
                listOf("pickup", "delivery", "price", "other").forEach { selectedReasons.remove(it) }
                detailButtons.forEach { it.alpha = 0.5f }
                selectedReasons.add("verdict")
                button.alpha = 1.0f
            }
        }
    }

    private fun updateDriverAction(context: Context, action: String) {
        val service = OnTheWayService.instance ?: return
        val call = service.lastDeliveryCall ?: return

        try {
            CallLogDb.get(context).updateDriverAction(call.price, call.platform, action)
            Log.d("OverlayV1", "driver_action 덮어쓰기: ${call.platform} ${call.price}원 → $action")
        } catch (e: Exception) {
            Log.e("OverlayV1", "driver_action 덮어쓰기 실패", e)
        }
    }

    private fun saveRejectFeedback(context: Context) {
        val service = OnTheWayService.instance
        val call = service?.lastDeliveryCall
        val verdict = service?.lastDeliveryVerdict ?: ""
        val reason = service?.lastDeliveryReason ?: ""
        val sessionId = service?.lastDeliverySessionId ?: ""

        if (call == null) {
            hide()
            return
        }

        val feedback = selectedFeedback ?: "down"
        val reasonsList = selectedReasons.toList()

        try {
            FeedbackLogger.log(
                context,
                platform = call.platform,
                store = call.storeName,
                price = call.price,
                distanceKm = call.distance ?: 0.0,
                verdict = verdict,
                reason = reason,
                sessionId = sessionId,
                feedback = feedback,
                reasons = reasonsList,
                driverAction = "simulated_reject"
            )
            Toast.makeText(context, "거절 기록", Toast.LENGTH_SHORT).show()
            Log.d("OverlayV1", "거절 저장: feedback=$feedback reasons=$reasonsList")
        } catch (e: Exception) {
            Log.e("OverlayV1", "저장 실패", e)
        }

        hide()
    }

    private fun startFadeOut(view: View) {
        view.animate()
            .alpha(0.4f)
            .setDuration(OVERLAY_FADE_MS)
            .start()
    }

    fun hide() {
        try {
            handler.removeCallbacks(hideRunnable)
            overlayView?.let {
                it.animate().cancel()  // 페이드 애니메이션 취소
                it.alpha = 1.0f         // 다음 표시 위해 초기화
            }
            selectedFeedback = null
            selectedReasons.clear()
            if (overlayView != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (e: Exception) {
            // 이미 제거된 뷰 무시
        }
    }
}
