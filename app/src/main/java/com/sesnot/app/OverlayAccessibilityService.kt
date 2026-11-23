package com.sesnot.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

class OverlayAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var tv: TextView? = null
    private var wm: WindowManager? = null

    private val hide = Runnable {
        tv?.animate()?.alpha(0f)?.setDuration(400)?.withEndAction { tv?.visibility = android.view.View.GONE }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager
        val density = resources.displayMetrics.density
        val view = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            val pad = (14 * density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            background = GradientDrawable().apply {
                setColor(Color.argb(210, 20, 20, 20))
                cornerRadius = 18 * density
            }
            visibility = android.view.View.GONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (72 * density).toInt()
            width = (resources.displayMetrics.widthPixels * 0.9).toInt()
        }
        manager.addView(view, lp)
        tv = view
        Bus.overlay = { msg -> show(msg) }
    }

    private fun show(msg: String) {
        val v = tv ?: return
        handler.removeCallbacks(hide)
        v.animate().cancel()
        v.text = msg
        v.alpha = 1f
        v.visibility = android.view.View.VISIBLE
        handler.postDelayed(hide, 2500)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        Bus.overlay = null
        handler.removeCallbacksAndMessages(null)
        tv?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        tv = null
        super.onDestroy()
    }
}
