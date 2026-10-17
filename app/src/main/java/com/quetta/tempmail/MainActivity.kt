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
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Quetta Temp Mail v6.0 — single-page iOS 18 dark-mode edition.
 * Everything lives on one scrollable page: address card, search,
 * segmented inbox, inline-expanding reader, history and settings.
 */
class MainActivity : AppCompatActivity() {

    private var mailService: QuettaMailService? = null
    private var isBound = false

    private val starredIds = mutableSetOf<String>()
    private val expandedIds = mutableSetOf<String>()
    private val historyItems = mutableListOf<HistoryItem>()
    private var currentSegment = "all"
    private var searchQuery = ""
    private var currentTab = "inbox"

    private lateinit var messagesAdapter: MessagesAdapter
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var scrollView: NestedScrollView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as QuettaMailService.LocalBinder
            mailService = binder.getService()
            isBound = true
            mailService?.setStateListener { refreshUiState() }
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
        scrollView = findViewById(R.id.scrollView)

        // ---- inbox list ----
        val rv = findViewById<RecyclerView>(R.id.recyclerViewMessages)
        rv.layoutManager = LinearLayoutManager(this)
        rv.isNestedScrollingEnabled = false
        messagesAdapter = MessagesAdapter(
            emptyList(),
            starredIds,
            expandedIds,
            onStarClick = { id ->
                if (starredIds.contains(id)) starredIds.remove(id) else starredIds.add(id)
                refreshUiState()
            },
            onToggleExpand = { id ->
                if (expandedIds.contains(id)) expandedIds.remove(id) else expandedIds.add(id)
                val pos = messagesAdapter.currentPositionOf(id)
                if (pos >= 0) messagesAdapter.notifyItemChanged(pos) else messagesAdapter.notifyDataSetChanged()
            },
            onCopyOtpClick = { otp ->
                mailService?.copyToClipboard(otp, "Copied OTP: $otp")
            },
            onDeleteClick = { msg ->
                confirmDeleteMessage(msg)
            },
            onVerifyLinkClick = { url ->
                openVerifyLink(url)
            }
        )
        rv.adapter = messagesAdapter

        // ---- history list ----
        val rvHist = findViewById<RecyclerView>(R.id.recyclerViewHistory)
        rvHist.layoutManager = LinearLayoutManager(this)
        rvHist.isNestedScrollingEnabled = false
        historyAdapter = HistoryAdapter(historyItems)
        rvHist.adapter = historyAdapter

