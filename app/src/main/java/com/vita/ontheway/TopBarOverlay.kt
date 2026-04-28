package com.vita.ontheway

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
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
 * 화면 상단 전폭 얇은 바 오버레이.
 * - 높이 48dp, 반투명 다크 배경
 * - 1.5초 자동 dismiss
 * - 터치 불가 (FLAG_NOT_TOUCHABLE)
 * - 동시 2개 금지 (새 콜 시 기존 바 즉시 교체)
 */
object TopBarOverlay {

    private const val TAG = "TopBarOverlay"
    private const val DISMISS_MS = 1500L

    private var windowManager: WindowManager? = null
    private var barView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    fun show(ctx: Context, text: String) {
        if (!FeatureFlags.overlayEnabled) return
        if (!Settings.canDrawOverlays(ctx)) {
            Log.w(TAG, "오버레이 권한 없음")
            return
        }

        handler.post { showInternal(ctx, text) }
    }

    private fun showInternal(ctx: Context, text: String) {
        try {
            // 기존 바 즉시 제거 (교체)
            removeInternal()

            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = ctx.resources.displayMetrics.density

            barView = TextView(ctx).apply {
                this.text = text
                textSize = 16f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#CC000000"))
                gravity = Gravity.CENTER
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                (48 * density).toInt(),
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
                y = 0
            }

            windowManager?.addView(barView, params)

            // 1.5초 후 자동 제거
            dismissRunnable = Runnable { removeInternal() }
            handler.postDelayed(dismissRunnable!!, DISMISS_MS)

            OtwFileLogger.log(TAG, "표시: $text")
        } catch (e: Exception) {
            Log.w(TAG, "표시 실패: ${e.message}")
        }
    }

    fun hide() {
        handler.post { removeInternal() }
    }

    private fun removeInternal() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        try {
            barView?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {}
        barView = null
    }
}
