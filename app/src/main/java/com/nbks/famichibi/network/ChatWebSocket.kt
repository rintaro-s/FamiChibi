package com.nbks.famichibi.network

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.net.URLEncoder

@Serializable
data class ChatMessage(
    val id: String = "",
    val sender: String = "",
    val sender_id: String = "",
    val content: String = "",
    val type: String = "chat",
    val timestamp: String = ""
)

data class RoomUser(
    val user_id: String = "",
    val user_name: String = ""
)

sealed class ChatEvent {
    data class Message(val msg: ChatMessage) : ChatEvent()
    data class UserJoined(val userId: String, val userName: String, val users: List<RoomUser>) : ChatEvent()
    data class UserLeft(val userId: String, val userName: String, val users: List<RoomUser>) : ChatEvent()
    data class Joined(val roomId: String, val roomName: String, val userId: String, val users: List<RoomUser>) : ChatEvent()
    data class Whisper(val fromUserId: String, val fromUserName: String, val toUserId: String, val content: String, val timestamp: String) : ChatEvent()
    data class Nudge(val fromUserId: String, val fromUserName: String, val toUserId: String, val timestamp: String) : ChatEvent()
    data class Reaction(val messageId: String, val emoji: String, val fromUserId: String, val fromUserName: String, val timestamp: String) : ChatEvent()
    data class NotebookUpdate(val kind: String) : ChatEvent()
    data class VoiceSignal(val type: String, val fromUserId: String, val toUserId: String, val sdp: String?, val candidate: String?, val sdpMLineIndex: Int?, val sdpMid: String?) : ChatEvent()
    data class Error(val message: String) : ChatEvent()
}

