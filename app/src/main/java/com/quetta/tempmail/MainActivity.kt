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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/**
 * Quetta Temp Mail v6.1 — the classic interface (title + status pill,
 * address box, Copy/Refresh/New/Exit row, inbox card with search),
 * restyled in iOS 18 dark mode. Tap a message to expand it inline.
 */
class MainActivity : AppCompatActivity() {

    private var mailService: QuettaMailService? = null
    private var isBound = false

    private val expandedIds = mutableSetOf<String>()
    private var searchQuery = ""

    private lateinit var messagesAdapter: MessagesAdapter

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
        val rv = findViewById<RecyclerView>(R.id.recyclerViewMessages)
        rv.layoutManager = LinearLayoutManager(this)
        messagesAdapter = MessagesAdapter(
            emptyList(),
            expandedIds,
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

        findViewById<Button>(R.id.btnCopyAddress).setOnClickListener { copyActiveEmail() }
        findViewById<TextView>(R.id.tvEmailAddress).setOnClickListener { copyActiveEmail() }

        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
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

        findViewById<Button>(R.id.btnNewAddress).setOnClickListener { rotateNewAddress() }
        findViewById<Button>(R.id.btnExit).setOnClickListener { onExitAppClick() }
        findViewById<Button>(R.id.btnKeepAlive).setOnClickListener { onKeepAliveClick() }

        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim()?.lowercase(Locale.US) ?: ""
                refreshUiState()
            }
        })
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

    // ---------- UI refresh ----------

    private fun refreshUiState() {
        val client = mailService?.apiClient ?: return
        findViewById<TextView>(R.id.tvEmailAddress).text = client.email

        val statusText = findViewById<TextView>(R.id.statusText)
        val statusDot = findViewById<View>(R.id.statusDot)
        if (client.error != null) {
            statusText.text = client.error
            statusText.setTextColor(0xFFFF453A.toInt())
            statusDot.setBackgroundResource(R.drawable.bg_red_dot)
        } else {
            statusText.text = if (client.status == "Watching inbox live") "Watching live in the background"
                else client.status
            statusText.setTextColor(0xFF8E8E93.toInt())
            statusDot.setBackgroundResource(R.drawable.bg_green_dot)
        }

        val rawMsgs = client.getMessages()
        val filtered = if (searchQuery.isEmpty()) {
            rawMsgs
        } else {
            rawMsgs.filter { m ->
                m.from.lowercase(Locale.US).contains(searchQuery)
                    || m.subject.lowercase(Locale.US).contains(searchQuery)
                    || m.textBody.lowercase(Locale.US).contains(searchQuery)
                    || (m.otp?.contains(searchQuery) == true)
            }
        }

        findViewById<TextView>(R.id.tvInboxTitle).text = "INBOX (${filtered.size})"
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
