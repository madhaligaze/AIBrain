package com.example.aibrain.scene

import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.Light
import io.github.sceneview.ar.ArSceneView

object LightingSetup {

    fun setupLighting(sceneView: ArSceneView, anchorNode: Node) {
        val sunLight = Light.builder(Light.Type.DIRECTIONAL)
            .setColor(Color(1.0f, 0.98f, 0.95f))
            .setIntensity(100_000f)
            .setShadowCastingEnabled(true)
            .build()

        Node().apply {
            setParent(anchorNode)
            localPosition = Vector3(5f, 10f, 5f)
            light = sunLight
        }

        val fillLight = Light.builder(Light.Type.POINT)
            .setColor(Color(0.7f, 0.8f, 1.0f))
            .setIntensity(10_000f)
            .setFalloffRadius(20f)
            .build()

        Node().apply {
            setParent(anchorNode)
            localPosition = Vector3(-5f, 5f, 5f)
            light = fillLight
        }

        val ambientLight = Light.builder(Light.Type.SPOTLIGHT)
            .setColor(Color(1.0f, 1.0f, 1.0f))
            .setIntensity(5_000f)
            .setInnerConeAngle(45f)
            .setOuterConeAngle(60f)
            .build()

        Node().apply {
            setParent(anchorNode)
            localPosition = Vector3(0f, 8f, 0f)
            light = ambientLight
        }
    }

    fun updateLightingForTimeOfDay(hour: Int) {
        // TODO: динамическая смена освещения по времени суток.
    }
}
