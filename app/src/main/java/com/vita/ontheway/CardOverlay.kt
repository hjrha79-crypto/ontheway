package com.vita.ontheway

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 카드형 오버레이 (MINI v1.2).
 *
 * 깜빡임 방지 설계:
 * - isShowing 플래그: show 중 동일 메시지 재요청 → 무시
 * - 다른 메시지 재요청 → 기존 즉시 제거 후 교체
 * - 모든 상태 변경은 handler.post (메인스레드 직렬화)
 * - animateOut 중 show() → cancel + removeView + 새 카드 생성
 */
object CardOverlay {

    private const val TAG = "CardOverlay"
    private const val DISMISS_MS = 1500L
    private const val ANIM_IN_MS = 200L
    private const val ANIM_OUT_MS = 200L

    val COLOR_GREEN = Color.parseColor("#00F5A0")
    val COLOR_WHITE = Color.WHITE
    val COLOR_RED = Color.parseColor("#FF4D6D")
    private val BG_COLOR = Color.parseColor("#CC000000")

    private var windowManager: WindowManager? = null
    private var cardView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private var currentAnimator: Animator? = null
    @Volatile private var isShowing = false
    private var currentText: String? = null

    fun colorForUnitPrice(pricePerKm: Int?): Int {
        if (pricePerKm == null) return COLOR_WHITE
        return when {
            pricePerKm >= 1700 -> COLOR_GREEN
            pricePerKm >= 1400 -> COLOR_WHITE
            else -> COLOR_RED
        }
    }

    fun show(ctx: Context, text: String, textColor: Int = COLOR_WHITE) {
        if (!FeatureFlags.overlayEnabled) return
        val appCtx = ctx.applicationContext
        if (!Settings.canDrawOverlays(appCtx)) {
            Log.w(TAG, "오버레이 권한 없음")
            return
        }
        handler.post { showInternal(appCtx, text, textColor) }
    }

    private fun showInternal(ctx: Context, text: String, textColor: Int) {
        // 동일 메시지가 이미 표시 중 → 무시 (깜빡임 방지 핵심)
        if (isShowing && text == currentText) {
            Log.d(TAG, "동일 메시지 표시 중 → 스킵: $text")
            return
        }

        try {
            // 기존 카드 즉시 정리 (애니메이션 포함)
            teardown()

            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = ctx.resources.displayMetrics.density
            val screenWidth = ctx.resources.displayMetrics.widthPixels

            val bg = GradientDrawable().apply {
                setColor(BG_COLOR)
                cornerRadius = 16 * density
            }

            cardView = TextView(ctx).apply {
                this.text = text
                textSize = 30f
                setTextColor(textColor)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = bg
                setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            }

            val cardWidth = (screenWidth * 0.65).toInt()
            val params = WindowManager.LayoutParams(
                cardWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (48 * density).toInt()
            }

            windowManager?.addView(cardView, params)
            isShowing = true
            currentText = text

            // 등장 애니메이션: Fade + Scale 0.95→1.0
            cardView?.let { v ->
                v.alpha = 0f
                v.scaleX = 0.95f
                v.scaleY = 0.95f
                val animSet = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(v, "alpha", 0f, 1f),
                        ObjectAnimator.ofFloat(v, "scaleX", 0.95f, 1f),
                        ObjectAnimator.ofFloat(v, "scaleY", 0.95f, 1f)
                    )
                    duration = ANIM_IN_MS
                }
                currentAnimator = animSet
                animSet.start()
            }

            // 1.5초 후 fade out + 제거
            dismissRunnable = Runnable { animateOut() }
            handler.postDelayed(dismissRunnable!!, DISMISS_MS)

            OtwFileLogger.log(TAG, "표시: $text")
        } catch (e: Exception) {
            Log.w(TAG, "표시 실패: ${e.message}")
            isShowing = false
            currentText = null
        }
    }

    private fun animateOut() {
        val v = cardView ?: run {
            isShowing = false
            currentText = null
            return
        }
        try {
            val fadeOut = ObjectAnimator.ofFloat(v, "alpha", 1f, 0f).apply {
                duration = ANIM_OUT_MS
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        removeViewSafe()
                        isShowing = false
                        currentText = null
                    }
                    override fun onAnimationCancel(animation: Animator) {
                        // cancel 시에도 정리 (showInternal의 teardown에서 호출)
                        removeViewSafe()
                    }
                })
            }
            currentAnimator = fadeOut
            fadeOut.start()
        } catch (_: Exception) {
            removeViewSafe()
            isShowing = false
            currentText = null
        }
    }

    fun hide() {
        handler.post { teardown(); isShowing = false; currentText = null }
    }

    /** 애니메이션 + dismiss 예약 + 뷰 일괄 정리 */
    private fun teardown() {
        currentAnimator?.removeAllListeners()
        currentAnimator?.cancel()
        currentAnimator = null
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        removeViewSafe()
    }

    private fun removeViewSafe() {
        try {
            cardView?.let { windowManager?.removeViewImmediate(it) }
        } catch (_: Exception) {}
        cardView = null
    }
}
