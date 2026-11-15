package com.brukeai.studyshield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class ShieldService : Service() {
    private var overlay: View? = null
    private var timeView: TextView? = null
    private var nameView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false

    private val watcher = object : Runnable {
        override fun run() {
            if (!isMonitoring) return
            val p = prefs()
            val active = p.getLong("endAt", 0L) > System.currentTimeMillis()
            if (!active) {
                hide()
                stopSelf()
                return
            }
            val pkg = getForegroundApp()
            val blocked = p.getStringSet("blocked", emptySet()) ?: emptySet()
            if (pkg != null && pkg != packageName && pkg != "com.android.systemui" && blocked.contains(pkg)) {
                show(pkg)
            } else if (pkg != null && !blocked.contains(pkg)) {
                hide()
            }
            handler.postDelayed(this, 800)
        }
    }

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startNotification()
        if (!isMonitoring) {
            isMonitoring = true
            handler.post(watcher)
        }
        return START_STICKY
    }

    private fun getForegroundApp(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 5000, end)
        val e = UsageEvents.Event()
        var current: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                current = e.packageName
            }
        }
        return current
    }

    private fun startNotification() {
        val channelId = "ss_shield_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Study Shield", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notif = builder
            .setContentTitle("Study Shield")
            .setContentText("Focus session is active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        startForeground(1, notif)
    }

    private fun show(pkg: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return

        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        if (overlay != null) {
            nameView?.text = "$label is blocked"
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
        val name = tv("$label is blocked", 26f, "#10243a", true)
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
        btn.isAllCaps = false
        btn.textSize = 16f
        btn.setTextColor(Color.WHITE)
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#1d6fb8"))
        bg.cornerRadius = dp(14).toFloat()
        btn.background = bg
        btn.setOnClickListener {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(home)
            hide()
        }
        root.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(root, lp)
            overlay = root
            handler.post(ticker)
        } catch (_: Exception) {
        }
    }

    private fun hide() {
        handler.removeCallbacks(ticker)
        val v = overlay ?: return
        overlay = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        isMonitoring = false
        handler.removeCallbacks(watcher)
        hide()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
