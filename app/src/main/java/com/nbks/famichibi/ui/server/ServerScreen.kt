package com.nbks.famichibi.ui.server

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nbks.famichibi.data.HostConfig
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.data.ServerMembership
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class ChannelInfo(
    val id: String,
    val name: String,
    val type: String,
    val has_password: Boolean,
    val user_count: Int
)

@Serializable
data class ServerDetail(
    val id: String,
    val name: String,
    val icon: String,
    val welcome_message: String,
    val has_password: Boolean,
    val member_count: Int,
    val channels: List<ChannelInfo>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    navController: NavController,
    prefs: PreferencesRepository,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    var membership by remember { mutableStateOf<ServerMembership?>(null) }
    var host by remember { mutableStateOf<HostConfig?>(null) }
    var server by remember { mutableStateOf<ServerDetail?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var newChannelName by remember { mutableStateOf("") }
    var newChannelPassword by remember { mutableStateOf("") }
    var joinPassword by remember { mutableStateOf("") }
    var joiningChannel by remember { mutableStateOf<ChannelInfo?>(null) }

    val httpClient = remember { HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } } }

    suspend fun refresh() {
        val memberships = prefs.serverMemberships.first()
        membership = memberships.lastOrNull()
        val hosts = prefs.hosts.first()
        host = hosts.find { it.id == membership?.hostId }
        val h = host ?: return
        val s = membership ?: return
        try {
            server = Json.decodeFromString(httpClient.get("${h.url}/s/${s.serverId}").bodyAsText())
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("サーバー情報の取得に失敗しました")
        }
    }

    LaunchedEffect(Unit) { refresh() }

    suspend fun joinChannel(ch: ChannelInfo, password: String) {
        val h = host ?: return
        val m = membership ?: return
        try {
            val response = httpClient.submitForm(
                url = "${h.url}/s/${m.serverId}/channels/${ch.id}/join",
                formParameters = Parameters.build {
                    append("user_id", prefs.userId.first())
                    append("user_name", m.nickname)
                    append("password", password)
                }
            )
            if (response.status == HttpStatusCode.OK) {
                prefs.setActiveChannelId(ch.id)
                if (ch.type == "voice") navController.navigate("voice")
                else navController.navigate("channel")
            } else {
                snackbarHostState.showSnackbar("参加に失敗しました")
            }
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("参加に失敗しました")
        }
    }

    suspend fun createChannel() {
        val h = host ?: return
        val m = membership ?: return
        try {
            httpClient.submitForm(
                url = "${h.url}/s/${m.serverId}/channels",
                formParameters = Parameters.build {
                    append("name", newChannelName)
                    append("channel_type", "text")
                    append("password", newChannelPassword)
                }
            )
            newChannelName = ""
            newChannelPassword = ""
            showCreate = false
            refresh()
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("作成に失敗しました")
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(server?.name ?: "サーバー", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Button(onClick = { showCreate = true }) { Text("チャンネル作成") }
        }
        if (!server?.welcome_message.isNullOrBlank()) {
            Text(server?.welcome_message ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        val metaText = "${server?.member_count ?: 0}メンバー / ${server?.channels?.size ?: 0}チャンネル"
        Text(metaText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        val textChannels = server?.channels?.filter { it.type != "voice" } ?: emptyList()
        val voiceChannels = server?.channels?.filter { it.type == "voice" } ?: emptyList()

        if (textChannels.isEmpty() && voiceChannels.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("まだチャンネルがありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (textChannels.isNotEmpty()) {
            Text("テキストチャンネル", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            textChannels.forEach { ch ->
                ChannelRow(ch) {
                    joiningChannel = ch
                    if (!ch.has_password) scope.launch { joinChannel(ch, "") }
                }
            }
        }

        if (voiceChannels.isNotEmpty()) {
            Text("ボイスチャンネル", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            voiceChannels.forEach { ch ->
                ChannelRow(ch) {
                    joiningChannel = ch
                    if (!ch.has_password) scope.launch { joinChannel(ch, "") }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("チャンネル作成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = newChannelName, onValueChange = { newChannelName = it }, label = { Text("名前") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newChannelPassword, onValueChange = { newChannelPassword = it }, label = { Text("パスワード（空欄で公開）") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { createChannel() } }, enabled = newChannelName.isNotBlank()) { Text("作成") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("キャンセル") } }
        )
    }

    if (joiningChannel != null && joiningChannel!!.has_password) {
        AlertDialog(
            onDismissRequest = { joiningChannel = null },
            title = { Text("${joiningChannel!!.name}に参加") },
            text = {
                OutlinedTextField(value = joinPassword, onValueChange = { joinPassword = it }, label = { Text("パスワード") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { joinChannel(joiningChannel!!, joinPassword); joiningChannel = null; joinPassword = "" } }) { Text("参加") }
            },
            dismissButton = { TextButton(onClick = { joiningChannel = null }) { Text("キャンセル") } }
        )
    }
}

@Composable
private fun ChannelRow(channel: ChannelInfo, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(channel.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("${channel.user_count}人在室", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (channel.has_password) {
                    Icon(Icons.Default.Lock, contentDescription = "鍵付き", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("鍵付き", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onClick) { Text("参加") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}
