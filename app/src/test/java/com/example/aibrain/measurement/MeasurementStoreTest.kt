package com.example.aibrain.measurement

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementStoreTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Test
    fun `envelope has schema_version 2`() {
        val env = MeasurementStore.StoreEnvelope(records = emptyList())
        assertEquals(2, env.schema_version)
    }

    @Test
    fun `envelope serializes records list`() {
        val rec = sampleRecord()
        val env = MeasurementStore.StoreEnvelope(records = listOf(rec))
        val json = gson.toJson(env)
        assertTrue(json.contains("\"schema_version\": 2"))
        assertTrue(json.contains("\"type\": \"LINEAR\""))
        assertTrue(json.contains("\"distance_m\""))
    }

    @Test
    fun `golden JSON round-trip`() {
        val records = listOf(
            sampleRecord(id = "meas_001", type = "LINEAR", distM = 1.5f, label = "1.50 м"),
            sampleRecord(id = "meas_002", type = "AREA", areaM2 = 12.0f, label = "12.00 м²")
        )
        val env = MeasurementStore.StoreEnvelope(
            schema_version = 2,
            exported_at = "2026-01-01T00:00:00Z",
            records = records
        )
        val json = gson.toJson(env)

        assertTrue("schema_version missing", json.contains("\"schema_version\": 2"))
        assertTrue("meas_001 missing", json.contains("\"id\": \"meas_001\""))
        assertTrue("distance_m missing", json.contains("\"distance_m\": 1.5"))
        assertTrue("area_m2 missing", json.contains("\"area_m2\": 12.0"))

        val restored = gson.fromJson(json, MeasurementStore.StoreEnvelope::class.java)
        assertEquals(2, restored.schema_version)
        assertEquals(2, restored.records.size)
        assertEquals("meas_001", restored.records[0].id)
        assertEquals(1.5f, restored.records[0].distance_m)
        assertEquals(12.0f, restored.records[1].area_m2)
    }

    @Test
    fun `toRecord maps tracking_confidence`() {
        val store = FakeStore()
        val rec = store.makeRecord("HIGH")
        assertEquals("HIGH", rec.tracking_confidence)
    }

    @Test
    fun `points are serialized as x,y,z`() {
        val pt = MeasurementStore.Point3(1.1f, 2.2f, 3.3f)
        val json = gson.toJson(pt)
        assertTrue(json.contains("\"x\": 1.1") || json.contains("\"x\":1.1"))
    }

    private fun sampleRecord(
        id: String = "meas_test",
        type: String = "LINEAR",
        distM: Float? = 1.0f,
        areaM2: Float? = null,
        label: String = "1.00 м"
    ) = MeasurementStore.MeasurementRecord(
        id = id,
        type = type,
        label = label,
        timestamp = 1700000000000L,
        created_at = "2024-01-01T00:00:00Z",
        distance_m = distM,
        area_m2 = areaM2,
        points = listOf(MeasurementStore.Point3(0f, 0f, 0f)),
        tracking_confidence = "HIGH"
    )

    private inner class FakeStore {
        fun makeRecord(confidence: String) = MeasurementStore.MeasurementRecord(
            id = "x",
            type = "LINEAR",
            label = "1.00 м",
            timestamp = 0L,
            distance_m = 1f,
            points = emptyList(),
            tracking_confidence = confidence
        )
    }
}