object ChatWebSocket {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(JsonConfig.json) }
        install(WebSockets)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = kotlinx.coroutines.sync.Mutex()

    private var session: DefaultClientWebSocketSession? = null
    private var reconnectJob: Job? = null
    private val _events = MutableSharedFlow<ChatEvent>()
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()
    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState
    private val _roomUsers = MutableStateFlow<List<RoomUser>>(emptyList())
    val roomUsers: StateFlow<List<RoomUser>> = _roomUsers

    var currentRoomId: String = ""
        private set
    var currentServerId: String = ""
        private set
    var currentHostUrl: String = ""
        private set

    fun connect(hostUrl: String, serverId: String, roomId: String, userId: String, userName: String, password: String = "") {
        scope.launch {
            mutex.withLock {
                disconnectInternal()
                currentHostUrl = hostUrl
                currentServerId = serverId
                currentRoomId = roomId
                reconnectJob = scope.launch { connectionLoop(hostUrl, serverId, roomId, userId, userName, password) }
            }
        }
    }

    private suspend fun connectionLoop(
        hostUrl: String, serverId: String, roomId: String,
        userId: String, userName: String, password: String
    ) {
        val wsUrl = hostUrl.replace("http://", "ws://").replace("https://", "wss://")
        val query = "?user_id=${encode(userId)}&user_name=${encode(userName)}" +
                (if (password.isNotBlank()) "&password=${encode(password)}" else "")
        while (currentCoroutineContext().isActive) {
            try {
                client.webSocket("$wsUrl/ws/$serverId/$roomId$query") {
                    session = this
                    _connectionState.value = true
                    for (frame in incoming) {
                        if (!isActive) break
                        when (frame) {
                            is Frame.Text -> {
                                try {
                                    val msg = JsonConfig.json.decodeFromString(ServerMessage.serializer(), frame.readText())
                                    handleServerMessage(msg)
                                } catch (_: Exception) {}
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

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private suspend fun handleServerMessage(msg: ServerMessage) {
        when (msg.type) {
            "system" -> { /* system messages carry join/leave info via content, ignore user counts here */ }
            "joined" -> {
                val users = msg.users?.map { RoomUser(it.user_id, it.user_name) } ?: emptyList()
                _roomUsers.value = users
                _events.emit(ChatEvent.Joined(
                    roomId = msg.room_id ?: "",
                    roomName = msg.room_name ?: "",
                    userId = msg.user_id ?: "",
                    users = users
                ))
            }
            "user_joined" -> {
                val users = msg.users?.map { RoomUser(it.user_id, it.user_name) } ?: emptyList()
                _roomUsers.value = users
                _events.emit(ChatEvent.UserJoined(msg.user_id ?: "", msg.user_name ?: "", users))
            }
            "user_left" -> {
                val users = msg.users?.map { RoomUser(it.user_id, it.user_name) } ?: emptyList()
                _roomUsers.value = users
                _events.emit(ChatEvent.UserLeft(msg.user_id ?: "", msg.user_name ?: "", users))
            }
            "chat", "agent" -> _events.emit(ChatEvent.Message(ChatMessage(
                id = msg.id ?: "", sender = msg.sender ?: "", sender_id = msg.sender_id ?: "",
                content = msg.content ?: "", type = msg.type, timestamp = msg.timestamp ?: ""
            )))
            "whisper" -> _events.emit(ChatEvent.Whisper(
                fromUserId = msg.from_user_id ?: "", fromUserName = msg.from_user_name ?: "",
                toUserId = msg.to_user_id ?: "", content = msg.content ?: "", timestamp = msg.timestamp ?: ""
            ))
            "nudge" -> _events.emit(ChatEvent.Nudge(
                fromUserId = msg.from_user_id ?: "", fromUserName = msg.from_user_name ?: "",
                toUserId = msg.to_user_id ?: "", timestamp = msg.timestamp ?: ""
            ))
            "reaction" -> _events.emit(ChatEvent.Reaction(
                messageId = msg.target_id ?: "", emoji = msg.reaction ?: "",
                fromUserId = msg.from_user_id ?: "", fromUserName = msg.from_user_name ?: "", timestamp = msg.timestamp ?: ""
            ))
            "voice_signal" -> {
                val data = msg.data
                _events.emit(ChatEvent.VoiceSignal(
                    type = data?.type ?: "", fromUserId = data?.from ?: "", toUserId = data?.target_user_id ?: "",
                    sdp = data?.sdp, candidate = data?.candidate, sdpMLineIndex = data?.sdp_mline_index, sdpMid = data?.sdp_mid
                ))
            }
            "error" -> _events.emit(ChatEvent.Error(msg.message ?: "Unknown error"))
        }
    }

    fun disconnect() {
        scope.launch { mutex.withLock { disconnectInternal() } }
    }

    private suspend fun disconnectInternal() {
        reconnectJob?.cancelAndJoin()
        reconnectJob = null
        currentRoomId = ""
        currentServerId = ""
        currentHostUrl = ""
        _roomUsers.value = emptyList()
        try { session?.close() } catch (_: Exception) {}
        session = null
        _connectionState.value = false
    }

    fun sendMessage(content: String) {
        scope.launch {
            try {
                session?.send(JsonConfig.json.encodeToString(MessagePayload.serializer(), MessagePayload("message", content)))
            } catch (_: Exception) {}
        }
    }

    fun sendReaction(messageId: String, emoji: String) {
        scope.launch {
            try {
                session?.send(JsonConfig.json.encodeToString(ReactionPayload.serializer(), ReactionPayload("reaction", messageId, emoji)))
            } catch (_: Exception) {}
        }
    }

    fun sendWhisper(targetUserId: String, content: String) {
        scope.launch {
            try {
                session?.send(JsonConfig.json.encodeToString(WhisperPayload.serializer(), WhisperPayload("whisper", targetUserId, content)))
            } catch (_: Exception) {}
        }
    }

    fun sendNudge(targetUserId: String) {
        scope.launch {
            try {
                session?.send(JsonConfig.json.encodeToString(NudgePayload.serializer(), NudgePayload("nudge", targetUserId)))
            } catch (_: Exception) {}
        }
    }

    @Serializable
    private data class MessagePayload(val type: String, val content: String)

    @Serializable
    private data class ReactionPayload(val type: String, val target_id: String, val reaction: String)

    @Serializable
    private data class WhisperPayload(val type: String, val target_user_id: String, val content: String)

    @Serializable
    private data class NudgePayload(val type: String, val target_user_id: String)

    @Serializable
    private data class VoiceSignalData(
        val type: String? = null,
        val from: String? = null,
        val target_user_id: String? = null,
        val sdp: String? = null,
        val candidate: String? = null,
        val sdp_mid: String? = null,
        val sdp_mline_index: Int? = null
    )

    @Serializable
    private data class ServerUser(val user_id: String = "", val user_name: String = "")

    @Serializable
    private data class ServerMessage(
        val type: String = "",
        val id: String? = null,
        val sender: String? = null,
        val sender_id: String? = null,
        val content: String? = null,
        val timestamp: String? = null,
        val room_id: String? = null,
        val room_name: String? = null,
        val user_id: String? = null,
        val user_name: String? = null,
        val users: List<ServerUser>? = null,
        val from_user_id: String? = null,
        val from_user_name: String? = null,
        val to_user_id: String? = null,
        val target_id: String? = null,
        val reaction: String? = null,
        val message: String? = null,
        val data: VoiceSignalData? = null
    )
}
