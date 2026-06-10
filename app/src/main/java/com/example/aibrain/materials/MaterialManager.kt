package com.example.aibrain.materials

import android.graphics.Color
import com.google.android.filament.MaterialInstance
import io.github.sceneview.loaders.MaterialLoader

/**
 * Scaffold material palette. Migrated to SceneView/Filament: materials are
 * [MaterialInstance]s created up-front via [MaterialLoader.createColorInstance]
 * (color int + PBR metallic/roughness/reflectance). Construct with
 * `sceneView.materialLoader` once the AR view exists, then call [init].
 */
class MaterialManager(private val materialLoader: MaterialLoader) {

    private val materials = mutableMapOf<MaterialType, MaterialInstance>()

    enum class MaterialType {
        GALVANIZED_STEEL,
        WOOD_DECK,
        STRESSED_METAL,
        SAFE_METAL,
        WARNING_METAL
    }

    fun init() {
        materials[MaterialType.GALVANIZED_STEEL] =
            materialLoader.createColorInstance(Color.rgb(179, 179, 191), 0.9f, 0.3f, 0f)
        materials[MaterialType.WOOD_DECK] =
            materialLoader.createColorInstance(Color.rgb(153, 102, 51), 0.0f, 0.8f, 0f)
        materials[MaterialType.SAFE_METAL] =
            materialLoader.createColorInstance(Color.rgb(51, 204, 51), 0.9f, 0.3f, 0f)
        materials[MaterialType.WARNING_METAL] =
            materialLoader.createColorInstance(Color.rgb(230, 230, 51), 0.9f, 0.3f, 0f)
        materials[MaterialType.STRESSED_METAL] =
            materialLoader.createColorInstance(Color.rgb(230, 51, 51), 0.9f, 0.3f, 0f)
    }

    fun getMaterial(elementType: String, loadRatio: Double = 0.0): MaterialInstance {
        if (elementType == "deck" || elementType == "platform") {
            return materials[MaterialType.WOOD_DECK] ?: materials.getValue(MaterialType.GALVANIZED_STEEL)
        }
        return when {
            loadRatio >= 0.9 -> materials[MaterialType.STRESSED_METAL]
            loadRatio >= 0.6 -> materials[MaterialType.WARNING_METAL]
            else -> materials[MaterialType.SAFE_METAL]
        } ?: materials.getValue(MaterialType.GALVANIZED_STEEL)
    }
}
