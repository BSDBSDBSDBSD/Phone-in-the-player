package com.offline.phonelink.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.offline.phonelink.databinding.ItemRowBinding

/** A two-line row with a call button: used for contacts and for recent calls. */
data class Row(val title: String, val subtitle: String, val onClick: () -> Unit)

class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {
    private var rows: List<Row> = emptyList()

    fun submit(newRows: List<Row>) {
        rows = newRows
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    class Holder(val b: ItemRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.b.title.text = row.title
        holder.b.subtitle.text = row.subtitle
        holder.b.avatar.text = row.title.firstOrNull { it.isLetterOrDigit() }?.toString() ?: "#"
        holder.b.root.setOnClickListener { row.onClick() }
        holder.b.callIcon.setOnClickListener { row.onClick() }
    }
}
