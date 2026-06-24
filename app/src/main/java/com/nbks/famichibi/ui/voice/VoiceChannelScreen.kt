package com.nbks.famichibi.ui.voice

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.nbks.famichibi.data.HostConfig
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.data.ServerMembership
import com.nbks.famichibi.network.ApiClient
import com.nbks.famichibi.network.ChatWebSocket
import com.nbks.famichibi.network.WebRtcAudioManager
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import com.nbks.famichibi.network.JsonConfig
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

@Serializable
data class VoiceUser(val user_id: String = "", val user_name: String = "")

@Serializable
data class VoiceJoinResponse(val users: List<VoiceUser> = emptyList())

@Serializable
data class VoiceSignal(
    val type: String,
    val from: String,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdp_mid: String? = null,
    val sdp_mline_index: Int? = null
)

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VoiceChannelScreen(
    navController: NavController,
    prefs: PreferencesRepository,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    var userId by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var membership by remember { mutableStateOf<ServerMembership?>(null) }
    var host by remember { mutableStateOf<HostConfig?>(null) }
    var channelId by remember { mutableStateOf("") }
    var channelName by remember { mutableStateOf("ボイスチャンネル") }
    var users by remember { mutableStateOf(listOf<VoiceUser>()) }
    var connected by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var joined by remember { mutableStateOf(false) }

    val webRtc = remember { WebRtcAudioManager(context) }

    suspend fun loadState() {
        userId = prefs.userId.first()
        userName = prefs.userName.first()
        val memberships = prefs.serverMemberships.first()
        membership = memberships.lastOrNull()
        channelId = prefs.activeChannelId.first()
        val hosts = prefs.hosts.first()
        host = hosts.find { it.id == membership?.hostId }
    }

    suspend fun basePath() = "${host?.url}/s/${membership?.serverId}/channels/$channelId"
    suspend fun name() = membership?.nickname?.ifEmpty { userName } ?: userName

    suspend fun sendSignal(target: String, type: String, sdp: String? = null, candidate: String? = null, sdpMid: String? = null, sdpMLineIndex: Int? = null) {
        ApiClient.postForm(
            basePath() + "/voice/${type}", userId, name(),
            Parameters.build {
                append("target_user_id", target)
                sdp?.let { append("sdp", it) }
                candidate?.let { append("candidate", it) }
                sdpMid?.let { append("sdp_mid", it) }
                sdpMLineIndex?.let { append("sdp_mline_index", it.toString()) }
            }
        )
    }

    suspend fun join() {
        loadState()
        if (!micPermission.status.isGranted) { micPermission.launchPermissionRequest(); return }
        val h = host ?: return
        val m = membership ?: return
        try {
            val res = ApiClient.postForm(
                "${h.url}/s/${m.serverId}/channels/$channelId/voice/join", userId, name(),
                Parameters.build { }
            )
            if (res.status == HttpStatusCode.OK) {
                val data = JsonConfig.json.decodeFromString<VoiceJoinResponse>(res.bodyAsText())
                users = data.users
                joined = true
                connected = true
                webRtc.initialize()
                webRtc.onOfferNeeded = { target, sdp -> scope.launch { sendSignal(target, "offer", sdp.description) } }
                webRtc.onAnswerNeeded = { target, sdp -> scope.launch { sendSignal(target, "answer", sdp.description) } }
                webRtc.onIceCandidate = { target, candidate ->
                    scope.launch {
                        sendSignal(target, "ice", candidate = candidate.sdp, sdpMid = candidate.sdpMid, sdpMLineIndex = candidate.sdpMLineIndex)
                    }
                }
                webRtc.startFallbackAudio()
                data.users.filter { it.user_id != userId }.forEach { u ->
                    webRtc.createPeerConnection(u.user_id, true, object : org.webrtc.PeerConnection.Observer {
                        override fun onSignalingChange(state: org.webrtc.PeerConnection.SignalingState?) {}
                        override fun onIceConnectionChange(state: org.webrtc.PeerConnection.IceConnectionState?) {}
                        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                        override fun onIceGatheringChange(state: org.webrtc.PeerConnection.IceGatheringState?) {}
                        override fun onIceCandidate(candidate: IceCandidate?) {}
                        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                        override fun onAddStream(stream: org.webrtc.MediaStream?) {}
                        override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
                        override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
                        override fun onRenegotiationNeeded() {}
                        override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {}
                        override fun onRemoveTrack(receiver: org.webrtc.RtpReceiver?) {}
                    })
                }
            } else {
                snackbarHostState.showSnackbar("ボイスチャンネルに参加できません")
            }
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("ボイスチャンネルに参加できません")
        }
    }

    suspend fun leave() {
        joined = false
        val h = host; val m = membership; val cid = channelId
        if (h != null && m != null && cid.isNotEmpty()) {
            try { ApiClient.post("${h.url}/s/${m.serverId}/channels/$cid/voice/leave", userId, name()) } catch (_: Exception) {}
        }
        webRtc.stop()
        ChatWebSocket.disconnect()
    }

    LaunchedEffect(Unit) {
        join()
        while (joined) {
            try {
                val res = ApiClient.get(basePath() + "/voice/poll", userId, name())
                if (res.status == HttpStatusCode.OK) {
                    val signal = JsonConfig.json.decodeFromString<VoiceSignal>(res.bodyAsText())
                    if (signal.type.isNotBlank() && signal.from != userId) {
                        when (signal.type) {
                            "offer" -> signal.sdp?.let { webRtc.handleOffer(signal.from, SessionDescription(SessionDescription.Type.OFFER, it)) }
                            "answer" -> signal.sdp?.let { webRtc.handleAnswer(signal.from, SessionDescription(SessionDescription.Type.ANSWER, it)) }
                            "ice" -> signal.candidate?.let { webRtc.handleIceCandidate(signal.from, IceCandidate(signal.sdp_mid, signal.sdp_mline_index ?: 0, it)) }
                        }
                    }
                }
            } catch (_: Exception) {}
            delay(1200)
        }
    }

    LaunchedEffect(isMuted) { webRtc.setMuted(isMuted) }

    DisposableEffect(Unit) {
        onDispose { scope.launch { leave() } }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(channelName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${users.size}人在室 ${if (connected) "" else "· 未接続"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { scope.launch { leave(); navController.navigate("server") { popUpTo("server") { inclusive = true } } } }) { Text("退出") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (users.isEmpty()) {
                Text("参加者がいません", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyRow(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(users) { u ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(u.user_name.take(1), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(u.user_name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "ボイスチャット: WebRTC接続を確立中です。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledIconButton(onClick = { isMuted = !isMuted }) {
                    Icon(if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = null)
                }
                Button(onClick = { scope.launch { leave(); navController.navigate("server") { popUpTo("server") { inclusive = true } } } }) { Text("退出") }
            }
        }
    }
}
