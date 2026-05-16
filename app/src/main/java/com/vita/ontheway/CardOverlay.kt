package com.vita.ontheway

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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.text.NumberFormat

/**
 * 카드형 오버레이 (MINI v2.0 — 싱글톤 뷰 방식).
 *
 * 핵심 설계: addView 1회, 이후 visibility + setText만 변경.
 * → removeView/addView 반복 제거 → 깜빡임 완전 해소.
 *
 * 라이프사이클:
 * 1. 첫 show() → addView (1회)
 * 2. 이후 show() → setText + alpha=1 + dismiss 타이머 리셋
 * 3. 1.5초 후 → alpha fade out → INVISIBLE
 * 4. 서비스 종료 시 hide() → removeView (1회)
 *
 * HUD (v0.1): 지속형 Context HUD.
 * - CALL_DETECTED 시 showHud() → 마지막 콜 정보 영구 표시
 * - alpha: 1.0 → 0.7 (5초) → 0.45 (15초, 영구)
 * - drag 이동 + 비율 위치 저장
 */
object CardOverlay {

    private const val TAG = "CardOverlay"
    private const val DISMISS_MS = 3000L
    private const val ANIM_OUT_MS = 200L

    val COLOR_GREEN = Color.parseColor("#00F5A0")
    val COLOR_WHITE = Color.WHITE
    val COLOR_RED = Color.parseColor("#FF4D6D")
    private val BG_COLOR = Color.parseColor("#CC000000")
    private val HUD_BG_COLOR = Color.parseColor("#B3000000")

    private var windowManager: WindowManager? = null
    private var textView: TextView? = null
    private var isAttached = false
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private var fadeAnimator: ObjectAnimator? = null
    private var currentText: String? = null

    // 마지막 콜 정보 (피드백 다이얼로그용)
    private var lastCallPlatform: String = ""
    private var lastCallPrice: Int = 0
    private var lastCallTs: Long = 0

    // ── HUD 상태 ──
    private var hudView: TextView? = null
    private var isHudAttached = false
    private var hudFadeRunnable1: Runnable? = null
    private var hudFadeRunnable2: Runnable? = null

    private const val HUD_PREFS = "hud_position"
    private const val KEY_X_RATIO = "hud_x_ratio"
    private const val KEY_Y_RATIO = "hud_y_ratio"

    internal const val HUD_ALPHA_FULL = 1.0f
    internal const val HUD_ALPHA_MID = 0.7f
    internal const val HUD_ALPHA_LOW = 0.45f
    internal const val HUD_FADE_1_MS = 5_000L
    internal const val HUD_FADE_2_MS = 15_000L

    private val nf = NumberFormat.getNumberInstance()

