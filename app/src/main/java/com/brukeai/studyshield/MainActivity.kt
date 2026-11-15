package com.brukeai.studyshield

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Calendar
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var web: WebView
         private val updateJsonUrl =
        "https://raw.githubusercontent.com/shreshthupadhyay12909-ux/study-shield/main/version.json"
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

        if (prefs().getLong("endAt", 0L) > System.currentTimeMillis()) {
            startShieldService()
        }
        checkGitHubUpdate()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.evaluateJavascript("window.onResumeApp && window.onResumeApp()", null)
    }

    private fun prefs() = getSharedPreferences("ss", Context.MODE_PRIVATE)

    private fun startShieldService() {
        val intent = Intent(this, ShieldService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun checkGitHubUpdate() {
        thread {
            try {
                val text = URL(updateJsonUrl).readText()
                val json = JSONObject(text)
                val latestCode = json.getInt("versionCode")
                val versionName = json.optString("versionName", "New Version")
                val apkUrl = json.getString("apkUrl")
                val notes = json.optString("notes", "Bug fixes and improvements.")

                val currentCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0).versionCode
                }

                if (latestCode > currentCode) {
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("Update Available ($versionName)")
                            .setMessage("What's New:\n$notes")
                            .setPositiveButton("Update") { _, _ ->
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)))
                            }
                            .setNegativeButton("Later", null)
                            .show()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun checkUsage(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun checkOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

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
            startShieldService()
        }

        @JavascriptInterface
        fun endSession() {
            prefs().edit().putLong("endAt", 0L).apply()
            stopService(Intent(this@MainActivity, ShieldService::class.java))
        }

        @JavascriptInterface
        fun getEndAt(): String = prefs().getLong("endAt", 0L).toString()

        @JavascriptInterface
        fun isServiceOn(): Boolean = checkUsage() && checkOverlay()

        @JavascriptInterface
        fun openAccessibilitySettings() {
            runOnUiThread {
                if (!checkUsage()) {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } else if (!checkOverlay() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                }
            }
        }

        @JavascriptInterface
        fun hasUsageAccess(): Boolean = checkUsage()

        @JavascriptInterface
        fun openUsageSettings() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }

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
