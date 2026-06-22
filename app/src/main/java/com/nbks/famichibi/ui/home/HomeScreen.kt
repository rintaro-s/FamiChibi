package com.nbks.famichibi.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nbks.famichibi.data.HostConfig
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.data.ServerMembership
import com.nbks.famichibi.network.DiscoveredServer
import com.nbks.famichibi.network.LanDiscovery
import com.nbks.famichibi.overlay.VrmOverlayService
import com.nbks.famichibi.util.formatName
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

@Serializable
data class ServerInfo(val id: String, val name: String, val has_password: Boolean, val icon: String = "")

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
    var hosts by remember { mutableStateOf(listOf<HostConfig>()) }
    var memberships by remember { mutableStateOf(listOf<ServerMembership>()) }
    var isOverlayRunning by remember { mutableStateOf(VrmOverlayService.isRunning(context)) }
    var showBriefing by remember { mutableStateOf(false) }
    var briefingText by remember { mutableStateOf("") }

    var showAddHost by remember { mutableStateOf(false) }
    var newHostName by remember { mutableStateOf("") }
    var newHostUrl by remember { mutableStateOf("") }
    var isDiscovering by remember { mutableStateOf(false) }
    var discoveredServers by remember { mutableStateOf(listOf<DiscoveredServer>()) }

    val httpClient = remember { HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } } }
    val lanDiscovery = remember { LanDiscovery() }

    LaunchedEffect(Unit) {
        userId = prefs.userId.first().ifEmpty { UUID.randomUUID().toString().also { prefs.setUserId(it) } }
        hosts = prefs.hosts.first()
        memberships = prefs.serverMemberships.first()
        val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        if (prefs.lastBriefingDate.first() != today) {
            briefingText = buildBriefing(formatName(memberships.firstOrNull()?.nickname ?: "ゲスト"), memberships.isNotEmpty())
            showBriefing = true
            prefs.setLastBriefingDate(today)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            isOverlayRunning = VrmOverlayService.isRunning(context)
            kotlinx.coroutines.delay(1000)
        }
    }

    LaunchedEffect(Unit) {
        lanDiscovery.servers.collect { discoveredServers = it }
    }

    suspend fun fetchServers(host: HostConfig): List<ServerInfo> {
        return try {
            Json.decodeFromString(httpClient.get("${host.url}/servers").bodyAsText())
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("${host.name}に接続できません")
            emptyList()
        }
    }

    suspend fun addHost(name: String, url: String) {
        val list = hosts + HostConfig(id = "host_${UUID.randomUUID()}", name = name, url = url)
        prefs.setHosts(list)
        hosts = list
        showAddHost = false
        snackbarHostState.showSnackbar("ホストを追加しました")
    }

    suspend fun joinServer(host: HostConfig, server: ServerInfo) {
        val existing = memberships.find { it.serverId == server.id && it.hostId == host.id }
        val nickname = existing?.nickname ?: "お兄ちゃん"
        val membership = ServerMembership(
            serverId = server.id,
            hostId = host.id,
            serverName = server.name,
            nickname = nickname,
            joinedAt = java.time.Instant.now().toString()
        )
        val newList = memberships.filterNot { it.serverId == server.id && it.hostId == host.id } + membership
        prefs.setServerMemberships(newList)
        memberships = newList
        navController.navigate("server")
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)) {
            Text(
                text = "こんにちは、${formatName(memberships.firstOrNull()?.nickname ?: "ゲスト")}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("ホストとサーバーを選んで、家族とつながりましょう。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // Hosts / Servers
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ホスト", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Button(onClick = { showAddHost = true }) { Text("ホスト追加") }
            }

            if (hosts.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("ホストがまだありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            hosts.forEach { host ->
                var expanded by remember { mutableStateOf(false) }
                var servers by remember { mutableStateOf(listOf<ServerInfo>()) }
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(host.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(host.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row {
                            TextButton(onClick = { expanded = !expanded; if (expanded) scope.launch { servers = fetchServers(host) } }) {
                                Text(if (expanded) "折りたたむ" else "サーバー")
                            }
                        }
                    }
                    if (expanded) {
                        servers.forEach { server ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(server.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(if (server.has_password) "鍵付き" else "公開", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Button(onClick = { scope.launch { joinServer(host, server) } }) { Text("開く") }
                            }
                        }
                        if (servers.isEmpty()) {
                            Text("サーバーがありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 32.dp, top = 8.dp, bottom = 8.dp))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        // Joined servers shortcut
        if (memberships.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "参加中のサーバー",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                memberships.forEach { m ->
                    val host = hosts.find { it.id == m.hostId }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(m.serverName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(host?.url ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { scope.launch { joinServer(host ?: return@launch, ServerInfo(m.serverId, m.serverName, false)) } }) { Text("開く") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Column(modifier = Modifier.fillMaxWidth()) {
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
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showAddHost) {
        AlertDialog(
            onDismissRequest = { showAddHost = false },
            title = { Text("ホストを追加") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = newHostName, onValueChange = { newHostName = it }, label = { Text("名前") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newHostUrl, onValueChange = { newHostUrl = it }, label = { Text("URL (例: http://192.168.1.10:8000)") }, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            isDiscovering = true
                            lanDiscovery.startDiscovery(scope)
                            scope.launch {
                                kotlinx.coroutines.delay(3500)
                                isDiscovering = false
                                lanDiscovery.stopDiscovery()
                            }
                        },
                        enabled = !isDiscovering,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isDiscovering) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("LAN検索")
                    }
                    if (discoveredServers.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            discoveredServers.forEach { s ->
                                OutlinedButton(
                                    onClick = { newHostUrl = "http://${s.host}:${s.port}" },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("${s.name} (${s.host}:${s.port})") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { scope.launch { addHost(newHostName, newHostUrl) } },
                    enabled = newHostName.isNotBlank() && newHostUrl.isNotBlank()
                ) { Text("追加") }
            },
            dismissButton = { TextButton(onClick = { showAddHost = false }) { Text("キャンセル") } }
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

@Composable
private fun HomeActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f), modifier = Modifier.weight(1f))
        if (enabled) TextButton(onClick = onClick) { Text("開く") }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

private fun buildBriefing(userName: String, hasServer: Boolean): String {
    return if (hasServer) {
        "${formatName(userName)}、おはようございます。\n今日も家族とつながりましょう。"
    } else {
        "${formatName(userName)}、おはようございます。\nまずはホストを追加して、サーバーに参加してみましょう。"
    }
}
