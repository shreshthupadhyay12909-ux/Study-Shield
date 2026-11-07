package com.brukeai.studyshield

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class ShieldService : AccessibilityService() {
    private var overlay: View? = null
    private var timeView: TextView? = null
    private var nameView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            val left = prefs().getLong("endAt", 0L) - System.currentTimeMillis()
            if (left <= 0) {
                hide()
                return
            }
            timeView?.text = format(left)
            handler.postDelayed(this, 1000)
        }
    }

    private fun prefs() = getSharedPreferences("ss", Context.MODE_PRIVATE)

    private fun format(ms: Long): String {
        val s = if (ms < 0) 0L else ms / 1000
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        val p = prefs()
        val active = p.getLong("endAt", 0L) > System.currentTimeMillis()
        val blocked = p.getStringSet("blocked", emptySet()) ?: emptySet()
        if (active && blocked.contains(pkg)) show(pkg) else hide()
    }

    private fun show(pkg: String) {
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        if (overlay != null) {
            nameView?.text = label + " is blocked"
            return
        }
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        fun tv(t: String, size: Float, color: String, bold: Boolean): TextView {
            val v = TextView(this)
            v.text = t
            v.textSize = size
            v.setTextColor(Color.parseColor(color))
            v.gravity = Gravity.CENTER
            if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
            return v
        }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.parseColor("#dff1fb"))
        root.setPadding(dp(32), dp(32), dp(32), dp(32))

        root.addView(tv("STAY FOCUSED", 13f, "#1d6fb8", false))
        val name = tv(label + " is blocked", 26f, "#10243a", true)
        name.setPadding(0, dp(10), 0, dp(6))
        nameView = name
        root.addView(name)
        root.addView(tv("Your focus session is still running.", 15f, "#4a6178", false))
        val time = tv(format(prefs().getLong("endAt", 0L) - System.currentTimeMillis()), 56f, "#10243a", true)
        time.setPadding(0, dp(28), 0, dp(28))
        timeView = time
        root.addView(time)

        val btn = Button(this)
        btn.text = "Back to study"
        btn.setAllCaps(false)
        btn.textSize = 16f
        btn.setTextColor(Color.WHITE)
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#1d6fb8"))
        bg.cornerRadius = dp(14).toFloat()
        btn.background = bg
        btn.setOnClickListener {
            performGlobalAction(GLOBAL_ACTION_HOME)
            hide()
        }
        root.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(root, lp)
            overlay = root
            handler.post(ticker)
        } catch (e: Exception) {
        }
    }

    private fun hide() {
        handler.removeCallbacks(ticker)
        val v = overlay ?: return
        overlay = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
        }
    }

    override fun onInterrupt() {
        hide()
    }

    override fun onDestroy() {
        hide()
        super.onDestroy()
    }
}
