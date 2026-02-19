package com.example.aibrain

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ResultsBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onExportRequested()
        fun onNewScanRequested()
    }

    var listener: Listener? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.bottom_sheet_results, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val args = requireArguments()
        val sessionId = args.getString(ARG_SESSION_ID).orEmpty()
        val revisionId = args.getString(ARG_REVISION_ID).orEmpty()
        val variantName = args.getString(ARG_VARIANT_NAME).orEmpty()
        val safetyScore = args.getInt(ARG_SAFETY_SCORE, 0)
        val physicsStatus = args.getString(ARG_PHYSICS_STATUS).orEmpty()
        val critique = args.getString(ARG_CRITIQUE).orEmpty()

        val tvTitle: TextView = view.findViewById(R.id.tv_title)
        val tvMeta: TextView = view.findViewById(R.id.tv_meta)
        val tvSafety: TextView = view.findViewById(R.id.tv_safety)
        val tvPhysics: TextView = view.findViewById(R.id.tv_physics)
        val tvCritique: TextView = view.findViewById(R.id.tv_critique)

        val btnCopy: Button = view.findViewById(R.id.btn_copy_report)
        val btnExport: Button = view.findViewById(R.id.btn_export_glb)
        val btnNew: Button = view.findViewById(R.id.btn_new_scan)

        tvTitle.text = "✓ Проект принят"
        tvMeta.text = buildString {
            append("variant: ")
            append(variantName.ifBlank { "—" })
            append("\n")
            append("session_id: ")
            append(sessionId.ifBlank { "—" })
            append("\n")
            append("rev_id: ")
            append(if (revisionId.isBlank()) "—" else revisionId.take(12))
        }

        tvSafety.text = "Safety: $safetyScore%"
        val safetyColor = when {
            safetyScore >= 70 -> R.color.green_primary
            safetyScore >= 40 -> R.color.orange_primary
            else -> R.color.red_primary
        }
        tvSafety.setTextColor(ContextCompat.getColor(requireContext(), safetyColor))

        tvPhysics.text = "Physics: ${physicsStatus.ifBlank { "UNKNOWN" }}"
        tvPhysics.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when (physicsStatus.uppercase()) {
                    "OK" -> R.color.green_primary
                    "COLLAPSE" -> R.color.red_primary
                    "ERROR" -> R.color.orange_primary
                    else -> R.color.text_white_dim
                }
            )
        )

        tvCritique.text = if (critique.isBlank()) "AI critique: —" else critique

        btnCopy.setOnClickListener {
            val report = buildString {
                append("Project Accepted\n")
                append("variant: ").append(variantName).append("\n")
                append("session_id: ").append(sessionId).append("\n")
                append("rev_id: ").append(revisionId).append("\n")
                append("safety: ").append(safetyScore).append("%\n")
                append("physics: ").append(physicsStatus).append("\n")
                if (critique.isNotBlank()) {
                    append("\nAI critique:\n")
                    append(critique)
                }
            }
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("report", report))
        }

        btnExport.setOnClickListener {
            listener?.onExportRequested()
            dismiss()
        }

        btnNew.setOnClickListener {
            listener?.onNewScanRequested()
            dismiss()
        }
    }

    companion object {
        private const val ARG_SESSION_ID = "session_id"
        private const val ARG_REVISION_ID = "revision_id"
        private const val ARG_VARIANT_NAME = "variant_name"
        private const val ARG_SAFETY_SCORE = "safety_score"
        private const val ARG_PHYSICS_STATUS = "physics_status"
        private const val ARG_CRITIQUE = "critique"

        fun newInstance(
            sessionId: String,
            revisionId: String,
            variantName: String,
            safetyScore: Int,
            physicsStatus: String,
            critique: String
        ): ResultsBottomSheet {
            val f = ResultsBottomSheet()
            f.arguments = Bundle().apply {
                putString(ARG_SESSION_ID, sessionId)
                putString(ARG_REVISION_ID, revisionId)
                putString(ARG_VARIANT_NAME, variantName)
                putInt(ARG_SAFETY_SCORE, safetyScore)
                putString(ARG_PHYSICS_STATUS, physicsStatus)
                putString(ARG_CRITIQUE, critique)
            }
            return f
        }
    }
}
