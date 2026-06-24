package com.nbks.famichibi.ui.server

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nbks.famichibi.data.HostConfig
import java.util.UUID
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.data.ServerMembership
import com.nbks.famichibi.network.ApiClient
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.Parameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import com.nbks.famichibi.network.JsonConfig

@Serializable
data class ChannelInfo(
    val id: String = "",
    val name: String = "",
    val type: String = "text",
    val visibility: String = "public",
    val ai_enabled: Boolean = true,
    val has_password: Boolean = false,
    val user_count: Int = 0,
    val can_manage: Boolean = false
)

@Serializable
data class JoinResult(val joined: Boolean = false, val user_id: String = "", val role: String = "")

@Serializable
data class ServerDetail(
    val id: String = "",
    val name: String = "",
    val icon: String = "",
    val welcome_message: String = "",
    val has_password: Boolean = false,
    val member_count: Int = 0,
    val my_role: String? = null,
    val my_user_id: String? = null,
    val permissions: List<String> = emptyList(),
    val channels: List<ChannelInfo> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    navController: NavController,
    prefs: PreferencesRepository,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    var userId by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var host by remember { mutableStateOf<HostConfig?>(null) }
    var membership by remember { mutableStateOf<ServerMembership?>(null) }
    var server by remember { mutableStateOf<ServerDetail?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var newChannelName by remember { mutableStateOf("") }
    var newChannelPassword by remember { mutableStateOf("") }
    var newChannelVisibility by remember { mutableStateOf("public") }
    var newChannelType by remember { mutableStateOf("text") }
    var newChannelAi by remember { mutableStateOf(false) }
    var joinPassword by remember { mutableStateOf("") }
    var joiningChannel by remember { mutableStateOf<ChannelInfo?>(null) }
    var showServerPasswordDialog by remember { mutableStateOf(false) }
    var pendingServerPassword by remember { mutableStateOf(false) }

    suspend fun loadState() {
        var uid = prefs.userId.first()
        if (uid.isBlank()) {
            uid = UUID.randomUUID().toString()
            prefs.setUserId(uid)
        }
        userId = uid
        userName = prefs.userName.first().ifBlank { "お兄ちゃん" }
        val hosts = prefs.hosts.first()
        val sid = prefs.activeServerId.first()
        val hid = prefs.activeHostId.first()
        host = hosts.find { it.id == hid }
        val memberships = prefs.serverMemberships.first()
        membership = memberships.find { it.serverId == sid && it.hostId == hid }
    }

    suspend fun refresh() {
        loadState()
        val h = host ?: return
        val m = membership ?: return
        try {
            val res = ApiClient.get("${h.url}/s/${m.serverId}", userId, m.nickname.ifEmpty { userName })
            if (res.status == HttpStatusCode.OK) {
                server = JsonConfig.json.decodeFromString(res.bodyAsText())
            } else {
                snackbarHostState.showSnackbar("サーバー情報の取得に失敗しました")
            }
        } catch (_: Exception) {
            snackbarHostState.showSnackbar("サーバー情報の取得に失敗しました")
        }
    }

    suspend fun ensureJoined(password: String = "") {
        val h = host ?: return
        val m = membership ?: return
        val name = m.nickname.ifEmpty { userName }
        val res = ApiClient.get("${h.url}/s/${m.serverId}", userId, name)
        if (res.status != HttpStatusCode.OK) return
        val info = JsonConfig.json.decodeFromString<ServerDetail>(res.bodyAsText())
        if (info.my_role == null) {
            if (password.isEmpty() && info.has_password) {
                pendingServerPassword = true
                showServerPasswordDialog = true
                return
            }
            val joinRes = ApiClient.postForm(
                "${h.url}/s/${m.serverId}/join", userId, name,
                Parameters.build { append("password", password) }
            )
            if (joinRes.status == HttpStatusCode.OK) {
                val memberships = prefs.serverMemberships.first()
                val joinResult = try { JsonConfig.json.decodeFromString<JoinResult>(joinRes.bodyAsText()) } catch (_: Exception) { null }
                val newMembership = m.copy(serverName = info.name, role = joinResult?.role ?: m.role)
                prefs.setServerMemberships(
                    if (memberships.any { it.serverId == m.serverId && it.hostId == m.hostId }) {
                        memberships.map { if (it.serverId == m.serverId && it.hostId == m.hostId) newMembership else it }
                    } else memberships + newMembership
                )
            } else {
                snackbarHostState.showSnackbar("サーバーに参加できません")
            }
        }
    }

    LaunchedEffect(Unit) {
        loadState()
        ensureJoined()
        if (!showServerPasswordDialog) refresh()
    }

    suspend fun joinChannel(ch: ChannelInfo, password: String) {
        val h = host ?: return
        val m = membership ?: return
        val name = m.nickname.ifEmpty { userName }
        prefs.setActiveChannelId(ch.id)
        if (ch.type == "voice") {
            try {
                val response = ApiClient.postForm(
                    "${h.url}/s/${m.serverId}/channels/${ch.id}/voice/join", userId, name,
                    Parameters.build { append("password", password) }
                )
                if (response.status == HttpStatusCode.OK) {
                    navController.navigate("voice")
                } else {
                    snackbarHostState.showSnackbar("参加に失敗しました")
                }
            } catch (_: Exception) {
                snackbarHostState.showSnackbar("参加に失敗しました")
            }
        } else {
            navController.navigate("channel")
        }
    }

    suspend fun createChannel() {
        val h = host ?: return
        val m = membership ?: return
        val name = m.nickname.ifEmpty { userName }
        try {
            ApiClient.postForm(
                "${h.url}/s/${m.serverId}/channels", userId, name,
                Parameters.build {
                    append("name", newChannelName)
                    append("channel_type", newChannelType)
                    append("password", newChannelPassword)
                    append("visibility", newChannelVisibility)
                    append("ai_enabled", if (newChannelAi) "true" else "false")
                }
            )
            newChannelName = ""; newChannelPassword = ""; newChannelVisibility = "public"; newChannelType = "text"; newChannelAi = false
            showCreate = false
            refresh()
        } catch (_: Exception) {
            snackbarHostState.showSnackbar("作成に失敗しました")
        }
    }

    val canCreateChannel = server?.permissions?.contains("manage_channels") == true || server?.my_role == "owner"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(server?.name ?: "サーバー", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${server?.member_count ?: 0}人 · ${server?.channels?.size ?: 0}CH", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
                actions = {
                    IconButton(onClick = { navController.navigate("server_settings") }) { Icon(Icons.Default.Settings, contentDescription = "サーバー設定") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            if (canCreateChannel) {
                FloatingActionButton(onClick = { showCreate = true }) {
                    Icon(Icons.Default.Add, contentDescription = "チャンネル作成")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (!server?.welcome_message.isNullOrBlank()) {
                Text(
                    server?.welcome_message ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            val textChannels = server?.channels?.filter { it.type != "voice" } ?: emptyList()
            val voiceChannels = server?.channels?.filter { it.type == "voice" } ?: emptyList()

            if (textChannels.isEmpty() && voiceChannels.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("まだチャンネルがありません\n右下の＋から作成できます", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (textChannels.isNotEmpty()) {
                SectionHeader("テキスト")
                textChannels.forEach { ch ->
                    ChannelRow(ch) {
                        joiningChannel = ch
                        if (!ch.has_password) scope.launch { joinChannel(ch, "") }
                    }
                }
            }

            if (voiceChannels.isNotEmpty()) {
                SectionHeader("ボイス")
                voiceChannels.forEach { ch ->
                    ChannelRow(ch) {
                        joiningChannel = ch
                        if (!ch.has_password) scope.launch { joinChannel(ch, "") }
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("チャンネル作成", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(value = newChannelName, onValueChange = { newChannelName = it }, label = { Text("名前") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = newChannelType == "text", onClick = { newChannelType = "text" }, label = { Text("テキスト") })
                        FilterChip(selected = newChannelType == "voice", onClick = { newChannelType = "voice" }, label = { Text("ボイス") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = newChannelVisibility == "public", onClick = { newChannelVisibility = "public" }, label = { Text("公開") })
                        FilterChip(selected = newChannelVisibility == "private", onClick = { newChannelVisibility = "private" }, label = { Text("非公開") })
                    }
                    OutlinedTextField(value = newChannelPassword, onValueChange = { newChannelPassword = it }, label = { Text("パスワード（空欄で公開）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = newChannelAi, onCheckedChange = { newChannelAi = it })
                        Text("AI応答を有効にする", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { scope.launch { createChannel() } }, enabled = newChannelName.isNotBlank()) { Text("作成") } },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("キャンセル") } }
        )
    }

    if (joiningChannel != null && joiningChannel!!.has_password) {
        AlertDialog(
            onDismissRequest = { joiningChannel = null },
            title = { Text("${joiningChannel!!.name}に参加", style = MaterialTheme.typography.titleMedium) },
            text = { OutlinedTextField(value = joinPassword, onValueChange = { joinPassword = it }, label = { Text("パスワード") }, modifier = Modifier.fillMaxWidth(), singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { joinChannel(joiningChannel!!, joinPassword); joiningChannel = null; joinPassword = "" } }) { Text("参加") } },
            dismissButton = { TextButton(onClick = { joiningChannel = null }) { Text("キャンセル") } }
        )
    }

    if (showServerPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showServerPasswordDialog = false; pendingServerPassword = false },
            title = { Text("${server?.name ?: "サーバー"}に参加", style = MaterialTheme.typography.titleMedium) },
            text = { OutlinedTextField(value = joinPassword, onValueChange = { joinPassword = it }, label = { Text("パスワード") }, modifier = Modifier.fillMaxWidth(), singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { ensureJoined(joinPassword); showServerPasswordDialog = false; pendingServerPassword = false; joinPassword = ""; refresh() } }) { Text("参加") } },
            dismissButton = { TextButton(onClick = { showServerPasswordDialog = false; pendingServerPassword = false }) { Text("キャンセル") } }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun ChannelRow(channel: ChannelInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text("#", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(channel.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        if (channel.visibility == "private") append("非公開 · ")
                        append("${channel.user_count}人在室")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (channel.has_password) {
                Icon(Icons.Default.Lock, contentDescription = "鍵付き", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("参加") }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
}
