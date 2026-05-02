package com.nbks.famichibi.network

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChatMessage(
    val id: String,
    val sender: String,
    val content: String,
    val type: String,
    val timestamp: String
)

class ChatWebSocket {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(WebSockets)
    }

    private var session: DefaultClientWebSocketSession? = null
    private var reconnectJob: Job? = null
    private val _messages = MutableSharedFlow<ChatMessage>()
    val messages: SharedFlow<ChatMessage> = _messages.asSharedFlow()
    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    fun connect(serverUrl: String, roomId: String) {
        reconnectJob?.cancel()
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://")
                    client.webSocket("$wsUrl/ws/$roomId") {
                        session = this
                        _connectionState.value = true
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    try {
                                        val msg = Json.decodeFromString<ChatMessage>(text)
                                        _messages.emit(msg)
                                    } catch (_: Exception) {
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    _connectionState.value = false
                    session = null
                }
                delay(3000)
            }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        CoroutineScope(Dispatchers.IO).launch {
            try {
                session?.close()
            } catch (_: Exception) {
            }
            _connectionState.value = false
        }
    }

    fun sendMessage(sender: String, content: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = Json { ignoreUnknownKeys = true }
                session?.send(
                    json.encodeToString(
                        MessagePayload.serializer(),
                        MessagePayload("message", sender, content)
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    @Serializable
    private data class MessagePayload(
        val type: String,
        val sender: String,
        val content: String
    )
}
