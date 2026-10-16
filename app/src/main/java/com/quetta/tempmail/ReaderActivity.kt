package com.quetta.tempmail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ReaderActivity : AppCompatActivity() {

    companion object {
        var cachedEmailMessage: EmailMessage? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        val msg = cachedEmailMessage
        if (msg == null) {
            finish()
            return
        }

        val btnBack = findViewById<Button>(R.id.btnReaderBack)
        val btnDel = findViewById<Button>(R.id.btnReaderDelete)
        val tvSubj = findViewById<TextView>(R.id.tvReaderSubject)
        val tvFrom = findViewById<TextView>(R.id.tvReaderFrom)
        val tvDate = findViewById<TextView>(R.id.tvReaderDate)
        val tvBody = findViewById<TextView>(R.id.tvReaderBody)

        val layoutOtp = findViewById<LinearLayout>(R.id.layoutReaderOtp)
        val tvOtpVal = findViewById<TextView>(R.id.tvReaderOtpValue)
        val btnCopyOtp = findViewById<Button>(R.id.btnReaderCopyOtp)

        tvSubj.text = msg.subject
        tvFrom.text = "From: ${msg.from}"
        tvDate.text = "Date: ${msg.date}"
        tvBody.text = msg.textBody

        if (!msg.otp.isNullOrBlank()) {
            layoutOtp.visibility = View.VISIBLE
            tvOtpVal.text = msg.otp
            btnCopyOtp.setOnClickListener {
                copyToClipboard(msg.otp)
            }
        } else {
            layoutOtp.visibility = View.GONE
        }

        btnBack.setOnClickListener {
            finish()
        }

        btnDel.setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("OTP Code", text))
        Toast.makeText(this, "Copied OTP: $text", Toast.LENGTH_SHORT).show()
    }
}
