package com.example.aibrain

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.sceneview.ar.ArSceneView
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity : AppCompatActivity() {

    private lateinit var sceneView: ArSceneView
    private lateinit var tvAiHint: TextView
    private lateinit var btnStart: Button
    private lateinit var btnAddPoint: Button
    private lateinit var btnModel: Button

    private var currentSessionId: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // ВНИМАНИЕ: Замени 100.x.x.x на реальный IP твоего ПК в Tailscale!
    private val api = Retrofit.Builder()
        .baseUrl("http://100.x.x.x:8000")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ApiService::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация компонентов
        sceneView = findViewById(R.id.sceneView)
        tvAiHint = findViewById(R.id.tv_ai_hint)
        btnStart = findViewById(R.id.btn_start)
        btnAddPoint = findViewById(R.id.btn_add_point)
        btnModel = findViewById(R.id.btn_model)

        btnStart.setOnClickListener { startSession() }
    }

    private fun startSession() {
        scope.launch {
            try {
                tvAiHint.text = "Подключение к AI Brain..."
                val response = api.startSession()

                if (response.isSuccessful) {
                    currentSessionId = response.body()?.session_id
                    tvAiHint.text = "Сессия: $currentSessionId\nОтмечайте опорные точки."

                    // Управление кнопками
                    btnStart.visibility = View.GONE
                    btnAddPoint.visibility = View.VISIBLE
                    btnModel.visibility = View.VISIBLE
                } else {
                    tvAiHint.text = "Сервер отклонил запрос: ${response.code()}"
                }
            } catch (e: Exception) {
                tvAiHint.text = "Нет связи с сервером. Проверьте Tailscale и IP."
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel() // Закрываем фоновые задачи при выходе
    }
}