    /** 콜 정보 기록 (OutputController.emit 시 호출) */
    fun setLastCall(platform: String, price: Int) {
        lastCallPlatform = platform
        lastCallPrice = price
        lastCallTs = System.currentTimeMillis()
    }

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
        handler.post { showOnMain(appCtx, text, textColor) }
    }

    private fun showOnMain(ctx: Context, text: String, textColor: Int) {
        try {
            // 뷰가 아직 없으면 1회 생성 + addView
            if (!isAttached || textView == null) {
                createAndAttach(ctx)
            }

            val tv = textView ?: return

            // fade-out 애니메이션 진행 중이면 즉시 취소
            fadeAnimator?.cancel()
            fadeAnimator = null

            // dismiss 타이머 리셋
            dismissRunnable?.let { handler.removeCallbacks(it) }

            // 동일 메시지 + 이미 보이는 상태 → 타이머만 리셋 (깜빡임 방지)
            if (text == currentText && tv.visibility == View.VISIBLE && tv.alpha >= 1f) {
                dismissRunnable = Runnable { fadeOut() }
                handler.postDelayed(dismissRunnable!!, DISMISS_MS)
                return
            }

            // 텍스트/색상 업데이트 + 즉시 표시
            tv.text = text
            tv.setTextColor(textColor)
            tv.alpha = 1f
            tv.scaleX = 1f
            tv.scaleY = 1f
            tv.visibility = View.VISIBLE
            currentText = text

            // 1.5초 후 fade out
            dismissRunnable = Runnable { fadeOut() }
            handler.postDelayed(dismissRunnable!!, DISMISS_MS)

            OtwFileLogger.log(TAG, "표시: $text")
        } catch (e: Exception) {
            Log.w(TAG, "표시 실패: ${e.message}")
        }
    }

    private fun createAndAttach(ctx: Context) {
        // 잔존 뷰 정리
        detach()

        windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = ctx.resources.displayMetrics.density
        val screenWidth = ctx.resources.displayMetrics.widthPixels

        val bg = GradientDrawable().apply {
            setColor(BG_COLOR)
            cornerRadius = 16 * density
        }

        textView = TextView(ctx).apply {
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = bg
            val pad = (16 * density).toInt()
            val padV = (12 * density).toInt()
            setPadding(pad, padV, pad, padV)
            visibility = View.INVISIBLE
            setOnClickListener {
                // 카드 클릭 → OnTheWay 앱으로 이동 + 피드백
                dismissRunnable?.let { r -> handler.removeCallbacks(r) }
                fadeOut()
                if (lastCallPrice > 0) {
                    try {
                        val intent = android.content.Intent(ctx, MainActivity::class.java).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("open_feedback", true)
                            putExtra("fb_platform", lastCallPlatform)
                            putExtra("fb_price", lastCallPrice)
                            putExtra("fb_ts", lastCallTs)
                        }
                        ctx.startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
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
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (48 * density).toInt()
        }

        windowManager?.addView(textView, params)
        isAttached = true
        Log.d(TAG, "뷰 생성 + addView (1회)")
    }

    private fun fadeOut() {
        val tv = textView ?: return
        if (tv.visibility != View.VISIBLE) return
        try {
            fadeAnimator = ObjectAnimator.ofFloat(tv, "alpha", tv.alpha, 0f).apply {
                duration = ANIM_OUT_MS
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        tv.visibility = View.INVISIBLE
                        currentText = null
                        fadeAnimator = null
                    }
                })
                start()
            }
        } catch (_: Exception) {
            tv.visibility = View.INVISIBLE
            currentText = null
        }
    }

    // ══════════════════════════════════════════
    // HUD — Persistent Context HUD (v0.1)
    // ══════════════════════════════════════════

    /** CALL_DETECTED 시 호출 — 마지막 콜 정보를 HUD에 영구 표시 */
    fun showHud(ctx: Context, platform: String, price: Int, pickupKm: Double?) {
        if (!FeatureFlags.overlayEnabled) return
        val appCtx = ctx.applicationContext
        if (!Settings.canDrawOverlays(appCtx)) return
        handler.post { showHudOnMain(appCtx, platform, price, pickupKm) }
    }

    /** HUD 텍스트 생성 (테스트 가능) */
    internal fun buildHudText(platform: String, price: Int, pickupKm: Double?): String {
        val platLabel = if (platform == "coupang") "쿠팡" else "배민"
        val priceLabel = nf.format(price) + "원"
        val pickupLabel = if (pickupKm != null && pickupKm > 0) {
            "픽업 ${"%.1f".format(pickupKm)}km"
        } else "거리 미확인"
        return "$platLabel $priceLabel / $pickupLabel"
    }

    /** 비율 → 절대 px 변환 (테스트 가능) */
    internal fun ratioToPixel(ratio: Float, screenSize: Int): Int =
        (ratio * screenSize).toInt()

    /** 절대 px → 비율 변환 (테스트 가능) */
    internal fun pixelToRatio(pixel: Int, screenSize: Int): Float =
        if (screenSize > 0) pixel.toFloat() / screenSize else 0f

    private fun showHudOnMain(ctx: Context, platform: String, price: Int, pickupKm: Double?) {
        try {
            if (!isHudAttached || hudView == null) {
                createAndAttachHud(ctx)
            }

            val hv = hudView ?: return

            hv.text = buildHudText(platform, price, pickupKm)
            hv.alpha = HUD_ALPHA_FULL
            hv.visibility = View.VISIBLE

            // 기존 fade 타이머 취소
            hudFadeRunnable1?.let { handler.removeCallbacks(it) }
            hudFadeRunnable2?.let { handler.removeCallbacks(it) }

            // alpha fade 스케줄: 5초 → 0.7, 15초 → 0.45
            hudFadeRunnable1 = Runnable {
                hudView?.let { it.alpha = HUD_ALPHA_MID }
            }
            hudFadeRunnable2 = Runnable {
                hudView?.let { it.alpha = HUD_ALPHA_LOW }
            }
            handler.postDelayed(hudFadeRunnable1!!, HUD_FADE_1_MS)
            handler.postDelayed(hudFadeRunnable2!!, HUD_FADE_2_MS)

            OtwFileLogger.log(TAG, "HUD 표시: ${hv.text}")
        } catch (e: Exception) {
            Log.w(TAG, "HUD 표시 실패: ${e.message}")
        }
    }

    private fun createAndAttachHud(ctx: Context) {
        detachHud()

        if (windowManager == null) {
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        val wm = windowManager ?: return
        val density = ctx.resources.displayMetrics.density
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val screenHeight = ctx.resources.displayMetrics.heightPixels

        val bg = GradientDrawable().apply {
            setColor(HUD_BG_COLOR)
            cornerRadius = 12 * density
        }

        val prefs = ctx.getSharedPreferences(HUD_PREFS, Context.MODE_PRIVATE)
        val savedXRatio = prefs.getFloat(KEY_X_RATIO, 0.5f)
        val savedYRatio = prefs.getFloat(KEY_Y_RATIO, 0.05f)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ratioToPixel(savedXRatio, screenWidth)
            y = ratioToPixel(savedYRatio, screenHeight)
        }

        hudView = TextView(ctx).apply {
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            this.background = bg
            val padH = (12 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            visibility = View.INVISIBLE
        }

        val hv = hudView!!

        // Drag 처리
        var dragStartX = 0f
        var dragStartY = 0f
        var dragStartParamX = 0
        var dragStartParamY = 0
        var isDragging = false

        hv.setOnTouchListener { _, event ->
            val lp = hv.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    dragStartParamX = lp.x
                    dragStartParamY = lp.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY
                    if (!isDragging && (dx * dx + dy * dy) > 25) {
                        isDragging = true
                    }
                    if (isDragging) {
                        lp.x = dragStartParamX + dx.toInt()
                        lp.y = dragStartParamY + dy.toInt()
                        try { wm.updateViewLayout(hv, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        val sw = ctx.resources.displayMetrics.widthPixels
                        val sh = ctx.resources.displayMetrics.heightPixels
                        val xRatio = pixelToRatio(lp.x, sw).coerceIn(0f, 1f)
                        val yRatio = pixelToRatio(lp.y, sh).coerceIn(0f, 1f)
                        ctx.getSharedPreferences(HUD_PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putFloat(KEY_X_RATIO, xRatio)
                            .putFloat(KEY_Y_RATIO, yRatio)
                            .apply()
                        OtwFileLogger.log(TAG, "HUD 위치 저장: x=$xRatio, y=$yRatio")
                    }
                    true
                }
                else -> false
            }
        }

        wm.addView(hv, params)
        isHudAttached = true
        Log.d(TAG, "HUD 뷰 생성 + addView")
    }

    private fun detachHud() {
        hudFadeRunnable1?.let { handler.removeCallbacks(it) }
        hudFadeRunnable2?.let { handler.removeCallbacks(it) }
        hudFadeRunnable1 = null
        hudFadeRunnable2 = null
        try {
            hudView?.let { windowManager?.removeViewImmediate(it) }
        } catch (_: Exception) {}
        hudView = null
        isHudAttached = false
    }

    /** 서비스 종료 시 호출 — removeView (1회) */
    fun hide() {
        handler.post {
            fadeAnimator?.cancel()
            fadeAnimator = null
            dismissRunnable?.let { handler.removeCallbacks(it) }
            dismissRunnable = null
            currentText = null
            detach()
            detachHud()
        }
    }

    private fun detach() {
        try {
            textView?.let { windowManager?.removeViewImmediate(it) }
        } catch (_: Exception) {}
        textView = null
        isAttached = false
    }
}
