package com.quetta.tempmail

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val apiClient = QuettaApiClient()
    private var scheduler: ScheduledExecutorService? = null
    private val bgExecutor = Executors.newCachedThreadPool()
    private var wakeLock: PowerManager.WakeLock? = null
    private var notifIdCounter = 100

    companion object {
        private const val CHANNEL_ID = "quetta_mail_alerts"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupNotifications()
        acquireWakeLock()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(NativeBridge(), "AndroidBridge")
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        startBackgroundPoller()
    }

    private fun startBackgroundPoller() {
        scheduler = Executors.newSingleThreadScheduledExecutor()
        // Initial address rotation (deletes old addresses, creates new one)
        bgExecutor.execute {
            val (ok, newEmail, _) = apiClient.deleteOldAndCreateNew()
            if (ok) {
                copyToClipboardInternal(newEmail, silent = true)
            }
        }
        // Poll every 2 seconds
        scheduler?.scheduleWithFixedDelay({
            try {
                val newEmails = apiClient.pollOnce()
                for (em in newEmails) {
                    sendNewMailNotification(em.subject, em.from)
                }
            } catch (_: Exception) {}
        }, 2, 2, TimeUnit.SECONDS)
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun getState(): String {
            return apiClient.getStateJson()
        }

        @JavascriptInterface
        fun copyEmail(email: String) {
            copyToClipboardInternal(email, silent = false)
        }

        @JavascriptInterface
        fun refreshNow(): String {
            return try {
                val added = apiClient.pollOnce()
                for (em in added) {
                    sendNewMailNotification(em.subject, em.from)
                }
                JSONObject().apply {
                    put("ok", true)
                    put("added", added.size)
                }.toString()
            } catch (e: Exception) {
                JSONObject().apply {
                    put("ok", false)
                    put("error", e.message ?: "Refresh error")
                }.toString()
            }
        }

        @JavascriptInterface
        fun rotateNewMail(): String {
            val (ok, msg, deletedCount) = apiClient.deleteOldAndCreateNew()
            if (ok) {
                copyToClipboardInternal(msg, silent = true)
            }
            return JSONObject().apply {
                put("ok", ok)
                put("email", if (ok) msg else "")
                put("deletedCount", deletedCount)
                put("error", if (ok) JSONObject.NULL else msg)
            }.toString()
        }

        @JavascriptInterface
        fun exitAndCleanup() {
            bgExecutor.execute {
                apiClient.cleanupCurrent()
                runOnUiThread {
                    releaseWakeLock()
                    finishAndRemoveTask()
                }
            }
        }
    }

    private fun copyToClipboardInternal(text: String, silent: Boolean) {
        runOnUiThread {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Quetta Temp Email", text))
            if (!silent) {
                Toast.makeText(this, "Copied: $text", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "Incoming Temp Mail",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when new temporary emails arrive"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(chan)
        }
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

    private fun sendNewMailNotification(subject: String, sender: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("📧 ${subject.take(80)}")
            .setContentText("From: ${sender.take(120)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifIdCounter++, notif)
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuettaTempMail::InboxPollWakeLock")
            wakeLock?.acquire(60 * 60 * 1000L) // 1 hour max safety timeout
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        scheduler?.shutdownNow()
        Thread {
            apiClient.cleanupCurrent()
        }.start()
        releaseWakeLock()
        super.onDestroy()
    }
}
