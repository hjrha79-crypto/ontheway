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
 * 카드형 오버레이 (MINI v1.1).
 * - 화면 상단 중앙, 너비 65%
 * - 반투명 블랙 배경, 모서리 16dp
 * - 단가 기반 3단계 색상
 * - Fade + Scale 애니메이션
 * - 1.5초 유지 후 fade out
 * - 항상 applicationContext 사용 (Activity leak 방지)
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
        // 항상 applicationContext 사용 (Activity/Service 무관)
        val appCtx = ctx.applicationContext
        if (!Settings.canDrawOverlays(appCtx)) {
            Log.w(TAG, "오버레이 권한 없음")
            return
        }
        handler.post { showInternal(appCtx, text, textColor) }
    }

    private fun showInternal(ctx: Context, text: String, textColor: Int) {
        try {
            // 진행 중 애니메이션 즉시 취소 + 기존 카드 제거 (번쩍임 방지)
            currentAnimator?.cancel()
            currentAnimator = null
            removeInternal()

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
        }
    }

    private fun animateOut() {
        val v = cardView ?: return
        try {
            val fadeOut = ObjectAnimator.ofFloat(v, "alpha", 1f, 0f).apply {
                duration = ANIM_OUT_MS
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        removeInternal()
                    }
                })
            }
            currentAnimator = fadeOut
            fadeOut.start()
        } catch (_: Exception) {
            removeInternal()
        }
    }

    fun hide() {
        handler.post {
            currentAnimator?.cancel()
            currentAnimator = null
            removeInternal()
        }
    }

    private fun removeInternal() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        try {
            cardView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        cardView = null
    }
}
