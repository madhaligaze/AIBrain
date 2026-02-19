package com.example.aibrain.materials

import android.content.Context
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ModelRenderable
import kotlinx.coroutines.future.await

/**
 * Менеджер материалов для строительных лесов.
 */
class MaterialManager(private val context: Context) {

    private val materials = mutableMapOf<MaterialType, com.google.ar.sceneform.rendering.Material>()

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

    fun getMaterial(elementType: String, loadRatio: Double = 0.0): com.google.ar.sceneform.rendering.Material {
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
        val material = getMaterial(elementType, newLoadRatio)

        // SceneView/Sceneform forks differ in how materials are applied:
        // - some expose ModelRenderable.material (Material)
        // - some expose ModelRenderable.material (MaterialInstance)
        // To keep the app buildable across these variants, apply via reflection.
        try {
            // Prefer a direct setter: setMaterial(...)
            val setMaterial = renderable.javaClass.methods.firstOrNull {
                it.name == "setMaterial" && it.parameterTypes.size == 1
            }
            if (setMaterial != null) {
                val paramType = setMaterial.parameterTypes[0]
                when {
                    paramType.isInstance(material) -> {
                        setMaterial.invoke(renderable, material)
                        return
                    }
                    else -> {
                        // Try to extract a "materialInstance"-like object from Material
                        val instance = material.javaClass.methods.firstOrNull { m ->
                            m.parameterTypes.isEmpty() &&
                            (m.name == "getMaterialInstance" || m.name == "materialInstance" || m.name == "getInstance")
                        }?.invoke(material)

                        if (instance != null && paramType.isInstance(instance)) {
                            setMaterial.invoke(renderable, instance)
                            return
                        }
                    }
                }
            }

            // Alternate: setMaterialInstance(...)
            val setMaterialInstance = renderable.javaClass.methods.firstOrNull {
                it.name == "setMaterialInstance" && it.parameterTypes.size == 1
            }
            if (setMaterialInstance != null && setMaterialInstance.parameterTypes[0].isInstance(material)) {
                setMaterialInstance.invoke(renderable, material)
                return
            }
        } catch (_: Throwable) {
            // No-op - material update is best-effort.
        }
    }
}
