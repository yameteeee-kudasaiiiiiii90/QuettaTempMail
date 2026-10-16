package com.quetta.tempmail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MessagesAdapter(
    private var items: List<EmailMessage>,
    private val starredIds: Set<String>,
    private val onStarClick: (String) -> Unit,
    private val onItemClick: (EmailMessage) -> Unit,
    private val onCopyOtpClick: (String) -> Unit
) : RecyclerView.Adapter<MessagesAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatarFrame: FrameLayout = view.findViewById(R.id.avatarFrame)
        val tvAvatarText: TextView = view.findViewById(R.id.tvAvatarText)
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val cbStar: CheckBox = view.findViewById(R.id.cbStar)
        val tvSubject: TextView = view.findViewById(R.id.tvSubject)
        val tvPreview: TextView = view.findViewById(R.id.tvPreview)
        val btnCopyOtpChip: Button = view.findViewById(R.id.btnCopyOtpChip)
    }

    fun updateData(newItems: List<EmailMessage>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = items[position]
        holder.tvSender.text = if (!msg.otp.isNullOrBlank()) "🔑 OTP Code" else msg.from
        holder.tvSubject.text = if (!msg.otp.isNullOrBlank()) "${msg.otp} • ${msg.subject}" else ((if (msg.hasAttachment) "📎 " else "") + msg.subject)
        holder.tvPreview.text = msg.preview
        holder.tvTime.text = formatShortTime(msg.date)

        holder.cbStar.isChecked = starredIds.contains(msg.id)
        holder.cbStar.setOnClickListener {
            onStarClick(msg.id)
        }

        holder.itemView.setOnClickListener {
            onItemClick(msg)
        }

        if (!msg.otp.isNullOrBlank()) {
            holder.btnCopyOtpChip.visibility = View.VISIBLE
            holder.btnCopyOtpChip.text = "📋 Copy OTP: ${msg.otp}"
            holder.btnCopyOtpChip.setOnClickListener {
                onCopyOtpClick(msg.otp)
            }
        } else {
            holder.btnCopyOtpChip.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = items.size

    private fun formatShortTime(iso: String): String {
        if (iso.length >= 16) return iso.slice(11..15)
        return iso
    }
}
