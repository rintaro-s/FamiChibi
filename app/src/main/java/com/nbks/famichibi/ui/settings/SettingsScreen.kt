package com.nbks.famichibi.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.network.DiscoveredServer
import com.nbks.famichibi.network.LanDiscovery
import com.nbks.famichibi.vrm.AssetVrmScanner
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: PreferencesRepository,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf("http://10.0.2.2:8000") }
    var userName by remember { mutableStateOf("お兄ちゃん") }
    var quickPhrases by remember { mutableStateOf("") }
    var quietStart by remember { mutableIntStateOf(0) }
    var quietEnd by remember { mutableIntStateOf(7) }
    var quietEnabled by remember { mutableStateOf(false) }
    var ttsEnabled by remember { mutableStateOf(false) }
    var voiceEnabled by remember { mutableStateOf(false) }
    var myVrmPath by remember { mutableStateOf("") }
    var selectedAssetVrm by remember { mutableStateOf("") }
    var assetVrms by remember { mutableStateOf(listOf<String>()) }

    val lanDiscovery = remember { LanDiscovery() }
    var discoveredServers by remember { mutableStateOf(listOf<DiscoveredServer>()) }
    var isDiscovering by remember { mutableStateOf(false) }

    val httpClient = remember { HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } } }

    LaunchedEffect(Unit) {
        serverUrl = prefs.serverUrl.first()
        userName = prefs.userName.first()
        quickPhrases = prefs.quickPhrases.first().joinToString(",")
        quietStart = prefs.quietHoursStart.first()
        quietEnd = prefs.quietHoursEnd.first()
        quietEnabled = prefs.quietEnabled.first()
        ttsEnabled = prefs.ttsEnabled.first()
        voiceEnabled = prefs.voiceInputEnabled.first()
        myVrmPath = prefs.myVrmPath.first()
        selectedAssetVrm = prefs.selectedAssetVrm.first()
        assetVrms = AssetVrmScanner.listAssetVrms(context)
    }

    LaunchedEffect(Unit) {
        lanDiscovery.servers.collect { discoveredServers = it }
    }

    val vrmPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                val path = copyVrmToInternal(context, it)
                if (path != null) {
                    prefs.setMyVrmPath(path)
                    myVrmPath = path
                    snackbarHostState.showSnackbar("VRMを読み込みました: ${File(path).name}")
                }
            }
        }
    }

    suspend fun save() {
        prefs.setServerUrl(serverUrl)
        prefs.setUserName(userName)
        prefs.setQuickPhrases(quickPhrases.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        prefs.setQuietHours(quietStart.coerceIn(0, 23), quietEnd.coerceIn(0, 23))
        prefs.setQuietEnabled(quietEnabled)
        prefs.setTtsEnabled(ttsEnabled)
        prefs.setVoiceInputEnabled(voiceEnabled)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp)) {
            Text("設定", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // Server
        SettingSection(title = "サーバー接続") {
            OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, label = { Text("サーバーURL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scope.launch { save(); snackbarHostState.showSnackbar("保存しました") } },
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
                    if (isDiscovering) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("自動検索")
                }
            }
            AnimatedVisibility(visible = discoveredServers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    discoveredServers.forEach { s ->
                        OutlinedButton(
                            onClick = { serverUrl = "http://${s.host}:${s.port}" },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("${s.name} (${s.host}:${s.port})") }
                    }
                }
            }
        }

        // Profile
        SettingSection(title = "プロフィール") {
            OutlinedTextField(value = userName, onValueChange = { userName = it }, label = { Text("あなたの名前") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }

        // Avatar
        SettingSection(title = "アバター") {
            Text("デフォルトVRM（アセット内）", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            if (assetVrms.isEmpty()) {
                Text("vrm-viewer内にVRMが見つかりませんでした", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    assetVrms.forEach { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedAssetVrm == name, onClick = {
                                selectedAssetVrm = name
                                scope.launch {
                                    val file = AssetVrmScanner.copyAssetVrmToInternal(context, name)
                                    if (file != null) {
                                        prefs.setSelectedAssetVrm(name)
                                        prefs.setMyVrmPath(file.absolutePath)
                                        myVrmPath = file.absolutePath
                                    }
                                }
                            })
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(12.dp))
            Text("または独自のVRMを選択", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (myVrmPath.isNotEmpty()) "読込中: ${File(myVrmPath).name}" else "未設定",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vrmPicker.launch("*/*") }, modifier = Modifier.weight(1f)) { Text("VRMを選択") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            prefs.setMyVrmPath("")
                            myVrmPath = ""
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("クリア") }
            }
        }

        // Quick phrases
        SettingSection(title = "クイック返信") {
            OutlinedTextField(value = quickPhrases, onValueChange = { quickPhrases = it }, label = { Text("カンマ区切り") }, modifier = Modifier.fillMaxWidth())
        }

        // Quiet hours + TTS
        SettingSection(title = "音声とおやすみ") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("アバター音声（TTS）", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = ttsEnabled, onCheckedChange = { ttsEnabled = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("音声入力（マイク）", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = voiceEnabled, onCheckedChange = { voiceEnabled = it })
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("おやすみ時間", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = quietStart.toString(), onValueChange = { quietStart = it.toIntOrNull() ?: 0 }, label = { Text("開始") }, modifier = Modifier.weight(1f), singleLine = true)
                Text("〜")
                OutlinedTextField(value = quietEnd.toString(), onValueChange = { quietEnd = it.toIntOrNull() ?: 7 }, label = { Text("終了") }, modifier = Modifier.weight(1f), singleLine = true)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { scope.launch { save(); snackbarHostState.showSnackbar("設定を保存しました") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) { Text("すべて保存") }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
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
