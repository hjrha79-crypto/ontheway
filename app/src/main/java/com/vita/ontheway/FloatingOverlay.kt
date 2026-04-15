package com.vita.ontheway

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.TextView

/** v3.5 플로팅 오버레이 버블 */
object FloatingOverlay {

    private var windowManager: WindowManager? = null
    private var bubbleView: TextView? = null
    private var isShowing = false

    fun isEnabled(ctx: Context) =
        ctx.getSharedPreferences("advanced_prefs", Context.MODE_PRIVATE).getBoolean("floating_overlay", false)
    fun setEnabled(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences("advanced_prefs", Context.MODE_PRIVATE).edit().putBoolean("floating_overlay", v).apply()

    fun show(ctx: Context, text: String) {
        if (!isEnabled(ctx)) return
        if (!Settings.canDrawOverlays(ctx)) {
            Log.w("FloatingOverlay", "오버레이 권한 없음")
            return
        }

        if (isShowing) {
            update(text)
            return
        }

        try {
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            bubbleView = TextView(ctx).apply {
                this.text = text
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#CC5B6ABF"))
                setPadding(24, 16, 24, 16)
                gravity = Gravity.CENTER
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
                gravity = Gravity.TOP or Gravity.END
                x = 16
                y = 200
            }

            // 드래그 + 탭
            bubbleView?.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0; private var initialY = 0
                private var touchX = 0f; private var touchY = 0f
                private var moved = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x; initialY = params.y
                            touchX = event.rawX; touchY = event.rawY
                            moved = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (touchX - event.rawX).toInt()
                            val dy = (event.rawY - touchY).toInt()
                            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager?.updateViewLayout(bubbleView, params)
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!moved) {
                                // 탭 → 앱 실행
                                val intent = Intent(ctx, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                                ctx.startActivity(intent)
                            }
                        }
                    }
                    return true
                }
            })

            windowManager?.addView(bubbleView, params)
            isShowing = true
            Log.d("FloatingOverlay", "버블 표시: $text")
        } catch (e: Exception) {
            Log.w("FloatingOverlay", "버블 표시 실패: ${e.message}")
        }
    }

    fun update(text: String) {
        bubbleView?.text = text
    }

    fun hide() {
        try {
            if (isShowing && bubbleView != null) {
                windowManager?.removeView(bubbleView)
                bubbleView = null
                isShowing = false
            }
        } catch (e: Exception) { /* ignore */ }
    }
}
