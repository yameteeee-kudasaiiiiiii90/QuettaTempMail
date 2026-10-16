package com.quetta.tempmail

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var mailService: QuettaMailService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as QuettaMailService.LocalBinder
            mailService = binder.getService()
            isBound = true
            mailService?.setUiListener { event, stateJson ->
                pushStateToWebView(event, stateJson)
            }
            pushStateToWebView("state", mailService?.apiClient?.getStateJson())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            mailService = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        return true
                    }
                    return false
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(NativeBridge(), "AndroidBridge")
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        val serviceIntent = Intent(this, QuettaMailService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun pushStateToWebView(event: String, stateJson: String?) {
        if (stateJson == null) return
        val escaped = JSONObject.quote(stateJson)
        webView.evaluateJavascript("window.onNativePush && window.onNativePush('$event', $escaped);", null)
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun getState(): String {
            return mailService?.apiClient?.getStateJson()
                ?: JSONObject().put("email", "Starting service...").put("status", "Connecting...").toString()
        }

        @JavascriptInterface
        fun copyText(text: String, label: String) {
            mailService?.copyToClipboard(text, "Copied $label: $text")
        }

        @JavascriptInterface
        fun refreshNow() {
            mailService?.triggerImmediateRefresh { added, err ->
                val script = if (err != null) {
                    "window.onRefreshComplete && window.onRefreshComplete(false, 0, ${JSONObject.quote(err)});"
                } else {
                    "window.onRefreshComplete && window.onRefreshComplete(true, $added, null);"
                }
                webView.evaluateJavascript(script, null)
            }
        }

        @JavascriptInterface
        fun rotateNewMail() {
            mailService?.triggerRotateNewMail { ok, emailOrErr, delCount ->
                val script = "window.onRotateComplete && window.onRotateComplete($ok, ${JSONObject.quote(emailOrErr)}, $delCount);"
                webView.evaluateJavascript(script, null)
            }
        }

        @JavascriptInterface
        fun deleteEmail(emailId: String) {
            mailService?.apiClient?.deleteSingleMessage(emailId)
        }

        @JavascriptInterface
        fun requestUnrestrictedBattery() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun exitAndCleanup() {
            mailService?.shutdownAndExit {
                finishAndRemoveTask()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView.evaluateJavascript(
            "(function(){ var s = document.getElementById('readerSheet'); if(s && s.classList.contains('open')){ closeReader(); return 'closed'; } return 'none'; })();"
        ) { res ->
            if (res == null || !res.contains("closed")) {
                super.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            mailService?.setUiListener(null)
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}
