package com.example.aibrain

import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    @POST("/session/start")
    suspend fun startSession(): Response<SessionResponse>

    @POST("/session/stream/{session_id}")
    suspend fun streamData(
        @Path("session_id") sessionId: String,
        @Body body: Map<String, Any>
    ): Response<HintResponse>

    @POST("/session/model/{session_id}")
    suspend fun startModeling(
        @Path("session_id") sessionId: String
    ): Response<ModelingResponse>
}

// Модели данных для ответов сервера
data class SessionResponse(val session_id: String, val status: String)
data class HintResponse(val hints: Map<String, List<String>>)
data class ModelingResponse(val status: String, val options: List<Any>)