package com.example.aibrain.measurement

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeasurementStore(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File = File(context.filesDir, "measurements_v2.json")
    private val lock = Any()

    companion object {
        const val SCHEMA_VERSION = 2
    }

    data class Point3(val x: Float, val y: Float, val z: Float)

    data class MeasurementRecord(
        val id: String,
        val type: String,
        val label: String,
        val timestamp: Long,
        val created_at: String = isoNow(),
        val distance_m: Float? = null,
        val height_m: Float? = null,
        val area_m2: Float? = null,
        val perimeter_m: Float? = null,
        val points: List<Point3>,
        val tracking_confidence: String = "UNKNOWN"
    )

    data class StoreEnvelope(
        val schema_version: Int = SCHEMA_VERSION,
        val exported_at: String = isoNow(),
        val records: List<MeasurementRecord>
    )

    fun append(measurement: Measurement, trackingConfidence: String = "UNKNOWN") {
        synchronized(lock) {
            val list = loadInternal().toMutableList()
            list.add(toRecord(measurement, trackingConfidence))
            saveInternal(list)
        }
    }

    fun clear() {
        synchronized(lock) {
            if (file.exists()) file.delete()
        }
    }

    fun exportJson(): String {
        synchronized(lock) {
            val records = loadInternal()
            return gson.toJson(StoreEnvelope(records = records))
        }
    }

    fun exportToFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(context.filesDir, "measurements_export_$ts.json")
        out.writeText(exportJson())
        return out
    }

    fun buildShareIntent(): Intent {
        val outFile = exportToFile()
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            outFile
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AI Brain — измерения")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    internal fun toRecord(
        m: Measurement,
        trackingConfidence: String = "UNKNOWN"
    ): MeasurementRecord {
        val pts = m.points.map { p -> Point3(p.pose.tx(), p.pose.ty(), p.pose.tz()) }
        return MeasurementRecord(
            id = m.id,
            type = m.type.name,
            label = m.label,
            timestamp = m.timestamp,
            created_at = isoNow(),
            distance_m = if (m.type == MeasurementType.LINEAR) m.distance else null,
            height_m = m.height,
            area_m2 = m.area,
            perimeter_m = m.perimeter,
            points = pts,
            tracking_confidence = trackingConfidence
        )
    }

    private fun loadInternal(): List<MeasurementRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val env = gson.fromJson(file.readText(), StoreEnvelope::class.java)
            env?.records ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveInternal(list: List<MeasurementRecord>) {
        try {
            file.writeText(gson.toJson(StoreEnvelope(records = list)))
        } catch (_: Exception) {
            // ignore
        }
    }
}

private fun isoNow(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
