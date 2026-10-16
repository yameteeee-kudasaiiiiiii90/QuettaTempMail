package com.quetta.tempmail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class HistoryItem(val time: String, val description: String)

class HistoryAdapter(private val items: List<HistoryItem>) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLabel: TextView = view.findViewById(android.R.id.text1)
        val tvSub: TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvLabel.text = item.description
        holder.tvLabel.setTextColor(0xFFFFFFFF.toInt())
        holder.tvSub.text = item.time
        holder.tvSub.setTextColor(0xFF8E8E93.toInt())
    }

    override fun getItemCount(): Int = items.size
}
