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

/**
 * v3.20: 콜 판정 결과 오버레이 (2줄, 2초 자동 소멸)
 * - TTS와 동시 또는 +0.3초 지연으로 표시
 * - 판정별 색상: ACCEPT 초록, REJECT 빨강, 기타 회색
 */
object OverlayManager {

    /**
     * 오버레이 자동 닫힘 타이머 (ms).
     * 2026-04-24: 2초 → 8초 (사용자가 정보 읽기 + 👍👎 탭 가능하도록).
     * 향후 SettingsActivity에서 조정 가능하도록 상수로 분리.
     */
    const val OVERLAY_DURATION_MS = 8000L

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }

    private const val COLOR_ACCEPT = "#2ECC71"
    private const val COLOR_REJECT = "#E74C3C"
    private const val COLOR_DEFAULT = "#95A5A6"

    fun show(context: Context, verdict: String, line1: String, line2: String?) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w("OverlayManager", "오버레이 권한 없음 - 스킵")
            return
        }

        try {
            // 기존 뷰 제거
            hide()

            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            overlayView = LayoutInflater.from(context).inflate(R.layout.overlay_on_the_way, null)

            val line1View = overlayView!!.findViewById<TextView>(R.id.overlay_line1)
            val line2View = overlayView!!.findViewById<TextView>(R.id.overlay_line2)

            line1View.text = line1
            if (line2.isNullOrBlank()) {
                line2View.visibility = View.GONE
            } else {
                line2View.text = line2
                line2View.visibility = View.VISIBLE
            }

            // 판정별 배경 색상 적용
            val colorHex = when {
                verdict.contains("잡으세요") || verdict == "ACCEPT" -> COLOR_ACCEPT
                verdict.contains("넘기세요") || verdict == "REJECT" -> COLOR_REJECT
                else -> COLOR_DEFAULT
            }
            val bg = overlayView!!.background
            if (bg is GradientDrawable) {
                bg.setColor(Color.parseColor(colorHex).let { color ->
                    // 반투명 적용 (alpha CC = 80%)
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 150
            }

            windowManager?.addView(overlayView, params)

            // 2초 후 자동 제거
            handler.removeCallbacks(hideRunnable)
            handler.postDelayed(hideRunnable, OVERLAY_DURATION_MS)

            Log.d("OverlayManager", "오버레이 표시: $line1 / $line2 ($verdict)")
        } catch (e: Exception) {
            Log.w("OverlayManager", "오버레이 표시 실패: ${e.message}")
        }
    }

    fun hide() {
        try {
            handler.removeCallbacks(hideRunnable)
            if (overlayView != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (e: Exception) {
            // 이미 제거된 뷰 무시
        }
    }
}
