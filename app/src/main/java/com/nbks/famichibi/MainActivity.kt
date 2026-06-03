package com.nbks.famichibi

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.nbks.famichibi.data.AgentConfig
import com.nbks.famichibi.data.DecorationItem
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.network.ChatEvent
import com.nbks.famichibi.network.ChatMessage
import com.nbks.famichibi.network.ChatWebSocket
import com.nbks.famichibi.network.DiscoveredServer
import com.nbks.famichibi.network.LanDiscovery
import com.nbks.famichibi.overlay.VrmOverlayActivity
import com.nbks.famichibi.overlay.VrmOverlayService
import com.nbks.famichibi.ui.theme.FamiChibiTheme
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = PreferencesRepository(applicationContext)

        setContent {
            FamiChibiTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = {
                                Text(
                                    "FamiChibi",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                ) { innerPadding ->
                    SettingsScreen(
                        modifier = Modifier.padding(innerPadding),
                        prefs = prefs,
                        snackbarHostState = snackbarHostState
                    )
                }
            }
        }
    }
}

@Serializable
data class RoomInfo(
    val id: String,
    val name: String,
    val has_password: Boolean,
    val user_count: Int,
    val created_at: String
)

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Suppress("OPT_IN_IS_NOT_ENABLED")
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    prefs: PreferencesRepository,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var serverUrl by remember { mutableStateOf("http://10.0.2.2:8000") }
    var roomId by remember { mutableStateOf("") }
    var roomName by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("お兄ちゃん") }
    var userId by remember { mutableStateOf("") }
    var vrmPath by remember { mutableStateOf("") }
    var myVrmPath by remember { mutableStateOf("") }
    var isOverlayRunning by remember { mutableStateOf(VrmOverlayService.isRunning(context)) }
    var showAgentDialog by remember { mutableStateOf(false) }
    var showDecoDialog by remember { mutableStateOf(false) }
    var agents by remember { mutableStateOf(listOf<AgentConfig>()) }
    var decorations by remember { mutableStateOf(listOf<DecorationItem>()) }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var chatInput by remember { mutableStateOf("") }

    // LAN Discovery
    val lanDiscovery = remember { LanDiscovery() }
    var discoveredServers by remember { mutableStateOf(listOf<DiscoveredServer>()) }
    var isDiscovering by remember { mutableStateOf(false) }

    // Room management
    var rooms by remember { mutableStateOf(listOf<RoomInfo>()) }
    var isLoadingRooms by remember { mutableStateOf(false) }
    var showCreateRoomDialog by remember { mutableStateOf(false) }
    var showJoinRoomDialog by remember { mutableStateOf(false) }
    var selectedRoom by remember { mutableStateOf<RoomInfo?>(null) }
    var joinPassword by remember { mutableStateOf("") }

    val httpClient = remember {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    LaunchedEffect(Unit) {
        serverUrl = prefs.serverUrl.first()
        roomId = prefs.roomId.first()
        userName = prefs.userName.first()
        userId = prefs.userId.first().ifEmpty { UUID.randomUUID().toString().also { prefs.setUserId(it) } }
        vrmPath = prefs.vrmPath.first()
        myVrmPath = prefs.myVrmPath.first()
        agents = prefs.agents.first()
        decorations = prefs.decorations.first()
    }

    // Observe discovered servers
    LaunchedEffect(Unit) {
        lanDiscovery.servers.collect { discoveredServers = it }
    }

    // Refresh overlay running state periodically
    LaunchedEffect(Unit) {
        while (true) {
            isOverlayRunning = VrmOverlayService.isRunning(context)
            kotlinx.coroutines.delay(1000)
        }
    }

    LaunchedEffect(Unit) {
        ChatWebSocket.events.collect { event ->
            when (event) {
                is ChatEvent.Message -> {
                    messages = messages + event.msg
                }
                else -> {}
            }
        }
    }

    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(android.Manifest.permission.POST_NOTIFICATIONS)
    } else null

    val vrmPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val path = copyVrmToInternal(context, it)
                if (path != null) {
                    prefs.setMyVrmPath(path)
                    myVrmPath = path
                }
            }
        }
    }

    suspend fun fetchRooms() {
        isLoadingRooms = true
        try {
            val response = httpClient.get("$serverUrl/rooms")
            if (response.status == HttpStatusCode.OK) {
                val body = response.bodyAsText()
                rooms = Json.decodeFromString(body)
            }
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("部屋一覧の取得に失敗しました")
        } finally {
            isLoadingRooms = false
        }
    }

    suspend fun createRoom(name: String, password: String) {
        try {
            val response = httpClient.submitForm(
                url = "$serverUrl/rooms",
                formParameters = Parameters.build {
                    append("name", name)
                    append("password", password)
                }
            )
            if (response.status == HttpStatusCode.OK) {
                snackbarHostState.showSnackbar("部屋を作成しました")
                fetchRooms()
            }
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("部屋の作成に失敗しました")
        }
    }

    suspend fun loadChatHistory() {
        if (roomId.isEmpty()) return
        try {
            val response = httpClient.get("$serverUrl/rooms/$roomId/messages?limit=50")
            if (response.status == HttpStatusCode.OK) {
                messages = Json.decodeFromString(response.bodyAsText())
            }
        } catch (_: Exception) {}
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
                roomId = room.id
                roomName = room.name
                loadChatHistory()
                snackbarHostState.showSnackbar("${room.name}に参加しました")
            } else {
                snackbarHostState.showSnackbar("参加に失敗しました")
            }
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("参加に失敗しました")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Server Connection Card
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("サーバー接続", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("サーバーURL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                prefs.setServerUrl(serverUrl)
                                snackbarHostState.showSnackbar("サーバーを保存しました")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("保存") }

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
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isDiscovering) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("自動検索")
                    }
                }

                AnimatedVisibility(visible = discoveredServers.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("発見したサーバー:", style = MaterialTheme.typography.labelMedium)
                        discoveredServers.forEach { server ->
                            OutlinedCard(
                                onClick = {
                                    serverUrl = "http://${server.host}:${server.port}"
                                    scope.launch { prefs.setServerUrl(serverUrl) }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(server.name, fontWeight = FontWeight.Medium)
                                        Text("${server.host}:${server.port}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("接続", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Room Card
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Text("部屋", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }

                OutlinedTextField(
                    value = userName,
                    onValueChange = { userName = it },
                    label = { Text("あなたの名前") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (roomId.isNotEmpty()) {
                    AssistChip(
                        onClick = { },
                        label = { Text("参加中: $roomName ($roomId)") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                prefs.setUserName(userName)
                                if (userId.isEmpty()) {
                                    userId = UUID.randomUUID().toString()
                                    prefs.setUserId(userId)
                                }
                                fetchRooms()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("部屋一覧") }

                    OutlinedButton(
                        onClick = { showCreateRoomDialog = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("部屋を作る") }
                }

                AnimatedVisibility(visible = isLoadingRooms) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }

                AnimatedVisibility(visible = rooms.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rooms.forEach { room ->
                            OutlinedCard(
                                onClick = {
                                    selectedRoom = room
                                    if (room.has_password) {
                                        showJoinRoomDialog = true
                                    } else {
                                        scope.launch { joinRoom(room, "") }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(room.name, fontWeight = FontWeight.Medium)
                                            if (room.has_password) {
                                                Text("🔒", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        Text("${room.user_count}人在室", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("参加", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Avatar Card
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    Text("アバター設定", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }

                Text(
                    text = if (myVrmPath.isNotEmpty()) "自分のVRM: ${File(myVrmPath).name}" else "自分のVRM: 未設定（デフォルト使用）",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vrmPicker.launch("*/*") }) {
                        Text("VRMを選択")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                prefs.setMyVrmPath("")
                                myVrmPath = ""
                            }
                        }
                    ) {
                        Text("クリア")
                    }
                }

                HorizontalDivider()

                Text(
                    text = if (vrmPath.isNotEmpty()) "表示VRM: ${File(vrmPath).name}" else "表示VRM: デフォルト",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("オーバーレイに表示するVRM（自分用）", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Overlay Control Card
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("オーバーレイ制御", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

                if (!Settings.canDrawOverlays(context)) {
                    Button(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("画面オーバーレイ権限を許可")
                    }
                } else {
                    Button(
                        onClick = {
                            notificationPermission?.launchPermissionRequest()
                            VrmOverlayService.start(context)
                            isOverlayRunning = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isOverlayRunning
                    ) {
                        Text("アバターを表示開始")
                    }
                    OutlinedButton(
                        onClick = {
                            VrmOverlayService.stop(context)
                            VrmOverlayActivity.stop(context)
                            isOverlayRunning = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isOverlayRunning
                    ) {
                        Text("アバターを停止")
                    }
                    if (isOverlayRunning) {
                        Text(
                            "アバターが表示中です。ホーム画面に戻って確認してください。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Chat Card (shown when overlay is running and joined a room)
        if (isOverlayRunning && roomId.isNotEmpty()) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("チャット", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (messages.isEmpty()) {
                            Text(
                                "まだメッセージがありません",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            messages.forEach { msg ->
                                val isMe = msg.sender_id == userId || msg.sender == userName
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                                ) {
                                    Text(
                                        text = msg.sender,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Surface(
                                        color = if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                        shape = MaterialTheme.shapes.medium
                                    ) {
                                        Text(
                                            text = msg.content,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            label = { Text("メッセージ") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                if (chatInput.isNotBlank()) {
                                    ChatWebSocket.sendMessage(userName, chatInput)
                                    chatInput = ""
                                }
                            },
                            enabled = chatInput.isNotBlank()
                        ) {
                            Text("送信")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    // Create Room Dialog
    if (showCreateRoomDialog) {
        var newRoomName by remember { mutableStateOf("") }
        var newRoomPassword by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateRoomDialog = false },
            title = { Text("部屋を作成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newRoomName,
                        onValueChange = { newRoomName = it },
                        label = { Text("部屋の名前") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newRoomPassword,
                        onValueChange = { newRoomPassword = it },
                        label = { Text("パスワード（空欄で公開）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            createRoom(newRoomName, newRoomPassword)
                            showCreateRoomDialog = false
                        }
                    },
                    enabled = newRoomName.isNotBlank()
                ) { Text("作成") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateRoomDialog = false }) { Text("キャンセル") }
            }
        )
    }

    // Join Room Dialog
    if (showJoinRoomDialog && selectedRoom != null) {
        AlertDialog(
            onDismissRequest = { showJoinRoomDialog = false },
            title = { Text("${selectedRoom!!.name}に参加") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("パスワードを入力してください")
                    OutlinedTextField(
                        value = joinPassword,
                        onValueChange = { joinPassword = it },
                        label = { Text("パスワード") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            joinRoom(selectedRoom!!, joinPassword)
                            showJoinRoomDialog = false
                            joinPassword = ""
                        }
                    }
                ) { Text("参加") }
            },
            dismissButton = {
                TextButton(onClick = { showJoinRoomDialog = false }) { Text("キャンセル") }
            }
        )
    }
}

private fun copyVrmToInternal(context: android.content.Context, uri: Uri): String? {
    return try {
        val fileName = getFileName(context, uri) ?: "imported.vrm"
        val destFile = File(context.filesDir, fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        destFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun getFileName(context: android.content.Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) result = cursor.getString(index)
            }
        }
    }
    if (result == null) {
        result = uri.path?.let { path ->
            val cut = path.lastIndexOf('/')
            if (cut != -1) path.substring(cut + 1) else path
        }
    }
    return result
}
