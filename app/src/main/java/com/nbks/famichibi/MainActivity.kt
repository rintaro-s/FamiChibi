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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.nbks.famichibi.data.AgentConfig
import com.nbks.famichibi.data.DecorationItem
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.overlay.OverlayService
import com.nbks.famichibi.ui.theme.FamiChibiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

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
                    snackbarHost = { SnackbarHost(snackbarHostState) }
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

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    prefs: PreferencesRepository,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }

    var serverUrl by remember { mutableStateOf("http://10.0.2.2:8000") }
    var roomId by remember { mutableStateOf("family-room-001") }
    var userName by remember { mutableStateOf("お兄ちゃん") }
    var vrmPath by remember { mutableStateOf("") }
    var isOverlayRunning by remember { mutableStateOf(OverlayService.isRunning(context)) }
    var showAgentDialog by remember { mutableStateOf(false) }
    var showDecoDialog by remember { mutableStateOf(false) }
    var agents by remember { mutableStateOf(listOf<AgentConfig>()) }
    var decorations by remember { mutableStateOf(listOf<DecorationItem>()) }

    LaunchedEffect(Unit) {
        serverUrl = prefs.serverUrl.first()
        roomId = prefs.roomId.first()
        userName = prefs.userName.first()
        vrmPath = prefs.vrmPath.first()
        agents = prefs.agents.first()
        decorations = prefs.decorations.first()
    }

    // Refresh overlay running state periodically
    LaunchedEffect(Unit) {
        while (true) {
            isOverlayRunning = OverlayService.isRunning(context)
            kotlinx.coroutines.delay(1000)
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
                    prefs.setVrmPath(path)
                    vrmPath = path
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "⚙️ FamiChibi 設定",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )

        // Connection Settings
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("接続設定", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("サーバーURL") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = roomId,
                    onValueChange = { roomId = it },
                    label = { Text("部屋ID") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = userName,
                    onValueChange = { userName = it },
                    label = { Text("あなたの名前") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        scope.launch {
                            prefs.setServerUrl(serverUrl)
                            prefs.setRoomId(roomId)
                            prefs.setUserName(userName)
                            snackbarHostState.showSnackbar("設定を保存しました")
                        }
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("保存")
                }
            }
        }

        // VRM Settings
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("アバター設定", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (vrmPath.isNotEmpty()) "VRM: ${File(vrmPath).name}" else "VRM: デフォルト (AvatarSample_M.vrm)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vrmPicker.launch("*/*") }) {
                        Text("VRMを読み込み")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                prefs.setVrmPath("")
                                vrmPath = ""
                            }
                        }
                    ) {
                        Text("デフォルトに戻す")
                    }
                }
            }
        }

        // AI Agent Settings
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AIエージェント", style = MaterialTheme.typography.titleMedium)
                if (agents.isEmpty()) {
                    Text("エージェントが設定されていません", style = MaterialTheme.typography.bodySmall)
                } else {
                    agents.forEach { agent ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${agent.name} (${agent.personality})")
                            TextButton(onClick = {
                                scope.launch {
                                    val updated = agents.filter { it.id != agent.id }
                                    prefs.setAgents(updated)
                                    agents = updated
                                }
                            }) {
                                Text("削除")
                            }
                        }
                    }
                }
                Button(onClick = { showAgentDialog = true }) {
                    Text("エージェントを追加")
                }
            }
        }

        // Decoration Settings
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("デコレーション", style = MaterialTheme.typography.titleMedium)
                Text("アバターにリボンや王冠などを装着できます", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ribbon", "heart", "crown", "flower").forEach { type ->
                        Button(onClick = {
                            scope.launch {
                                val item = DecorationItem(
                                    id = UUID.randomUUID().toString(),
                                    type = type,
                                    x = 100f,
                                    y = 50f
                                )
                                val updated = decorations + item
                                prefs.setDecorations(updated)
                                decorations = updated
                            }
                        }) {
                            Text(when(type) {
                                "ribbon" -> "🎀"
                                "heart" -> "❤️"
                                "crown" -> "👑"
                                "flower" -> "🌸"
                                else -> "?"
                            })
                        }
                    }
                }
                if (decorations.isNotEmpty()) {
                    TextButton(onClick = {
                        scope.launch {
                            prefs.setDecorations(emptyList())
                            decorations = emptyList()
                        }
                    }) {
                        Text("すべて削除")
                    }
                }
            }
        }

        // Overlay Control
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("オーバーレイ制御", style = MaterialTheme.typography.titleMedium)
                if (!Settings.canDrawOverlays(context)) {
                    Button(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("画面オーバーレイ権限を許可")
                    }
                } else {
                    Button(
                        onClick = {
                            notificationPermission?.launchPermissionRequest()
                            OverlayService.start(context)
                            isOverlayRunning = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isOverlayRunning
                    ) {
                        Text("アバターを表示開始")
                    }
                    OutlinedButton(
                        onClick = {
                            OverlayService.stop(context)
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
                Text(
                    "※「他のアプリの上に重ねて表示」を許可してください",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showAgentDialog) {
        var agentName by remember { mutableStateOf("") }
        var personality by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAgentDialog = false },
            title = { Text("AIエージェントを追加") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = agentName,
                        onValueChange = { agentName = it },
                        label = { Text("名前") }
                    )
                    OutlinedTextField(
                        value = personality,
                        onValueChange = { personality = it },
                        label = { Text("性格 (ツンデレ/おしとやか/元気など)") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val agent = AgentConfig(
                            id = UUID.randomUUID().toString(),
                            name = agentName,
                            personality = personality
                        )
                        val updated = agents + agent
                        prefs.setAgents(updated)
                        agents = updated
                    }
                    showAgentDialog = false
                }) {
                    Text("追加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAgentDialog = false }) {
                    Text("キャンセル")
                }
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
