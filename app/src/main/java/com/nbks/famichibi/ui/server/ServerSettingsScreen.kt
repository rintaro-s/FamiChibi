package com.nbks.famichibi.ui.server

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
data class ServerSettingsInfo(
    val id: String = "",
    val name: String = "",
    val icon: String = "",
    val welcome_message: String = "",
    val has_password: Boolean = false,
    val invite_only: Boolean = false,
    val my_role: String? = null,
    val owner_id: String = "",
    val permissions: List<String> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(
    navController: NavController,
    prefs: PreferencesRepository,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    var userId by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var host by remember { mutableStateOf<HostConfig?>(null) }
    var membership by remember { mutableStateOf<ServerMembership?>(null) }
    var server by remember { mutableStateOf<ServerSettingsInfo?>(null) }

    var name by remember { mutableStateOf("") }
    var welcome by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var inviteOnly by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    val canManage = server?.permissions?.contains("manage_server") == true || server?.my_role == "owner"
    val isOwner = server?.my_role == "owner"

    suspend fun loadState() {
        userId = prefs.userId.first()
        userName = prefs.userName.first().ifBlank { "お兄ちゃん" }
        val hosts = prefs.hosts.first()
        val sid = prefs.activeServerId.first()
        val hid = prefs.activeHostId.first()
        host = hosts.find { it.id == hid }
        membership = prefs.serverMemberships.first().find { it.serverId == sid && it.hostId == hid }
    }

    suspend fun refresh() {
        loadState()
        val h = host ?: return
        val m = membership ?: return
        try {
            val res = ApiClient.get("${h.url}/s/${m.serverId}", userId, m.nickname.ifEmpty { userName })
            if (res.status == HttpStatusCode.OK) {
                server = JsonConfig.json.decodeFromString(res.bodyAsText())
                name = server?.name ?: ""
                welcome = server?.welcome_message ?: ""
                icon = server?.icon ?: ""
                inviteOnly = server?.invite_only ?: false
            }
        } catch (_: Exception) {
            snackbarHostState.showSnackbar("サーバー情報の取得に失敗しました")
        }
    }

    suspend fun save() {
        val h = host ?: return
        val m = membership ?: return
        isLoading = true
        try {
            val params = Parameters.build {
                append("name", name)
                append("welcome_message", welcome)
                append("icon", icon)
                append("invite_only", if (inviteOnly) "true" else "false")
                if (password.isNotBlank()) append("password", password)
            }
            val res = ApiClient.putForm("${h.url}/s/${m.serverId}", userId, m.nickname.ifEmpty { userName }, params)
            if (res.status == HttpStatusCode.OK) {
                snackbarHostState.showSnackbar("保存しました")
                val list = prefs.serverMemberships.first().map {
                    if (it.serverId == m.serverId && it.hostId == m.hostId) it.copy(serverName = name) else it
                }
                prefs.setServerMemberships(list)
            } else {
                snackbarHostState.showSnackbar("保存に失敗しました")
            }
        } catch (_: Exception) {
            snackbarHostState.showSnackbar("保存に失敗しました")
        } finally { isLoading = false }
    }

    suspend fun leave() {
        val h = host ?: return
        val m = membership ?: return
        isLoading = true
        try {
            val res = ApiClient.postForm("${h.url}/s/${m.serverId}/leave", userId, m.nickname.ifEmpty { userName }, Parameters.build { })
            if (res.status == HttpStatusCode.OK) {
                val list = prefs.serverMemberships.first().filterNot { it.serverId == m.serverId && it.hostId == m.hostId }
                prefs.setServerMemberships(list)
                snackbarHostState.showSnackbar("退会しました")
                navController.navigate("home") { popUpTo("home") { inclusive = true } }
            } else {
                snackbarHostState.showSnackbar("退会に失敗しました")
            }
        } catch (_: Exception) {
            snackbarHostState.showSnackbar("退会に失敗しました")
        } finally { isLoading = false }
    }

    suspend fun deleteServer() {
        val h = host ?: return
        val m = membership ?: return
        isLoading = true
        try {
            val res = ApiClient.delete("${h.url}/s/${m.serverId}", userId, m.nickname.ifEmpty { userName })
            if (res.status == HttpStatusCode.OK) {
                val list = prefs.serverMemberships.first().filterNot { it.serverId == m.serverId && it.hostId == m.hostId }
                prefs.setServerMemberships(list)
                snackbarHostState.showSnackbar("サーバーを削除しました")
                navController.navigate("home") { popUpTo("home") { inclusive = true } }
            } else {
                snackbarHostState.showSnackbar("削除に失敗しました")
            }
        } catch (_: Exception) {
            snackbarHostState.showSnackbar("削除に失敗しました")
        } finally { isLoading = false }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("サーバー設定", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (server == null || isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("サーバー名") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = canManage)
                OutlinedTextField(value = welcome, onValueChange = { welcome = it }, label = { Text("ウェルカムメッセージ") }, modifier = Modifier.fillMaxWidth(), minLines = 2, enabled = canManage)
                OutlinedTextField(value = icon, onValueChange = { icon = it }, label = { Text("アイコンURL") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = canManage)
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("参加パスワード（変更時のみ入力）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = canManage)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = inviteOnly, onCheckedChange = { inviteOnly = it }, enabled = canManage)
                    Text("招待コードのみで参加可能", style = MaterialTheme.typography.bodySmall)
                }

                if (canManage) {
                    Button(onClick = { scope.launch { save() } }, modifier = Modifier.fillMaxWidth(), enabled = name.isNotBlank()) { Text("保存") }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Button(
                    onClick = { scope.launch { leave() } },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("サーバーから退会") }

                if (isOwner) {
                    OutlinedButton(
                        onClick = { scope.launch { deleteServer() } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("サーバーを削除") }
                }
            }
        }
    }
}