        // ---- address card ----
        findViewById<View>(R.id.btnCopyAddress).setOnClickListener { copyActiveEmail() }
        findViewById<TextView>(R.id.tvEmailAddress).setOnClickListener { copyActiveEmail() }
        findViewById<View>(R.id.btnNewAddress).setOnClickListener { rotateNewAddress() }
        findViewById<View>(R.id.btnRefresh).setOnClickListener {
            it.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in))
            mailService?.triggerImmediateRefresh { added, err ->
                refreshUiState()
                if (err != null) {
                    Toast.makeText(this, "Error: $err", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        if (added > 0) "Received $added new email(s)" else "Inbox is up to date",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // ---- search ----
        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim()?.lowercase(Locale.US) ?: ""
                refreshUiState()
            }
        })

        // ---- segments ----
        findViewById<TextView>(R.id.segAll).setOnClickListener { setSegment("all") }
        findViewById<TextView>(R.id.segStarred).setOnClickListener { setSegment("starred") }
        findViewById<TextView>(R.id.segOtp).setOnClickListener { setSegment("otp") }

        // ---- settings rows ----
        findViewById<LinearLayout>(R.id.rowKeepAlive).setOnClickListener { onKeepAliveClick() }
        findViewById<LinearLayout>(R.id.rowExit).setOnClickListener { onExitAppClick() }

        updateTabSelection("inbox")
    }

    // ---------- bottom tabs: scroll to page sections ----------

    fun onTabInboxClick(view: View) = goToTab("inbox", R.id.sectionInbox)
    fun onTabGenerateClick(view: View) = goToTab("generate", R.id.sectionGenerate)
    fun onTabHistoryClick(view: View) = goToTab("history", R.id.sectionHistory)
    fun onTabSettingsClick(view: View) = goToTab("settings", R.id.sectionSettings)

    private fun goToTab(tab: String, sectionId: Int) {
        currentTab = tab
        updateTabSelection(tab)
        val section = findViewById<View>(sectionId)
        scrollView.post { scrollView.smoothScrollTo(0, section.top) }
    }

    private fun updateTabSelection(tab: String) {
        val blue = 0xFF0A84FF.toInt()
        val gray = 0xFF8E8E93.toInt()
        setTabTint(R.id.iconTabInbox, R.id.textTabInbox, tab == "inbox", blue, gray)
        setTabTint(R.id.iconTabGenerate, R.id.textTabGenerate, tab == "generate", blue, gray)
        setTabTint(R.id.iconTabHistory, R.id.textTabHistory, tab == "history", blue, gray)
        setTabTint(R.id.iconTabSettings, R.id.textTabSettings, tab == "settings", blue, gray)
    }

    private fun setTabTint(iconId: Int, textId: Int, active: Boolean, blue: Int, gray: Int) {
        findViewById<ImageView>(iconId).setColorFilter(if (active) blue else gray)
        findViewById<TextView>(textId).setTextColor(if (active) blue else gray)
    }

    // ---------- segments ----------

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

        findViewById<TextView>(R.id.tvSectionTitle).text = when (seg) {
            "starred" -> "Starred"
            "otp" -> "Verification Codes"
            else -> "Inbox"
        }
        refreshUiState()
    }

    // ---------- actions ----------

    private fun copyActiveEmail() {
        val em = mailService?.apiClient?.email ?: return
        if (!em.contains("...") && !em.contains("Initializing")) {
            mailService?.copyToClipboard(em, "Copied: $em")
        }
    }

    private fun rotateNewAddress() {
        Toast.makeText(this, "Deleting old address & creating new...", Toast.LENGTH_SHORT).show()
        mailService?.triggerRotateNewMail { ok, emailOrErr, _ ->
            refreshUiState()
            if (ok) {
                addHistory("Generated new address: $emailOrErr")
                mailService?.copyToClipboard(emailOrErr, "Old deleted! Copied: $emailOrErr")
            } else {
                Toast.makeText(this, "Error: $emailOrErr", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDeleteMessage(msg: EmailMessage) {
        AlertDialog.Builder(this)
            .setTitle("Delete Email")
            .setMessage("Delete this email permanently?")
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    try {
                        mailService?.apiClient?.deleteSingleMessage(msg.id)
                    } catch (_: Exception) {
                    }
                    runOnUiThread {
                        expandedIds.remove(msg.id)
                        refreshUiState()
                        Toast.makeText(this, "Email deleted", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openVerifyLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onKeepAliveClick() {
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
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, "App is already exempted from battery optimizations", Toast.LENGTH_SHORT).show()
    }

    private fun onExitAppClick() {
        AlertDialog.Builder(this)
            .setTitle("Exit App")
            .setMessage("Delete temporary email address and stop background service?")
            .setPositiveButton("Exit") { _, _ ->
                mailService?.shutdownAndExit { finishAndRemoveTask() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addHistory(text: String) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        historyItems.add(0, HistoryItem(timeStr, text))
        historyAdapter.notifyDataSetChanged()
        refreshHistoryVisibility()
    }

    // ---------- UI refresh ----------

    private fun refreshUiState() {
        val client = mailService?.apiClient ?: return
        val em = client.email
        findViewById<TextView>(R.id.tvEmailAddress).text = em

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

        findViewById<TextView>(R.id.lastSyncedText).text =
            if (client.lastChecked != "-") "Synced ${client.lastChecked}" else ""

        val rawMsgs = client.getMessages()
        val filtered = rawMsgs.filter { m ->
            val segOk = when (currentSegment) {
                "starred" -> starredIds.contains(m.id)
                "otp" -> !m.otp.isNullOrBlank()
                else -> true
            }
            if (!segOk) return@filter false
            if (searchQuery.isEmpty()) return@filter true
            m.from.lowercase(Locale.US).contains(searchQuery)
                || m.subject.lowercase(Locale.US).contains(searchQuery)
                || m.textBody.lowercase(Locale.US).contains(searchQuery)
                || (m.otp?.contains(searchQuery) == true)
        }

        findViewById<TextView>(R.id.tvMsgCount).text = "${filtered.size} messages"
        messagesAdapter.updateData(filtered)

        val emptyInbox = findViewById<LinearLayout>(R.id.layoutEmptyInbox)
        val rv = findViewById<RecyclerView>(R.id.recyclerViewMessages)
        if (filtered.isEmpty()) {
            emptyInbox.visibility = View.VISIBLE
            rv.visibility = View.GONE
        } else {
            emptyInbox.visibility = View.GONE
            rv.visibility = View.VISIBLE
        }

        refreshHistoryVisibility()
    }

    private fun refreshHistoryVisibility() {
        val rvHist = findViewById<RecyclerView>(R.id.recyclerViewHistory)
        val emptyHist = findViewById<TextView>(R.id.layoutEmptyHistory)
        if (historyItems.isEmpty()) {
            rvHist.visibility = View.GONE
            emptyHist.visibility = View.VISIBLE
        } else {
            rvHist.visibility = View.VISIBLE
            emptyHist.visibility = View.GONE
        }
    }

    // ---------- permissions / lifecycle ----------

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
