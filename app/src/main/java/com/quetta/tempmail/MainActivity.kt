package com.quetta.tempmail

import android.Manifest
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
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var mailService: QuettaMailService? = null
    private var isBound = false

    private val starredIds = mutableSetOf<String>()
    private val historyItems = mutableListOf<HistoryItem>()
    private var currentSegment = "all"
    private var currentTab = "inbox"

    private lateinit var messagesAdapter: MessagesAdapter
    private lateinit var historyAdapter: HistoryAdapter

    private val readerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            ReaderActivity.cachedEmailMessage?.id?.let { id ->
                mailService?.apiClient?.deleteSingleMessage(id)
                refreshUiState()
                Toast.makeText(this, "Email deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as QuettaMailService.LocalBinder
            mailService = binder.getService()
            isBound = true
            mailService?.setStateListener {
                refreshUiState()
            }
            refreshUiState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            mailService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestNotificationPermissionIfNeeded()

        setupViews()

        val serviceIntent = Intent(this, QuettaMailService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun setupViews() {
        val rv = findViewById<RecyclerView>(R.id.recyclerViewMessages)
        rv.layoutManager = LinearLayoutManager(this)
        messagesAdapter = MessagesAdapter(
            emptyList(),
            starredIds,
            onStarClick = { id ->
                if (starredIds.contains(id)) starredIds.remove(id) elsestarredIds.add(id)
                refreshUiState()
            },
            onItemClick = { msg ->
                ReaderActivity.cachedEmailMessage = msg
                readerLauncher.launch(Intent(this, ReaderActivity::class.java))
            },
            onCopyOtpClick = { otp ->
                mailService?.copyToClipboard(otp, "Copied OTP: $otp")
            }
        )
        rv.adapter = messagesAdapter

        val rvHist = findViewById<RecyclerView>(R.id.recyclerViewHistory)
        rvHist.layoutManager = LinearLayoutManager(this)
        historyAdapter = HistoryAdapter(historyItems)
        rvHist.adapter = historyAdapter

        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            val btn = it
            btn.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in))
            mailService?.triggerImmediateRefresh { added, err ->
                refreshUiState()
                if (err != null) {
                    Toast.makeText(this, "Error: $err", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, if (added > 0) "Received $added new email(s)" else "Inbox is up to date", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<ImageButton>(R.id.btnRotate).setOnClickListener {
            rotateNewAddress()
        }

        findViewById<Button>(R.id.btnCopyAddress).setOnClickListener {
            copyActiveEmail()
        }
        findViewById<TextView>(R.id.tvEmailAddress).setOnClickListener {
            copyActiveEmail()
        }

        // Segments
        findViewById<TextView>(R.id.segAll).setOnClickListener { setSegment("all") }
        findViewById<TextView>(R.id.segStarred).setOnClickListener { setSegment("starred") }
        findViewById<TextView>(R.id.segOtp).setOnClickListener { setSegment("otp") }
    }

    private fun setSegment(seg: String) {
        currentSegment = seg
        val colActive = 0xFFFFFFFF.toInt()
        val colMuted = 0xFF8E8E93.toInt()

        val tvAll = findViewById<TextView>(R.id.segAll)
        val tvStarred = findViewById<TextView>(R.id.segStarred)
        val tvOtp = findViewById<TextView>(R.id.segOtp)

        tvAll.setBackgroundResource(if (seg == "all") R.drawable.bg_segment_active else android.R.color.transparent)
        tvAll.setTextColor(if (seg == "all") colActive else colMuted)

        tvStarred.setBackgroundResource(if (seg == "starred") R.drawable.bg_segment_active else android.R.color.transparent)
        tvStarred.setTextColor(if (seg == "starred") colActive else colMuted)

        tvOtp.setBackgroundResource(if (seg == "otp") R.drawable.bg_segment_active else android.R.color.transparent)
        tvOtp.setTextColor(if (seg == "otp") colActive else colMuted)

        findViewById<TextView>(R.id.tvSectionTitle).text = when(seg) {
            "starred" -> "Starred"
            "otp" -> "Verification Codes"
            else -> "Inbox"
        }
        refreshUiState()
    }

    // Bottom Navigation Clicks
    fun onTabInboxClick(view: View) { switchTab("inbox") }
    fun onTabGenerateClick(view: View) { switchTab("generate") }
    fun onTabHistoryClick(view: View) { switchTab("history") }
    fun onTabSettingsClick(view: View) { switchTab("settings") }

    private fun switchTab(tab: String) {
        currentTab = tab
        findViewById<LinearLayout>(R.id.tabInboxContainer).visibility = if (tab == "inbox") View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.tabGenerateContainer).visibility = if (tab == "generate") View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.tabHistoryContainer).visibility = if (tab == "history") View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.tabSettingsContainer).visibility = if (tab == "settings") View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.headerLayout).visibility = if (tab == "inbox") View.VISIBLE else View.GONE

        val blue = 0xFF0A84FF.toInt()
        val gray = 0xFF8E8E93.toInt()

        findViewById<ImageView>(R.id.iconTabInbox).setColorFilter(if (tab == "inbox") blue else gray)
        findViewById<TextView>(R.id.textTabInbox).setTextColor(if (tab == "inbox") blue else gray)

        findViewById<ImageView>(R.id.iconTabGenerate).setColorFilter(if (tab == "generate") blue else gray)
        findViewById<TextView>(R.id.textTabGenerate).setTextColor(if (tab == "generate") blue else gray)

        findViewById<ImageView>(R.id.iconTabHistory).setColorFilter(if (tab == "history") blue else gray)
        findViewById<TextView>(R.id.textTabHistory).setTextColor(if (tab == "history") blue else gray)

        findViewById<ImageView>(R.id.iconTabSettings).setColorFilter(if (tab == "settings") blue else gray)
        findViewById<TextView>(R.id.textTabSettings).setTextColor(if (tab == "settings") blue else gray)
    }

    private fun copyActiveEmail() {
        val em = mailService?.apiClient?.email ?: return
        if (!em.contains("...")) {
            mailService?.copyToClipboard(em, "Copied: $em")
        }
    }

    fun onGenCopyClick(view: View) { copyActiveEmail() }
    fun onGenRotateClick(view: View) { rotateNewAddress() }

    private fun rotateNewAddress() {
        
        Toast.makeText(this, "Deleting old address & creating new...", Toast.LENGTH_SHORT).show()
        mailService?.triggerRotateNewMail { ok, emailOrErr, _ ->
            refreshUiState()
            if (ok) {
                addHistory("Generated new address: $emailOrErr")
                Toast.makeText(this, "Old deleted! Copied: $emailOrErr", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Error: $emailOrErr", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onKeepAliveClick(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    return
                }
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "App is already exempted from battery optimizations", Toast.LENGTH_SHORT).show()
    }

    fun onExitAppClick(view: View) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Exit App")
            .setMessage("Delete temporary email address and stop background service?")
            .setPositiveButton("Exit") { _, _ ->
                mailService?.shutdownAndExit {
                    finishAndRemoveTask()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addHistory(text: String) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        historyItems.add(0, HistoryItem(timeStr, text))
        historyAdapter.notifyDataSetChanged()
    }

    private fun refreshUiState() {
        val client = mailService?.apiClient ?: return
        val em = client.email
        findViewById<TextView>(R.id.tvEmailAddress).text = em
        findViewById<TextView>(R.id.tvGenEmail).text = em

        val statusText = findViewById<TextView>(R.id.statusText)
        val statusDot = findViewById<View>(R.id.statusDot)
        if (client.error != null) {
            statusText.text = client.error
            statusText.setTextColor(0xFFFF453A.toInt())
            statusDot.setBackgroundResource(R.drawable.bg_red_dot)
        } else {
            statusText.text = if (client.status == "Watching inbox live") "Watching Live" else client.status
            statusText.setTextColor(0xFF30D158.toInt())
            statusDot.setBackgroundResource(R.drawable.bg_green_dot)
        }

        findViewById<TextView>(R.id.lastSyncedText).text = if (client.lastChecked != "-") "Synced ${client.lastChecked}" else ""

        val rawMsgs = client.getMessages()
        val filtered = rawMsgs.filter { m ->
            when(currentSegment) {
                "starred" -> starredIds.contains(m.id)
                "otp" -> !m.otp.isNullOrBlank()
                else -> true
            }
        }

        findViewById<TextView>(R.id.tvMsgCount).text = "${filtered.size} messages"
        messagesAdapter.updateData(filtered)

        val emptyLayout = findViewById<LinearLayout>(R.id.layoutEmptyInbox)
        val rv = findViewById<RecyclerView>(R.id.recyclerViewMessages)
        if (filtered.isEmpty()) {
            emptyLayout.visibility = View.VISIBLE
            rv.visibility = View.GONE
        } else {
            emptyLayout.visibility = View.GONE
            rv.visibility = View.VISIBLE
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

    override fun onDestroy() {
        if (isBound) {
            mailService?.setStateListener(null)
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}
