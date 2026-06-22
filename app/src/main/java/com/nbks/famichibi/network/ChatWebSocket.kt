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
    val id: String = "",
    val sender: String = "",
    val sender_id: String = "",
    val content: String = "",
    val type: String = "user",
    val timestamp: String = ""
)

data class RoomUser(
    val user_id: String,
    val user_name: String
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
    data class VoiceUserJoined(val userId: String, val userName: String) : ChatEvent()
    data class VoiceUserLeft(val userId: String) : ChatEvent()
    data class Error(val message: String) : ChatEvent()
}

object ChatWebSocket {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(WebSockets)
    }

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
        disconnect()
        currentHostUrl = hostUrl
        currentServerId = serverId
        currentRoomId = roomId
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val wsUrl = hostUrl.replace("http://", "ws://").replace("https://", "wss://")
                    client.webSocket("$wsUrl/ws/$serverId/$roomId") {
                        session = this
                        _connectionState.value = true
                        send(Json.encodeToString(JoinPayload.serializer(), JoinPayload("join", userId, userName, password)))
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    try {
                                        val msg = Json.decodeFromString(ServerMessage.serializer(), frame.readText())
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
    }

    private suspend fun handleServerMessage(msg: ServerMessage) {
        when (msg.type) {
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
            "user", "agent", "proactive" -> _events.emit(ChatEvent.Message(ChatMessage(
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
                messageId = msg.message_id ?: "", emoji = msg.emoji ?: "",
                fromUserId = msg.from_user_id ?: "", fromUserName = msg.from_user_name ?: "", timestamp = msg.timestamp ?: ""
            ))
            "voice_offer" -> _events.emit(ChatEvent.VoiceSignal(
                type = "offer", fromUserId = msg.from_user_id ?: "", toUserId = msg.to_user_id ?: "",
                sdp = msg.sdp, candidate = null, sdpMLineIndex = null, sdpMid = null
            ))
            "voice_answer" -> _events.emit(ChatEvent.VoiceSignal(
                type = "answer", fromUserId = msg.from_user_id ?: "", toUserId = msg.to_user_id ?: "",
                sdp = msg.sdp, candidate = null, sdpMLineIndex = null, sdpMid = null
            ))
            "voice_ice" -> _events.emit(ChatEvent.VoiceSignal(
                type = "ice", fromUserId = msg.from_user_id ?: "", toUserId = msg.to_user_id ?: "",
                sdp = null, candidate = msg.candidate, sdpMLineIndex = msg.sdpMLineIndex, sdpMid = msg.sdpMid
            ))
            "voice_user_joined" -> _events.emit(ChatEvent.VoiceUserJoined(msg.user_id ?: "", msg.user_name ?: ""))
            "voice_user_left" -> _events.emit(ChatEvent.VoiceUserLeft(msg.user_id ?: ""))
            "note_added", "task_added", "event_added", "photo_added" -> _events.emit(ChatEvent.NotebookUpdate(kind = msg.type))
            "error" -> _events.emit(ChatEvent.Error(msg.message ?: "Unknown error"))
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        currentRoomId = ""
        currentServerId = ""
        currentHostUrl = ""
        _roomUsers.value = emptyList()
        CoroutineScope(Dispatchers.IO).launch {
            try { session?.close() } catch (_: Exception) {}
            _connectionState.value = false
        }
    }

    fun sendMessage(sender: String, userId: String, content: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                session?.send(Json.encodeToString(MessagePayload.serializer(), MessagePayload("message", sender, userId, content)))
            } catch (_: Exception) {}
        }
    }

    fun sendReaction(messageId: String, emoji: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                session?.send(Json.encodeToString(ReactionPayload.serializer(), ReactionPayload("reaction", messageId, emoji)))
            } catch (_: Exception) {}
        }
    }

    fun sendVoiceSignal(type: String, toUserId: String, sdp: String? = null, candidate: String? = null, sdpMLineIndex: Int? = null, sdpMid: String? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                session?.send(Json.encodeToString(VoicePayload.serializer(), VoicePayload("voice_$type", toUserId, sdp, candidate, sdpMLineIndex, sdpMid)))
            } catch (_: Exception) {}
        }
    }

    @Serializable
    private data class JoinPayload(val type: String, val user_id: String, val user_name: String, val password: String = "")

    @Serializable
    private data class MessagePayload(val type: String, val sender: String, val sender_id: String, val content: String)

    @Serializable
    private data class ReactionPayload(val type: String, val message_id: String, val emoji: String)

    @Serializable
    private data class VoicePayload(
        val type: String,
        val to_user_id: String,
        val sdp: String? = null,
        val candidate: String? = null,
        val sdpMLineIndex: Int? = null,
        val sdpMid: String? = null
    )

    @Serializable
    private data class ServerMessage(
        val type: String = "",
        val id: String? = null,
        val sender: String? = null,
        val sender_id: String? = null,
        val content: String? = null,
        val message: String? = null,
        val timestamp: String? = null,
        val room_id: String? = null,
        val room_name: String? = null,
        val user_id: String? = null,
        val user_name: String? = null,
        val users: List<ServerUser>? = null,
        val from_user_id: String? = null,
        val from_user_name: String? = null,
        val to_user_id: String? = null,
        val message_id: String? = null,
        val emoji: String? = null,
        val sdp: String? = null,
        val candidate: String? = null,
        val sdpMLineIndex: Int? = null,
        val sdpMid: String? = null
    )

    @Serializable
    private data class ServerUser(val user_id: String, val user_name: String)
}
