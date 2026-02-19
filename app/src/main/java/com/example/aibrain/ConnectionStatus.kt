package com.example.aibrain

/**
 * Единый тип состояния соединения (логика + UI).
 */
enum class ConnectionStatus {
    UNKNOWN,
    ONLINE,
    RECONNECTING,
    OFFLINE
}

data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.UNKNOWN,
    val detail: String = ""
)
