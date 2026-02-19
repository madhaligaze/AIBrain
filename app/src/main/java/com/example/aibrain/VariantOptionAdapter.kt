package com.example.aibrain

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class VariantOptionAdapter(
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<VariantOptionAdapter.VH>() {

    private var items: List<ScaffoldOption> = emptyList()
    private var selectedIndex: Int = 0

    fun submit(newItems: List<ScaffoldOption>, selected: Int = 0) {
        items = newItems
        selectedIndex = selected.coerceIn(0, maxOf(0, items.size - 1))
        notifyDataSetChanged()
    }

    fun setSelected(index: Int) {
        val prev = selectedIndex
        selectedIndex = index
        if (prev in items.indices) notifyItemChanged(prev)
        if (selectedIndex in items.indices) notifyItemChanged(selectedIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_variant_option, parent, false)
        return VH(v, onSelect)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position == selectedIndex, position)
    }

    class VH(itemView: View, private val onSelect: (Int) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.card_root)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        private val tvSafety: TextView = itemView.findViewById(R.id.tv_safety)
        private val tvWeight: TextView = itemView.findViewById(R.id.tv_weight)
        private val tvStats: TextView = itemView.findViewById(R.id.tv_stats)
        private val tvPreview: TextView = itemView.findViewById(R.id.tv_preview)

        fun bind(opt: ScaffoldOption, selected: Boolean, index: Int) {
            tvTitle.text = opt.variant_name.ifBlank { "Variant ${index + 1}" }
            tvSafety.text = "Safety: ${opt.safety_score}%"

            val weight = opt.stats?.total_weight_kg
            tvWeight.text = if (weight != null) "Weight: ${weight}kg" else "Weight: --"

            val beams = opt.stats?.total_beams
            val nodes = opt.stats?.total_nodes
            tvStats.text = "Beams: ${beams ?: "--"} | Nodes: ${nodes ?: "--"}"

            val preview = opt.ai_critique?.firstOrNull()?.trim().orEmpty()
            tvPreview.text = preview

            val ctx = itemView.context
            if (selected) {
                card.strokeWidth = 2
                card.strokeColor = ContextCompat.getColor(ctx, R.color.cyan_primary)
                card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.cyan_alpha_10))
            } else {
                card.strokeWidth = 1
                card.strokeColor = ContextCompat.getColor(ctx, R.color.cyan_alpha_20)
                card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.transparent_panel))
            }

            card.setOnClickListener { onSelect(index) }
        }
    }
}
