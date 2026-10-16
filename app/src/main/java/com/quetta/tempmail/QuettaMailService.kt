package com.quetta.tempmail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.min

class QuettaMailService : Service() {

    companion object {
        const val CHANNEL_SERVICE = "quetta_fg_service"
        const val CHANNEL_ALERTS = "quetta_mail_alerts"
        const val NOTIF_ID_SERVICE = 1

        const val ACTION_COPY_EMAIL = "com.quetta.tempmail.ACTION_COPY_EMAIL"
        const val ACTION_COPY_OTP = "com.quetta.tempmail.ACTION_COPY_OTP"
        const val ACTION_REFRESH_NOW = "com.quetta.tempmail.ACTION_REFRESH_NOW"
        const val ACTION_ROTATE_NEW = "com.quetta.tempmail.ACTION_ROTATE_NEW"
        const val ACTION_STOP_SERVICE = "com.quetta.tempmail.ACTION_STOP_SERVICE"
        const val EXTRA_OTP = "extra_otp"
    }

    inner class LocalBinder : Binder() {
        fun getService(): QuettaMailService = this@QuettaMailService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newCachedThreadPool()
    private var scheduler: ScheduledExecutorService? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var stateListener: (() -> Unit)? = null
    private var alertNotifId = 200
    @Volatile private var isStopping = false

    val apiClient = QuettaApiClient {
        updateForegroundNotification()
        mainHandler.post { stateListener?.invoke() }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        acquireLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID_SERVICE,
                buildForegroundNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID_SERVICE, buildForegroundNotification())
        }
        registerNetworkCallback()
        startBackgroundLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_COPY_EMAIL -> {
                val em = apiClient.email
                if (!em.contains("...")) {
                    copyToClipboard(em, "Copied: $em")
                }
            }
            ACTION_COPY_OTP -> {
                val otp = intent.getStringExtra(EXTRA_OTP)
                if (!otp.isNullOrBlank()) {
                    copyToClipboard(otp, "Copied OTP: $otp")
                }
            }
            ACTION_REFRESH_NOW -> {
                triggerImmediateRefresh(null)
            }
            ACTION_ROTATE_NEW -> {
                bgExecutor.execute {
                    val (ok, newEmail, _) = apiClient.deleteOldAndCreateNew()
                    if (ok) {
                        copyToClipboard(newEmail, "Old deleted! Copied: $newEmail")
                    }
                }
            }
            ACTION_STOP_SERVICE -> {
                shutdownAndExit(null)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setStateListener(listener: (() -> Unit)?) {
        stateListener = listener
    }

    private fun startBackgroundLoop() {
        bgExecutor.execute {
            val (ok, newEmail, _) = apiClient.deleteOldAndCreateNew()
            if (ok) {
                copyToClipboard(newEmail, null)
            }
        }

        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduleNextPoll(2000L)
    }

    private fun scheduleNextPoll(delayMs: Long) {
        if (isStopping) return
        scheduler?.schedule({
            if (isStopping) return@schedule
            var nextDelay = 2000L
            try {
                val added = apiClient.pollOnce()
                for (em in added) {
                    val otpCode = em.otp
                    if (!otpCode.isNullOrBlank()) {
                        copyToClipboard(otpCode, "🔑 Auto-copied OTP: $otpCode")
                    }
                    sendIncomingMailNotification(em)
                }
            } catch (_: Exception) {
                val fails = apiClient.consecutiveFailures
                nextDelay = min(12000L, 2000L + (fails * 2000L))
            }
            scheduleNextPoll(nextDelay)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    fun triggerImmediateRefresh(onDone: ((Int, String?) -> Unit)? = null) {
        bgExecutor.execute {
            try {
                val added = apiClient.pollOnce()
                for (em in added) {
                    val otpCode = em.otp
                    if (!otpCode.isNullOrBlank()) {
                        copyToClipboard(otpCode, "🔑 Auto-copied OTP: $otpCode")
                    }
                    sendIncomingMailNotification(em)
                }
                mainHandler.post { onDone?.invoke(added.size, null) }
            } catch (e: Exception) {
                mainHandler.post { onDone?.invoke(0, e.message ?: "Refresh error") }
            }
        }
    }

    fun triggerRotateNewMail(onDone: ((Boolean, String, Int) -> Unit)? = null) {
        bgExecutor.execute {
            val (ok, msg, delCount) = apiClient.deleteOldAndCreateNew()
            if (ok) {
                copyToClipboard(msg, null)
            }
            mainHandler.post { onDone?.invoke(ok, msg, delCount) }
        }
    }

    fun shutdownAndExit(onFinished: (() -> Unit)? = null) {
        if (isStopping) return
        isStopping = true
        scheduler?.shutdownNow()
        bgExecutor.execute {
            apiClient.cleanupCurrent()
            mainHandler.post {
                releaseLocks()
                unregisterNetworkCallback()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                onFinished?.invoke()
            }
        }
    }

    fun copyToClipboard(text: String, toastMsg: String?) {
        mainHandler.post {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Quetta Temp Mail", text))
            if (!toastMsg.isNullOrBlank()) {
                Toast.makeText(applicationContext, toastMsg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    triggerImmediateRefresh(null)
                }
            }
            networkCallback = cb
            connectivityManager?.registerNetworkCallback(req, cb)
        } catch (_: Exception) {}
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val fgChan = NotificationChannel(
                CHANNEL_SERVICE,
                "Active Temp Mail Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps temp mail inbox polling alive in the background"
                setShowBadge(false)
            }
            val alertChan = NotificationChannel(
                CHANNEL_ALERTS,
                "Incoming Mail & OTP Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Instant alerts and OTP copy actions when new mail arrives"
            }
            nm.createNotificationChannel(fgChan)
            nm.createNotificationChannel(alertChan)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val copyPi = PendingIntent.getService(
            this, 1, Intent(this, QuettaMailService::class.java).apply { action = ACTION_COPY_EMAIL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rotatePi = PendingIntent.getService(
            this, 2, Intent(this, QuettaMailService::class.java).apply { action = ACTION_ROTATE_NEW },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 3, Intent(this, QuettaMailService::class.java).apply { action = ACTION_STOP_SERVICE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val currentEmail = apiClient.email
        val msgCount = apiClient.getMessageCount()
        val subtitle = "${apiClient.status} • $msgCount msg(s)"

        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_quetta)
            .setColor(0xFF0A84FF.toInt())
            .setContentTitle("⚡ $currentEmail")
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(0, "Copy", copyPi)
            .addAction(0, "New Mail", rotatePi)
            .addAction(0, "Exit", stopPi)
            .build()
    }

    private fun updateForegroundNotification() {
        if (isStopping) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID_SERVICE, buildForegroundNotification())
        } catch (_: Exception) {}
    }

    private fun sendIncomingMailNotification(em: EmailMessage) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 10, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val otpCode = em.otp
        val title = if (!otpCode.isNullOrBlank()) {
            "🔑 OTP $otpCode • ${em.subject.take(60)}"
        } else {
            "📧 ${em.subject.take(80)}"
        }

        val expandedBody = "From: ${em.from}\n\n${em.preview}"

        val builder = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_quetta)
            .setColor(0xFF0A84FF.toInt())
            .setContentTitle(title)
            .setContentText("From: ${em.from.take(100)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPi)

        if (!otpCode.isNullOrBlank()) {
            val otpIntent = Intent(this, QuettaMailService::class.java).apply {
                action = ACTION_COPY_OTP
                putExtra(EXTRA_OTP, otpCode)
            }
            val otpPi = PendingIntent.getService(
                this, alertNotifId, otpIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Copy OTP ($otpCode)", otpPi)
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(alertNotifId++, builder.build())
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "QuettaTempMail::BackgroundServiceWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {}

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(lockMode, "QuettaTempMail::WifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        if (!isStopping) {
            isStopping = true
            scheduler?.shutdownNow()
            Thread { apiClient.cleanupCurrent() }.start()
            releaseLocks()
            unregisterNetworkCallback()
        }
        super.onDestroy()
    }
}
