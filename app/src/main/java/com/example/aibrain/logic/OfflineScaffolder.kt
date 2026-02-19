package com.example.aibrain.logic

import com.google.ar.core.Pose

object OfflineScaffolder {
    /**
     * Fallback generator when network is unavailable.
     * Returns a local asset path for a default structure.
     */
    fun generateSimpleStructure(origin: Pose, width: Int, height: Int): String {
        // In a real implementation, this would generate a mesh procedurally.
        // For MVP, return a bundled fallback model.
        return "models/fallback_scaffold_2x2.glb"
    }
}
