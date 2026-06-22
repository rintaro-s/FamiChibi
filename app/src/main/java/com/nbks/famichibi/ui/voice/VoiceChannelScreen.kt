package com.nbks.famichibi.ui.voice

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nbks.famichibi.data.HostConfig
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.data.ServerMembership
import com.nbks.famichibi.network.ChatEvent
import com.nbks.famichibi.network.ChatWebSocket
import com.nbks.famichibi.network.RoomUser
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceChannelScreen(
    navController: NavController,
    prefs: PreferencesRepository,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    var userId by remember { mutableStateOf("") }
    var membership by remember { mutableStateOf<ServerMembership?>(null) }
    var host by remember { mutableStateOf<HostConfig?>(null) }
    var channelId by remember { mutableStateOf("") }
    var channelName by remember { mutableStateOf("ボイスチャンネル") }
    var users by remember { mutableStateOf(listOf<RoomUser>()) }
    var connected by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var joined by remember { mutableStateOf(false) }

    val httpClient = remember { HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } } }

    suspend fun join() {
        userId = prefs.userId.first()
        val memberships = prefs.serverMemberships.first()
        membership = memberships.lastOrNull()
        channelId = prefs.activeChannelId.first()
        val hosts = prefs.hosts.first()
        host = hosts.find { it.id == membership?.hostId }
        val h = host ?: return
        val m = membership ?: return
        try {
            httpClient.submitForm(
                url = "${h.url}/s/${m.serverId}/channels/$channelId/voice/join",
                formParameters = Parameters.build {
                    append("user_id", userId)
                    append("user_name", m.nickname)
                }
            )
            joined = true
            ChatWebSocket.connect(h.url, m.serverId, channelId, userId, m.nickname, "")
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("ボイスチャンネルに参加できません")
        }
    }

    LaunchedEffect(Unit) { join() }

    LaunchedEffect(Unit) {
        ChatWebSocket.events.collect { event ->
            when (event) {
                is ChatEvent.Joined -> { users = event.users; connected = true }
                is ChatEvent.UserJoined, is ChatEvent.UserLeft -> users = ChatWebSocket.roomUsers.value
                is ChatEvent.VoiceUserJoined -> if (users.none { it.user_id == event.userId }) users = users + RoomUser(event.userId, event.userName)
                is ChatEvent.VoiceUserLeft -> users = users.filter { it.user_id != event.userId }
                is ChatEvent.Error -> snackbarHostState.showSnackbar(event.message)
                else -> {}
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val h = host; val m = membership; val cid = channelId
            if (h != null && m != null && cid.isNotEmpty() && joined) {
                scope.launch {
                    try {
                        httpClient.submitForm("${h.url}/s/${m.serverId}/channels/$cid/voice/leave", formParameters = Parameters.build { append("user_id", userId) })
                    } catch (_: Exception) {}
                }
            }
            ChatWebSocket.disconnect()
        }
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
            TextButton(onClick = { navController.navigate("server") { popUpTo("server") { inclusive = true } } }) { Text("退出") }
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
                "ボイスチャットの完全な音声機能はWebRTCライブラリの追加が必要です。現在は参加者リストとシグナリングを表示しています。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledIconButton(onClick = { isMuted = !isMuted }) {
                    Icon(if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = null)
                }
                Button(onClick = { navController.navigate("server") { popUpTo("server") { inclusive = true } } }) { Text("退出") }
            }
        }
    }
}
