package com.nbks.famichibi.ui.rooms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.network.ChatWebSocket
import com.nbks.famichibi.network.DiscoveredServer
import com.nbks.famichibi.network.LanDiscovery
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
import java.util.UUID

@Serializable
data class RoomInfo(
    val id: String,
    val name: String,
    val has_password: Boolean,
    val user_count: Int,
    val created_at: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsScreen(
    navController: NavController,
    prefs: PreferencesRepository,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf("http://10.0.2.2:8000") }
    var userName by remember { mutableStateOf("お兄ちゃん") }
    var userId by remember { mutableStateOf("") }
    var rooms by remember { mutableStateOf(listOf<RoomInfo>()) }
    var isLoading by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var selectedRoom by remember { mutableStateOf<RoomInfo?>(null) }
    var joinPassword by remember { mutableStateOf("") }

    val lanDiscovery = remember { LanDiscovery() }
    var discoveredServers by remember { mutableStateOf(listOf<DiscoveredServer>()) }
    var isDiscovering by remember { mutableStateOf(false) }

    val httpClient = remember {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    LaunchedEffect(Unit) {
        serverUrl = prefs.serverUrl.first()
        userName = prefs.userName.first()
        userId = prefs.userId.first().ifEmpty { UUID.randomUUID().toString().also { prefs.setUserId(it) } }
    }

    LaunchedEffect(Unit) {
        lanDiscovery.servers.collect { discoveredServers = it }
    }

    suspend fun fetchRooms() {
        isLoading = true
        try {
            val response = httpClient.get("$serverUrl/rooms")
            if (response.status == HttpStatusCode.OK) {
                rooms = Json.decodeFromString(response.bodyAsText())
            }
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("部屋一覧の取得に失敗しました")
        } finally {
            isLoading = false
        }
    }

    suspend fun createRoom(name: String, password: String) {
        try {
            val response = httpClient.submitForm(
                url = "$serverUrl/rooms",
                formParameters = Parameters.build { append("name", name); append("password", password) }
            )
            if (response.status == HttpStatusCode.OK) {
                val body = Json.decodeFromString<Map<String, String>>(response.bodyAsText())
                val rid = body["room_id"] ?: return
                prefs.setRoomId(rid)
                ChatWebSocket.connect(serverUrl, rid, userId, userName, password)
                navController.navigate("chat") { popUpTo("home") { inclusive = false } }
                snackbarHostState.showSnackbar("部屋を作成しました")
                fetchRooms()
            }
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("作成に失敗しました")
        }
    }

    suspend fun joinRoom(room: RoomInfo, password: String) {
        try {
            val response = httpClient.submitForm(
                url = "$serverUrl/rooms/${room.id}/join",
                formParameters = Parameters.build {
                    append("user_id", userId)
                    append("user_name", userName)
                    append("password", password)
                }
            )
            if (response.status == HttpStatusCode.OK) {
                prefs.setRoomId(room.id)
                ChatWebSocket.connect(serverUrl, room.id, userId, userName, password)
                navController.navigate("chat") { popUpTo("home") { inclusive = false } }
                snackbarHostState.showSnackbar("${room.name}に参加しました")
            } else {
                snackbarHostState.showSnackbar("参加に失敗しました")
            }
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("参加に失敗しました")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "部屋",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    enabled = !isDiscovering
                ) {
                    if (isDiscovering) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("検索")
                }
                Button(onClick = { showCreate = true }) { Text("＋ 作成") }
            }
        }

        AnimatedVisibility(visible = discoveredServers.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("発見したサーバー", style = MaterialTheme.typography.labelLarge)
                discoveredServers.forEach { s ->
                    OutlinedButton(
                        onClick = { serverUrl = "http://${s.host}:${s.port}"; scope.launch { prefs.setServerUrl(serverUrl); fetchRooms() } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("${s.name} (${s.host}:${s.port})") }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (rooms.isEmpty() && !isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("まだ部屋がありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            rooms.forEach { room ->
                RoomRow(
                    room = room,
                    onClick = {
                        selectedRoom = room
                        if (room.has_password) {
                            showJoin = true
                        } else {
                            scope.launch { joinRoom(room, "") }
                        }
                    }
                )
            }
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showCreate) {
        var newName by remember { mutableStateOf("") }
        var newPassword by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("部屋を作成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("部屋名") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newPassword, onValueChange = { newPassword = it }, label = { Text("パスワード（空欄で公開）") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { scope.launch { createRoom(newName, newPassword); showCreate = false } },
                    enabled = newName.isNotBlank()
                ) { Text("作成") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("キャンセル") } }
        )
    }

    if (showJoin && selectedRoom != null) {
        AlertDialog(
            onDismissRequest = { showJoin = false },
            title = { Text("${selectedRoom!!.name}に参加") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("パスワードを入力してください")
                    OutlinedTextField(value = joinPassword, onValueChange = { joinPassword = it }, label = { Text("パスワード") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { joinRoom(selectedRoom!!, joinPassword); showJoin = false; joinPassword = "" }
                    }
                ) { Text("参加") }
            },
            dismissButton = { TextButton(onClick = { showJoin = false }) { Text("キャンセル") } }
        )
    }
}

@Composable
private fun RoomRow(room: RoomInfo, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(room.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("${room.user_count}人が参加中", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (room.has_password) {
                    Text("🔒", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onClick) { Text("参加") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}
