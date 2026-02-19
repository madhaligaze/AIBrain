package com.example.aibrain.materials

import android.content.Context
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.MaterialInstance
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ModelRenderable
import kotlinx.coroutines.future.await

/**
 * Менеджер материалов для строительных лесов.
 */
class MaterialManager(private val context: Context) {

    private val materials = mutableMapOf<MaterialType, MaterialInstance>()

    enum class MaterialType {
        GALVANIZED_STEEL,
        WOOD_DECK,
        STRESSED_METAL,
        SAFE_METAL,
        WARNING_METAL
    }

    suspend fun init() {
        materials[MaterialType.GALVANIZED_STEEL] = MaterialFactory.makeOpaqueWithColor(
            context,
            Color(0.7f, 0.7f, 0.75f)
        ).await().apply {
            setFloat("metallic", 0.9f)
            setFloat("roughness", 0.3f)
        }

        materials[MaterialType.WOOD_DECK] = MaterialFactory.makeOpaqueWithColor(
            context,
            Color(0.6f, 0.4f, 0.2f)
        ).await().apply {
            setFloat("metallic", 0.0f)
            setFloat("roughness", 0.8f)
        }

        materials[MaterialType.SAFE_METAL] = MaterialFactory.makeOpaqueWithColor(
            context,
            Color(0.2f, 0.8f, 0.2f)
        ).await().apply {
            setFloat("metallic", 0.9f)
            setFloat("roughness", 0.3f)
        }

        materials[MaterialType.WARNING_METAL] = MaterialFactory.makeOpaqueWithColor(
            context,
            Color(0.9f, 0.9f, 0.2f)
        ).await().apply {
            setFloat("metallic", 0.9f)
            setFloat("roughness", 0.3f)
        }

        materials[MaterialType.STRESSED_METAL] = MaterialFactory.makeOpaqueWithColor(
            context,
            Color(0.9f, 0.2f, 0.2f)
        ).await().apply {
            setFloat("metallic", 0.9f)
            setFloat("roughness", 0.3f)
        }
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

    fun updateMaterial(
        renderable: ModelRenderable,
        elementType: String,
        newLoadRatio: Double
    ) {
        renderable.material = getMaterial(elementType, newLoadRatio)
    }
}
