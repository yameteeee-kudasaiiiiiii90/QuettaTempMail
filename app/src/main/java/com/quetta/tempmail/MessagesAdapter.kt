package com.quetta.tempmail

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MessagesAdapter(
    private var items: List<EmailMessage>,
    private val starredIds: Set<String>,
    private val expandedIds: MutableSet<String>,
    private val onStarClick: (String) -> Unit,
    private val onToggleExpand: (String) -> Unit,
    private val onCopyOtpClick: (String) -> Unit,
    private val onDeleteClick: (EmailMessage) -> Unit,
    private val onVerifyLinkClick: (String) -> Unit
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
        val layoutExpanded: LinearLayout = view.findViewById(R.id.layoutExpanded)
        val tvFullBody: TextView = view.findViewById(R.id.tvFullBody)
        val layoutExpandedOtp: LinearLayout = view.findViewById(R.id.layoutExpandedOtp)
        val tvExpandedOtpValue: TextView = view.findViewById(R.id.tvExpandedOtpValue)
        val btnExpandedCopyOtp: Button = view.findViewById(R.id.btnExpandedCopyOtp)
        val btnOpenVerifyLink: Button = view.findViewById(R.id.btnOpenVerifyLink)
        val tvAttachmentInfo: TextView = view.findViewById(R.id.tvAttachmentInfo)
        val btnExpandedDelete: Button = view.findViewById(R.id.btnExpandedDelete)
    }

    fun currentPositionOf(id: String): Int = items.indexOfFirst { it.id == id }

    fun updateData(newItems: List<EmailMessage>) {
        items = newItems
        // drop expanded state for messages that no longer exist
        expandedIds.retainAll(newItems.map { it.id }.toSet())
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = items[position]

        // iOS-style avatar: initial + deterministic tint
        val initial = msg.from.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "Q"
        holder.tvAvatarText.text = initial
        holder.avatarFrame.backgroundTintList =
            ColorStateList.valueOf(AVATAR_COLORS[Math.abs(msg.from.hashCode()) % AVATAR_COLORS.size])

        holder.tvSender.text = msg.from
        holder.tvSubject.text = (if (msg.hasAttachment) "📎 " else "") + msg.subject
        holder.tvPreview.text = msg.preview
        holder.tvTime.text = formatShortTime(msg.date)

        holder.cbStar.setOnCheckedChangeListener(null)
        holder.cbStar.isChecked = starredIds.contains(msg.id)
        holder.cbStar.setOnCheckedChangeListener { _, _ -> onStarClick(msg.id) }

        holder.itemView.setOnClickListener { onToggleExpand(msg.id) }

        if (!msg.otp.isNullOrBlank()) {
            holder.btnCopyOtpChip.visibility = View.VISIBLE
            holder.btnCopyOtpChip.text = "📋 ${msg.otp}"
            holder.btnCopyOtpChip.setOnClickListener { onCopyOtpClick(msg.otp!!) }
        } else {
            holder.btnCopyOtpChip.visibility = View.GONE
        }

        // ---- expanded inline reader ----
        if (expandedIds.contains(msg.id)) {
            holder.layoutExpanded.visibility = View.VISIBLE
            holder.tvPreview.maxLines = Integer.MAX_VALUE
            holder.tvFullBody.text = msg.textBody.ifBlank { msg.preview }

            if (!msg.otp.isNullOrBlank()) {
                holder.layoutExpandedOtp.visibility = View.VISIBLE
                holder.tvExpandedOtpValue.text = msg.otp
                holder.btnExpandedCopyOtp.setOnClickListener { onCopyOtpClick(msg.otp!!) }
            } else {
                holder.layoutExpandedOtp.visibility = View.GONE
            }

            if (!msg.verifyLink.isNullOrBlank()) {
                holder.btnOpenVerifyLink.visibility = View.VISIBLE
                holder.btnOpenVerifyLink.setOnClickListener { onVerifyLinkClick(msg.verifyLink!!) }
            } else {
                holder.btnOpenVerifyLink.visibility = View.GONE
            }

            if (msg.hasAttachment) {
                holder.tvAttachmentInfo.visibility = View.VISIBLE
                holder.tvAttachmentInfo.text =
                    "📎 ${msg.attachments.size} attachment" + if (msg.attachments.size == 1) "" else "s"
            } else {
                holder.tvAttachmentInfo.visibility = View.GONE
            }

            holder.btnExpandedDelete.setOnClickListener { onDeleteClick(msg) }
        } else {
            holder.layoutExpanded.visibility = View.GONE
            holder.tvPreview.maxLines = 2
        }
    }

    override fun getItemCount(): Int = items.size

    private fun formatShortTime(iso: String): String {
        if (iso.length >= 16) return iso.slice(11..15)
        return iso
    }

    companion object {
        private val AVATAR_COLORS = intArrayOf(
            0xFF0A84FF.toInt(), // iOS blue
            0xFF30D158.toInt(), // iOS green
            0xFFFF9F0A.toInt(), // iOS orange
            0xFFFF453A.toInt(), // iOS red
            0xFFBF5AF2.toInt(), // iOS purple
            0xFF64D2FF.toInt()  // iOS teal
        )
    }
}
