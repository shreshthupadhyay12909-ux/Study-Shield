package com.brukeai.studyshield

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0f1115")
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(Color.parseColor("#dff1fb"))
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/www/index.html")
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.evaluateJavascript("window.onResumeApp && window.onResumeApp()", null)
    }

    private fun prefs() = getSharedPreferences("ss", Context.MODE_PRIVATE)

    inner class Bridge {
        @JavascriptInterface
        fun getApps(): String {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val arr = JSONArray()
            pm.queryIntentActivities(intent, 0)
                .map { Pair(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .distinctBy { it.first }
                .filter { it.first != packageName }
                .sortedBy { it.second.lowercase() }
                .forEach { arr.put(JSONObject().put("pkg", it.first).put("name", it.second)) }
            return arr.toString()
        }

        @JavascriptInterface
        fun startSession(minutes: Int, blockedJson: String) {
            val set = HashSet<String>()
            val a = JSONArray(blockedJson)
            for (i in 0 until a.length()) set.add(a.getString(i))
            prefs().edit()
                .putStringSet("blocked", set)
                .putLong("endAt", System.currentTimeMillis() + minutes * 60000L)
                .apply()
        }

        @JavascriptInterface
        fun endSession() {
            prefs().edit().putLong("endAt", 0L).apply()
        }

        @JavascriptInterface
        fun getEndAt(): String = prefs().getLong("endAt", 0L).toString()

        @JavascriptInterface
        fun isServiceOn(): Boolean {
            val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return s != null && s.contains(packageName)
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        @JavascriptInterface
        fun hasUsageAccess(): Boolean {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }

        @JavascriptInterface
        fun openUsageSettings() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }

        // Screen time in milliseconds for each of the last 7 days (last value = today)
        @JavascriptInterface
        fun getUsage(): String {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.DAY_OF_YEAR, -6)
            val start = cal.timeInMillis
            val totals = LongArray(7)
            val events = usm.queryEvents(start, System.currentTimeMillis())
            val e = UsageEvents.Event()
            val resumed = HashMap<String, Long>()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val pkg = e.packageName ?: continue
                if (pkg == packageName) continue
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    resumed[pkg] = e.timeStamp
                } else if (e.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    val t0 = resumed.remove(pkg) ?: continue
                    val idx = ((e.timeStamp - start) / 86400000L).toInt()
                    if (idx in 0..6) totals[idx] += e.timeStamp - t0
                }
            }
            return JSONArray(totals.toList()).toString()
        }
    }
}
