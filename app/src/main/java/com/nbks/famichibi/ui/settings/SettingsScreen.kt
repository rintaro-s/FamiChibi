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
import androidx.navigation.NavController
import com.nbks.famichibi.data.HostConfig
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.data.ServerMembership
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
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    prefs: PreferencesRepository,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hosts by remember { mutableStateOf(listOf<HostConfig>()) }
    var memberships by remember { mutableStateOf(listOf<ServerMembership>()) }
    var quickPhrases by remember { mutableStateOf("") }
    var quietStart by remember { mutableIntStateOf(0) }
    var quietEnd by remember { mutableIntStateOf(7) }
    var quietEnabled by remember { mutableStateOf(false) }
    var ttsEnabled by remember { mutableStateOf(false) }
    var voiceEnabled by remember { mutableStateOf(false) }
    var speaker by remember { mutableIntStateOf(58) }
    var demoMode by remember { mutableStateOf(false) }

    var showAddHost by remember { mutableStateOf(false) }
    var newHostName by remember { mutableStateOf("") }
    var newHostUrl by remember { mutableStateOf("") }

    var selectedMembership by remember { mutableStateOf<ServerMembership?>(null) }
    var editNickname by remember { mutableStateOf("") }
    var editAvatarUrl by remember { mutableStateOf("") }

    val lanDiscovery = remember { LanDiscovery() }
    var discoveredServers by remember { mutableStateOf(listOf<DiscoveredServer>()) }
    var isDiscovering by remember { mutableStateOf(false) }
    var assetVrms by remember { mutableStateOf(listOf<String>()) }

    val httpClient = remember { HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } } }

    LaunchedEffect(Unit) {
        hosts = prefs.hosts.first()
        memberships = prefs.serverMemberships.first()
        quickPhrases = prefs.quickPhrases.first().joinToString(",")
        quietStart = prefs.quietHoursStart.first()
        quietEnd = prefs.quietHoursEnd.first()
        quietEnabled = prefs.quietEnabled.first()
        ttsEnabled = prefs.ttsEnabled.first()
        voiceEnabled = prefs.voiceInputEnabled.first()
        speaker = prefs.voicevoxSpeaker.first()
        demoMode = prefs.demoMode.first()
        assetVrms = AssetVrmScanner.listAssetVrms(context)
    }

    LaunchedEffect(Unit) { lanDiscovery.servers.collect { discoveredServers = it } }

    val vrmPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                val path = copyVrmToInternal(context, it)
                if (path != null) {
                    editAvatarUrl = path
                    snackbarHostState.showSnackbar("VRMを読み込みました: ${File(path).name}")
                }
            }
        }
    }

    suspend fun save() {
        prefs.setQuickPhrases(quickPhrases.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        prefs.setQuietHours(quietStart.coerceIn(0, 23), quietEnd.coerceIn(0, 23))
        prefs.setQuietEnabled(quietEnabled)
        prefs.setTtsEnabled(ttsEnabled)
        prefs.setVoiceInputEnabled(voiceEnabled)
        prefs.setVoicevoxSpeaker(speaker)
        prefs.setDemoMode(demoMode)
    }

    suspend fun addHost() {
        if (newHostName.isBlank() || newHostUrl.isBlank()) return
        val list = hosts + HostConfig(id = "host_${UUID.randomUUID()}", name = newHostName, url = newHostUrl)
        prefs.setHosts(list)
        hosts = list
        newHostName = ""; newHostUrl = ""; showAddHost = false
        snackbarHostState.showSnackbar("ホストを追加しました")
    }

    suspend fun removeHost(host: HostConfig) {
        val list = hosts.filter { it.id != host.id }
        prefs.setHosts(list)
        hosts = list
        prefs.setServerMemberships(memberships.filter { it.hostId != host.id })
        memberships = memberships.filter { it.hostId != host.id }
    }

    suspend fun saveMembershipProfile() {
        val m = selectedMembership ?: return
        val newList = memberships.map {
            if (it.serverId == m.serverId && it.hostId == m.hostId) it.copy(nickname = editNickname, avatarUrl = editAvatarUrl)
            else it
        }
        prefs.setServerMemberships(newList)
        memberships = newList
        selectedMembership = null
        snackbarHostState.showSnackbar("プロフィールを保存しました")
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp)) {
            Text("設定", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        SettingSection(title = "ホスト") {
            hosts.forEach { h ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(h.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(h.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { scope.launch { removeHost(h) } }) { Text("削除") }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { showAddHost = true }) { Text("ホストを追加") }
        }

        SettingSection(title = "サーバー別プロフィール") {
            memberships.forEach { m ->
                val h = hosts.find { it.id == m.hostId }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(m.serverName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("${m.nickname} · ${h?.url ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { selectedMembership = m; editNickname = m.nickname; editAvatarUrl = m.avatarUrl }) { Text("編集") }
                }
            }
        }

        SettingSection(title = "クイック返信") {
            OutlinedTextField(value = quickPhrases, onValueChange = { quickPhrases = it }, label = { Text("カンマ区切り") }, modifier = Modifier.fillMaxWidth())
        }

        SettingSection(title = "デモモード") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("デモモード", style = MaterialTheme.typography.bodyMedium)
                    Text("アバター2体を表示してランダムに動かす", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = demoMode, onCheckedChange = { demoMode = it })
            }
        }

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
            Text("VOICEVOX 話者", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(speakerLabel(speaker)) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf(58 to "58: 猫使ビィ", 3 to "3: ずんだもん", 2 to "2: 四国めたん", 8 to "8: 春日部つむぎ", 10 to "10: 雨晴はう", 14 to "14: 冥鳴ひまり").forEach { (id, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { speaker = id; expanded = false })
                    }
                }
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
        Button(onClick = { scope.launch { save(); snackbarHostState.showSnackbar("設定を保存しました") } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text("すべて保存") }
        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showAddHost) {
        AlertDialog(
            onDismissRequest = { showAddHost = false },
            title = { Text("ホストを追加") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = newHostName, onValueChange = { newHostName = it }, label = { Text("名前") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newHostUrl, onValueChange = { newHostUrl = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            isDiscovering = true
                            lanDiscovery.startDiscovery(scope)
                            scope.launch { kotlinx.coroutines.delay(3500); isDiscovering = false; lanDiscovery.stopDiscovery() }
                        },
                        enabled = !isDiscovering,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isDiscovering) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("LAN検索")
                    }
                    AnimatedVisibility(visible = discoveredServers.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            discoveredServers.forEach { s ->
                                OutlinedButton(onClick = { newHostUrl = "http://${s.host}:${s.port}" }, modifier = Modifier.fillMaxWidth()) { Text("${s.name} (${s.host}:${s.port})") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { scope.launch { addHost() } }, enabled = newHostName.isNotBlank() && newHostUrl.isNotBlank()) { Text("追加") } },
            dismissButton = { TextButton(onClick = { showAddHost = false }) { Text("キャンセル") } }
        )
    }

    if (selectedMembership != null) {
        AlertDialog(
            onDismissRequest = { selectedMembership = null },
            title = { Text("プロフィール編集") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = editNickname, onValueChange = { editNickname = it }, label = { Text("このサーバーでの名前") }, modifier = Modifier.fillMaxWidth())
                    Text("VRM: ${if (editAvatarUrl.isNotEmpty()) File(editAvatarUrl).name else "未設定"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { vrmPicker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text("VRMを選択") }
                    if (assetVrms.isNotEmpty()) {
                        Text("またはアセットから選択", style = MaterialTheme.typography.bodySmall)
                        assetVrms.forEach { name ->
                            TextButton(onClick = {
                                scope.launch {
                                    val file = AssetVrmScanner.copyAssetVrmToInternal(context, name)
                                    if (file != null) editAvatarUrl = file.absolutePath
                                }
                            }) { Text(name) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { scope.launch { saveMembershipProfile() } }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { selectedMembership = null }) { Text("キャンセル") } }
        )
    }
}

private fun speakerLabel(id: Int): String = when (id) {
    58 -> "58: 猫使ビィ"; 3 -> "3: ずんだもん"; 2 -> "2: 四国めたん"; 8 -> "8: 春日部つむぎ"; 10 -> "10: 雨晴はう"; 14 -> "14: 冥鳴ひまり"
    else -> "$id: その他"
}

@Composable
private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
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
        context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(destFile).use { output -> input.copyTo(output) } }
        destFile.absolutePath
    } catch (e: Exception) { e.printStackTrace(); null }
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
    if (result == null) result = uri.path?.let { path -> val cut = path.lastIndexOf('/'); if (cut != -1) path.substring(cut + 1) else path }
    return result
}
