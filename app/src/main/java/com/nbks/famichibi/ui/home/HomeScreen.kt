package com.nbks.famichibi.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nbks.famichibi.PendingDeepLink
import com.nbks.famichibi.data.HostConfig
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.data.ServerMembership
import com.nbks.famichibi.network.ApiClient
import com.nbks.famichibi.network.DiscoveredServer
import com.nbks.famichibi.network.JsonConfig
import com.nbks.famichibi.network.LanDiscovery
import com.nbks.famichibi.overlay.VrmOverlayService
import com.nbks.famichibi.util.formatName
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

@Serializable
data class ServerInfo(val id: String = "", val name: String = "", val has_password: Boolean = false, val icon: String = "", val member_count: Int = 0)

@Serializable
data class JoinResult(val joined: Boolean = false, val user_id: String = "", val role: String = "")
@Serializable
data class InviteJoinResult(val joined: Boolean = false, val server_id: String = "", val server_name: String = "", val channel_id: String? = null, val user_id: String = "", val role: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    prefs: PreferencesRepository,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var userId by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var hosts by remember { mutableStateOf(listOf<HostConfig>()) }
    var memberships by remember { mutableStateOf(listOf<ServerMembership>()) }
    var isOverlayRunning by remember { mutableStateOf(VrmOverlayService.isRunning(context)) }
    var showBriefing by remember { mutableStateOf(false) }
    var briefingText by remember { mutableStateOf("") }
    var showJoinDialog by remember { mutableStateOf(false) }

    val lanDiscovery = remember { LanDiscovery() }

    suspend fun joinByDeepLink(hostUrl: String, inviteCode: String) {
        val host = hosts.find { it.url == hostUrl }
            ?: HostConfig(id = "host_${UUID.randomUUID()}", name = "招待ホスト", url = hostUrl).also {
                val list = hosts + it
                prefs.setHosts(list)
                hosts = list
            }
        try {
            val displayName = userName.ifBlank { "お兄ちゃん" }
            val res = ApiClient.postForm("${host.url}/invites/${inviteCode.trim()}/join", userId, displayName, Parameters.build {
                append("user_name", displayName)
            })
            if (res.status == HttpStatusCode.OK) {
                val result = JsonConfig.json.decodeFromString<InviteJoinResult>(res.bodyAsText())
                if (result.server_id.isBlank()) return
                val membership = ServerMembership(serverId = result.server_id, hostId = host.id, serverName = result.server_name, nickname = displayName, role = result.role)
                val list = memberships.filterNot { it.serverId == membership.serverId && it.hostId == membership.hostId } + membership
                prefs.setServerMemberships(list)
                memberships = list
                prefs.setActiveHostId(membership.hostId)
                prefs.setActiveServerId(membership.serverId)
                if (!result.channel_id.isNullOrBlank()) {
                    prefs.setActiveChannelId(result.channel_id)
                    navController.navigate("channel")
                } else {
                    navController.navigate("server")
                }
            } else {
                snackbarHostState.showSnackbar("招待コードが無効です")
            }
        } catch (_: Exception) { snackbarHostState.showSnackbar("招待コードが無効です") }
    }

    LaunchedEffect(Unit) {
        userId = prefs.userId.first().ifEmpty { UUID.randomUUID().toString().also { prefs.setUserId(it) } }
        val savedName = prefs.userName.first()
        userName = savedName.ifBlank { "お兄ちゃん".also { prefs.setUserName(it) } }
        hosts = prefs.hosts.first()
        memberships = prefs.serverMemberships.first()
        val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        if (prefs.lastBriefingDate.first() != today) {
            briefingText = buildBriefing(formatName(memberships.firstOrNull()?.nickname ?: userName), memberships.isNotEmpty())
            showBriefing = true
            prefs.setLastBriefingDate(today)
        }
        if (PendingDeepLink.hasPending) {
            val hostUrl = PendingDeepLink.hostUrl ?: return@LaunchedEffect
            val inviteCode = PendingDeepLink.inviteCode ?: return@LaunchedEffect
            PendingDeepLink.clear()
            joinByDeepLink(hostUrl, inviteCode)
        }
    }

    LaunchedEffect(Unit) {
        while (true) { isOverlayRunning = VrmOverlayService.isRunning(context); kotlinx.coroutines.delay(1000) }
    }

    suspend fun refreshMemberships() { memberships = prefs.serverMemberships.first() }

    suspend fun openServer(m: ServerMembership) {
        prefs.setActiveHostId(m.hostId)
        prefs.setActiveServerId(m.serverId)
        navController.navigate("server")
    }

    suspend fun leaveMembership(m: ServerMembership) {
        val host = hosts.find { it.id == m.hostId } ?: return
        try {
            val res = ApiClient.postForm("${host.url}/s/${m.serverId}/leave", userId, m.nickname.ifEmpty { userName }, Parameters.build { })
            if (res.status == HttpStatusCode.OK) {
                val list = memberships.filterNot { it.serverId == m.serverId && it.hostId == m.hostId }
                prefs.setServerMemberships(list)
                memberships = list
            } else snackbarHostState.showSnackbar("退会に失敗しました")
        } catch (_: Exception) { snackbarHostState.showSnackbar("退会に失敗しました") }
    }

    suspend fun deleteRemoteServer(m: ServerMembership) {
        val host = hosts.find { it.id == m.hostId } ?: return
        try {
            val res = ApiClient.delete("${host.url}/s/${m.serverId}", userId, m.nickname.ifEmpty { userName })
            if (res.status == HttpStatusCode.OK) {
                val list = memberships.filterNot { it.serverId == m.serverId && it.hostId == m.hostId }
                prefs.setServerMemberships(list)
                memberships = list
            } else snackbarHostState.showSnackbar("削除に失敗しました")
        } catch (_: Exception) { snackbarHostState.showSnackbar("削除に失敗しました") }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showJoinDialog = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("サーバー参加") }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("こんにちは、${formatName(memberships.firstOrNull()?.nickname ?: userName)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("参加中のサーバー", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (memberships.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("まだサーバーに参加していません\n右下の＋から参加しましょう", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                items(memberships, key = { "${it.hostId}:${it.serverId}" }) { m ->
                    val host = hosts.find { it.id == m.hostId }
                    var expanded by remember { mutableStateOf(false) }
                    Card(
                        onClick = { scope.launch { openServer(m) } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(m.serverName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(host?.name ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box {
                                IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "メニュー") }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    DropdownMenuItem(text = { Text("サーバーから退会") }, onClick = { expanded = false; scope.launch { leaveMembership(m) } })
                                    if (m.role == "owner") {
                                        DropdownMenuItem(text = { Text("サーバーを削除") }, onClick = { expanded = false; scope.launch { deleteRemoteServer(m) } })
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                HomeActionRow(
                    icon = Icons.Default.Visibility,
                    label = if (isOverlayRunning) "アバターを停止" else "アバターを表示",
                    onClick = {
                        if (isOverlayRunning) VrmOverlayService.stop(context)
                        else {
                            if (!Settings.canDrawOverlays(context)) {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                            } else VrmOverlayService.start(context)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }

    if (showJoinDialog) {
        JoinServerDialog(
            prefs = prefs,
            hosts = hosts,
            userId = userId,
            userName = userName,
            lanDiscovery = lanDiscovery,
            onDismiss = { showJoinDialog = false },
            onJoined = { membership ->
                scope.launch {
                    val list = memberships.filterNot { it.serverId == membership.serverId && it.hostId == membership.hostId } + membership
                    prefs.setServerMemberships(list)
                    refreshMemberships()
                    showJoinDialog = false
                    prefs.setActiveHostId(membership.hostId)
                    prefs.setActiveServerId(membership.serverId)
                    navController.navigate("server")
                }
            },
            onHostsChanged = { hosts = it },
            snackbarHostState = snackbarHostState
        )
    }

    if (showBriefing) {
        ModalBottomSheet(onDismissRequest = { showBriefing = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)) {
                Text("今日のファミリー情報", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = briefingText, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { showBriefing = false }, modifier = Modifier.fillMaxWidth()) { Text("閉じる") }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JoinServerDialog(
    prefs: PreferencesRepository,
    hosts: List<HostConfig>,
    userId: String,
    userName: String,
    lanDiscovery: LanDiscovery,
    onDismiss: () -> Unit,
    onJoined: (ServerMembership) -> Unit,
    onHostsChanged: (List<HostConfig>) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(1) }
    var selectedHost by remember { mutableStateOf<HostConfig?>(null) }
    var servers by remember { mutableStateOf(listOf<ServerInfo>()) }
    var selectedServer by remember { mutableStateOf<ServerInfo?>(null) }
    var password by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf(userName) }
    var resolvedUserId by remember { mutableStateOf(userId) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (resolvedUserId.isBlank()) {
            resolvedUserId = UUID.randomUUID().toString()
            prefs.setUserId(resolvedUserId)
        }
    }

    var showAddHost by remember { mutableStateOf(false) }
    var newHostName by remember { mutableStateOf("") }
    var newHostUrl by remember { mutableStateOf("") }
    var isDiscovering by remember { mutableStateOf(false) }
    var showCreateServer by remember { mutableStateOf(false) }
    var newServerName by remember { mutableStateOf("") }
    var newServerPassword by remember { mutableStateOf("") }
    var newServerInviteOnly by remember { mutableStateOf(false) }
    var discoveredServers by remember { mutableStateOf(listOf<DiscoveredServer>()) }

    LaunchedEffect(Unit) { lanDiscovery.servers.collect { discoveredServers = it } }

    suspend fun fetchServers(host: HostConfig) {
        isLoading = true
        try {
            servers = JsonConfig.json.decodeFromString(ApiClient.get("${host.url}/servers", userId, userName).bodyAsText())
            selectedHost = host
            step = 2
        } catch (_: Exception) { snackbarHostState.showSnackbar("${host.name}に接続できません") }
        finally { isLoading = false }
    }

    suspend fun joinServer(host: HostConfig, server: ServerInfo, pw: String = "") {
        if (resolvedUserId.isBlank()) { snackbarHostState.showSnackbar("ユーザーIDの準備ができていません"); return }
        isLoading = true
        try {
            val displayName = nickname.ifBlank { userName }
            val res = ApiClient.postForm("${host.url}/s/${server.id}/join", resolvedUserId, displayName, Parameters.build {
                append("password", pw)
                append("user_name", displayName)
            })
            if (res.status == HttpStatusCode.OK) {
                val joinRes = JsonConfig.json.decodeFromString<JoinResult>(res.bodyAsText())
                onJoined(ServerMembership(serverId = server.id, hostId = host.id, serverName = server.name, nickname = displayName, joinedAt = java.time.Instant.now().toString(), role = joinRes.role))
            } else {
                val text = try { res.bodyAsText() } catch (_: Exception) { "" }
                if (text.contains("password") || text.contains("Invalid")) { selectedServer = server; step = 3 }
                else snackbarHostState.showSnackbar("参加に失敗しました")
            }
        } catch (_: Exception) { snackbarHostState.showSnackbar("参加に失敗しました") }
        finally { isLoading = false }
    }

    suspend fun joinByInvite(host: HostConfig) {
        if (inviteCode.isBlank()) return
        if (resolvedUserId.isBlank()) { snackbarHostState.showSnackbar("ユーザーIDの準備ができていません"); return }
        isLoading = true
        try {
            val displayName = nickname.ifBlank { userName }
            val res = ApiClient.postForm("${host.url}/invites/${inviteCode.trim()}/join", resolvedUserId, displayName, Parameters.build {
                append("user_name", displayName)
            })
            if (res.status == HttpStatusCode.OK) {
                val result = JsonConfig.json.decodeFromString<InviteJoinResult>(res.bodyAsText())
                if (result.server_id.isBlank()) return
                onJoined(ServerMembership(serverId = result.server_id, hostId = host.id, serverName = result.server_name, nickname = displayName, role = result.role))
            } else snackbarHostState.showSnackbar("招待コードが無効です")
        } catch (_: Exception) { snackbarHostState.showSnackbar("招待コードが無効です") }
        finally { isLoading = false }
    }

    suspend fun addHost(name: String, url: String) {
        if (name.isBlank() || url.isBlank()) return
        val host = HostConfig(id = "host_${UUID.randomUUID()}", name = name, url = url)
        val list = hosts + host
        prefs.setHosts(list)
        onHostsChanged(list)
        selectedHost = host
        showAddHost = false
        scope.launch { fetchServers(host) }
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("サーバーに参加", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (step) {
                    1 -> {
                        Text("ホストを選ぶか、招待コードを入力してください", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (hosts.isEmpty()) Text("ホストがまだありません。まず追加してください。", style = MaterialTheme.typography.bodySmall)
                        hosts.forEach { h ->
                            OutlinedButton(onClick = { scope.launch { fetchServers(h) } }, modifier = Modifier.fillMaxWidth(), enabled = !isLoading, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                                Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                                    Text(h.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                                    Text(h.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        OutlinedButton(onClick = { showAddHost = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text("＋ ホストを追加") }
                        HorizontalDivider()
                        OutlinedTextField(value = inviteCode, onValueChange = { inviteCode = it }, label = { Text("招待コード") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        if (hosts.isEmpty()) {
                            Text("招待コードで参加するにはホストを追加してください", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            hosts.forEach { h ->
                                OutlinedButton(onClick = { scope.launch { joinByInvite(h) } }, enabled = inviteCode.isNotBlank() && !isLoading, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text("${h.name} で招待コードを使う", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (isDiscovering) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                    2 -> {
                        Text("${selectedHost?.name} のサーバー", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = nickname,
                            onValueChange = { nickname = it },
                            label = { Text("このサーバーでの表示名") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (servers.isEmpty()) Text("サーバーがありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        servers.forEach { s ->
                            OutlinedButton(
                                onClick = { scope.launch { joinServer(selectedHost!!, s) } },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isLoading,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.name, style = MaterialTheme.typography.bodyMedium)
                                    if (s.has_password) Icon(Icons.Default.Lock, contentDescription = "鍵付き", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        TextButton(onClick = { step = 1; servers = emptyList(); selectedHost = null }, contentPadding = PaddingValues(0.dp)) { Text("戻る") }
                        OutlinedButton(onClick = { showCreateServer = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text("＋ 新規サーバー作成") }
                    }
                    3 -> {
                        Text("${selectedServer?.name} は鍵付きです", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = nickname, onValueChange = { nickname = it }, label = { Text("このサーバーでの表示名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("パスワード") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = { scope.launch { joinServer(selectedHost!!, selectedServer!!, password) } }, enabled = password.isNotBlank() && !isLoading, modifier = Modifier.fillMaxWidth()) { Text("参加") }
                        TextButton(onClick = { step = 2; password = "" }, contentPadding = PaddingValues(0.dp)) { Text("戻る") }
                    }
                }
                if (isLoading) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).size(24.dp))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isLoading) { Text("閉じる") } }
    )

    if (showCreateServer) {
        AlertDialog(
            onDismissRequest = { showCreateServer = false },
            title = { Text("サーバー作成", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newServerName, onValueChange = { newServerName = it }, label = { Text("名前") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = newServerPassword, onValueChange = { newServerPassword = it }, label = { Text("パスワード（空欄で公開）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = newServerInviteOnly, onCheckedChange = { newServerInviteOnly = it })
                        Text("招待コードのみで参加可能", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val h = selectedHost ?: return@launch
                            isLoading = true
                            try {
                                val displayName = nickname.ifBlank { userName }
                                val res = ApiClient.postForm("${h.url}/servers", resolvedUserId, displayName, Parameters.build {
                                    append("name", newServerName); append("password", newServerPassword); append("owner_id", resolvedUserId); append("owner_name", displayName); append("invite_only", if (newServerInviteOnly) "true" else "false")
                                })
                                if (res.status == HttpStatusCode.OK) {
                                    val info = JsonConfig.json.decodeFromString<Map<String, String>>(res.bodyAsText())
                                    val sid = info["id"] ?: return@launch
                                    onJoined(ServerMembership(serverId = sid, hostId = h.id, serverName = newServerName, nickname = userName, role = "owner"))
                                }
                            } catch (_: Exception) { snackbarHostState.showSnackbar("作成に失敗しました") }
                            finally { isLoading = false; showCreateServer = false }
                        }
                    },
                    enabled = newServerName.isNotBlank()
                ) { Text("作成") }
            },
            dismissButton = { TextButton(onClick = { showCreateServer = false }) { Text("キャンセル") } }
        )
    }

    if (showAddHost) {
        AlertDialog(
            onDismissRequest = { showAddHost = false },
            title = { Text("ホストを追加", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newHostName, onValueChange = { newHostName = it }, label = { Text("名前") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = newHostUrl, onValueChange = { newHostUrl = it }, label = { Text("URL (例: http://192.168.1.10:8000)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(
                        onClick = { isDiscovering = true; lanDiscovery.startDiscovery(scope); scope.launch { kotlinx.coroutines.delay(3500); isDiscovering = false; lanDiscovery.stopDiscovery() } },
                        enabled = !isDiscovering,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isDiscovering) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("LAN検索")
                    }
                    discoveredServers.forEach { s ->
                        TextButton(onClick = { newHostUrl = "http://${s.host}:${s.port}" }, contentPadding = PaddingValues(0.dp)) { Text("${s.name} (${s.host}:${s.port})", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { scope.launch { addHost(newHostName, newHostUrl); newHostName = ""; newHostUrl = "" } }, enabled = newHostName.isNotBlank() && newHostUrl.isNotBlank()) { Text("追加") } },
            dismissButton = { TextButton(onClick = { showAddHost = false }) { Text("キャンセル") } }
        )
    }
}

@Composable
private fun HomeActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f), modifier = Modifier.weight(1f))
        if (enabled) TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("開く") }
    }
}

private fun buildBriefing(userName: String, hasServer: Boolean): String {
    return if (hasServer) "${formatName(userName)}、おはようございます。\n今日も家族とつながりましょう。"
    else "${formatName(userName)}、おはようございます。\nまずはサーバーに参加してみましょう。"
}
