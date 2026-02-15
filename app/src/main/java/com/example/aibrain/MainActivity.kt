package com.example.aibrain

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.Config
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.ar.node.ArModelNode
import io.github.sceneview.ar.node.PlacementMode
import io.github.sceneview.math.Position
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var sceneView: ArSceneView
    private lateinit var tvAiHint: TextView
    private lateinit var btnStart: Button
    private lateinit var btnAddPoint: Button
    private lateinit var btnModel: Button

    private var currentSessionId: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var isStreaming = false

    // Список точек, которые поставил пользователь (x, y, z)
    private val userMarkers = mutableListOf<Map<String, Float>>()

    // НАСТРОЙКА СЕТИ (Проверь IP!)
    private val api = Retrofit.Builder()
        .baseUrl("http://192.168.1.148:8000")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ApiService::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sceneView = findViewById(R.id.sceneView)
        tvAiHint = findViewById(R.id.tv_ai_hint)
        btnStart = findViewById(R.id.btn_start)
        btnAddPoint = findViewById(R.id.btn_add_point)
        btnModel = findViewById(R.id.btn_model)

        // Настройка AR сцены
        sceneView.configureSession { session, config ->
            config.focusMode = Config.FocusMode.AUTO
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        }

        // Кнопка СТАРТ
        btnStart.setOnClickListener { startSession() }

        // Кнопка ТОЧКА ОПОРЫ (ставит виртуальный якорь)
        btnAddPoint.setOnClickListener {
            placeAnchor()
        }

        // Кнопка МОДЕЛИРОВАТЬ
        btnModel.setOnClickListener {
            stopStreaming()
            requestModeling()
        }
    }

    private fun startSession() {
        scope.launch {
            try {
                tvAiHint.text = "Подключение..."
                val response = api.startSession()
                if (response.isSuccessful) {
                    currentSessionId = response.body()?.session_id
                    tvAiHint.text = "Сессия активна. Ищите пол и ставьте точки."

                    // Меняем кнопки
                    btnStart.visibility = View.GONE
                    btnAddPoint.visibility = View.VISIBLE
                    btnModel.visibility = View.VISIBLE

                    // Запускаем отправку видео
                    startStreaming()
                } else {
                    tvAiHint.text = "Ошибка сервера: ${response.code()}"
                }
            } catch (e: Exception) {
                tvAiHint.text = "Нет связи: ${e.message}"
            }
        }
    }

    // ЛОГИКА СТРИМИНГА: Отправляем кадр каждые 1000мс
    private fun startStreaming() {
        isStreaming = true
        scope.launch(Dispatchers.IO) {
            while (isStreaming && currentSessionId != null) {
                val frame = sceneView.arSession?.update() ?: continue
                val cameraImage = try { frame.acquireCameraImage() } catch (e: Exception) { null }

                if (cameraImage != null) {
                    // 1. Конвертируем картинку
                    val base64Image = ImageUtils.convertYuvToJpegBase64(cameraImage)
                    cameraImage.close() // Обязательно закрываем!

                    // 2. Берем позицию телефона (Pose)
                    val pose = frame.camera.pose
                    val poseList = listOf(
                        pose.tx(), pose.ty(), pose.tz(),
                        pose.qx(), pose.qy(), pose.qz(), pose.qw()
                    )

                    // 3. Формируем пакет данных
                    val payload = mapOf(
                        "image" to base64Image,
                        "pose" to poseList,
                        "markers" to userMarkers
                    )

                    // 4. Отправляем на сервер
                    try {
                        val response = api.streamData(currentSessionId!!, payload)
                        withContext(Dispatchers.Main) {
                            if (response.isSuccessful) {
                                // Показываем подсказки от ИИ (если есть)
                                val hints = response.body()?.hints
                                if (!hints.isNullOrEmpty()) {
                                    tvAiHint.text = "ИИ: ${hints.values.first().joinToString()}"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ошибки сети игнорируем в стриме, чтобы не фризить UI
                    }
                }
                delay(1000) // Пауза 1 секунда
            }
        }
    }

    private fun stopStreaming() {
        isStreaming = false
    }

    // Ставим 3D-точку в пространстве
    private fun placeAnchor() {
        // Берем центр экрана
        val frame = sceneView.arSession?.update() ?: return
        val hitResult = frame.hitTest(
            sceneView.width / 2f,
            sceneView.height / 2f
        ).firstOrNull()

        if (hitResult != null) {
            // Создаем якорь ARCore
            val anchor = hitResult.createAnchor()

            // Визуализация: добавляем простую сферу (как модель)
            // Примечание: тут пока просто ставим точку без 3D модели,
            // так как у нас нет файла sphere.glb, но координаты сохраняем!

            val pose = anchor.pose
            val markerData = mapOf(
                "x" to pose.tx(),
                "y" to pose.ty(),
                "z" to pose.tz()
            )
            userMarkers.add(markerData)

            Toast.makeText(this, "Точка добавлена!", Toast.LENGTH_SHORT).show()
            tvAiHint.text = "Точек: ${userMarkers.size}"
        } else {
            Toast.makeText(this, "Подойдите ближе к поверхности", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestModeling() {
        scope.launch {
            tvAiHint.text = "ИИ думает..."
            try {
                val response = api.startModeling(currentSessionId!!)
                if (response.isSuccessful) {
                    val count = response.body()?.options?.size ?: 0
                    tvAiHint.text = "Готово! Вариантов: $count"
                } else {
                    tvAiHint.text = "Ошибка моделирования"
                }
            } catch (e: Exception) {
                tvAiHint.text = "Сбой: ${e.message}"
            }
        }
    }
}