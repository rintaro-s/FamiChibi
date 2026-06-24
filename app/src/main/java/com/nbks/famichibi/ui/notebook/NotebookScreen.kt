package com.nbks.famichibi.ui.notebook

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.nbks.famichibi.network.ApiClient
import com.nbks.famichibi.network.ChatWebSocket
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.Parameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import com.nbks.famichibi.network.JsonConfig

@Serializable
data class NoteItem(val id: String = "", val content: String = "", val category: String = "general", val author_name: String = "", val created_at: String = "")
@Serializable
data class TaskItem(val id: String = "", val title: String = "", val assignee: String? = null, val due: String? = null, val done: Boolean = false, val created_at: String = "")
@Serializable
data class EventItem(val id: String = "", val title: String = "", val event_at: String = "", val created_at: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookScreen(
    navController: NavController,
    prefs: PreferencesRepository,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var channelId by remember { mutableStateOf("") }
    var host by remember { mutableStateOf<HostConfig?>(null) }
    var membership by remember { mutableStateOf<ServerMembership?>(null) }
    var userId by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }

    var notes by remember { mutableStateOf(listOf<NoteItem>()) }
    var tasks by remember { mutableStateOf(listOf<TaskItem>()) }
    var events by remember { mutableStateOf(listOf<EventItem>()) }

    var newNote by remember { mutableStateOf("") }
    var noteCategory by remember { mutableStateOf("general") }
    var newTask by remember { mutableStateOf("") }
    var newTaskAssignee by remember { mutableStateOf("") }
    var newEventTitle by remember { mutableStateOf("") }
    var newEventAt by remember { mutableStateOf("") }

    val connected = ChatWebSocket.connectionState.collectAsState().value

    suspend fun path() = "${host?.url}/s/${membership?.serverId}/channels/$channelId"
    suspend fun name() = membership?.nickname?.ifEmpty { userName } ?: userName

    fun refresh() {
        if (channelId.isEmpty() || host == null || membership == null) return
        scope.launch {
            try {
                notes = JsonConfig.json.decodeFromString(ApiClient.get("${path()}/notes", userId, name()).bodyAsText())
                tasks = JsonConfig.json.decodeFromString(ApiClient.get("${path()}/tasks", userId, name()).bodyAsText())
                events = JsonConfig.json.decodeFromString(ApiClient.get("${path()}/events", userId, name()).bodyAsText())
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("読み込みに失敗しました")
            }
        }
    }

    LaunchedEffect(Unit) {
        channelId = prefs.activeChannelId.first()
        userId = prefs.userId.first()
        userName = prefs.userName.first()
        val memberships = prefs.serverMemberships.first()
        membership = memberships.lastOrNull()
        val hosts = prefs.hosts.first()
        host = hosts.find { it.id == membership?.hostId }
        refresh()
    }

    fun addNote() {
        if (channelId.isEmpty() || newNote.isBlank() || host == null || membership == null) return
        scope.launch {
            try {
                ApiClient.postForm("${path()}/notes", userId, name(), Parameters.build {
                    append("content", newNote); append("category", noteCategory)
                })
                newNote = ""; refresh()
            } catch (_: Exception) {}
        }
    }

    fun addTask() {
        if (channelId.isEmpty() || newTask.isBlank() || host == null || membership == null) return
        scope.launch {
            try {
                ApiClient.postForm("${path()}/tasks", userId, name(), Parameters.build {
                    append("title", newTask); append("assignee", newTaskAssignee)
                })
                newTask = ""; newTaskAssignee = ""; refresh()
            } catch (_: Exception) {}
        }
    }

    fun doneTask(id: String) {
        scope.launch {
            try { ApiClient.putForm("${path()}/tasks/$id", userId, name(), Parameters.build { append("done", "true") }); refresh() } catch (_: Exception) {}
        }
    }

    fun addEvent() {
        if (channelId.isEmpty() || newEventTitle.isBlank() || newEventAt.isBlank() || host == null || membership == null) return
        scope.launch {
            try {
                ApiClient.postForm("${path()}/events", userId, name(), Parameters.build {
                    append("title", newEventTitle); append("event_at", newEventAt)
                })
                newEventTitle = ""; newEventAt = ""; refresh()
            } catch (_: Exception) {}
        }
    }

    fun summarizeNotes() {
        if (channelId.isEmpty() || host == null || membership == null) return
        scope.launch {
            try {
                ApiClient.post("${path()}/summarize", userId, name())
                refresh()
            } catch (e: Exception) { snackbarHostState.showSnackbar("要約に失敗しました") }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("家族ノート", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Row {
                if (tab == 0) TextButton(onClick = { summarizeNotes() }, enabled = connected) { Text("要約") }
                TextButton(onClick = { refresh() }, enabled = connected) { Text("更新") }
                TextButton(onClick = { navController.popBackStack() }) { Text("戻る") }
            }
        }

        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("メモ") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("お手伝い") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("予定") })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> NotesTab(notes, newNote, { newNote = it }, noteCategory, { noteCategory = it }, ::addNote)
                1 -> TasksTab(tasks, newTask, { newTask = it }, newTaskAssignee, { newTaskAssignee = it }, ::addTask, ::doneTask)
                2 -> EventsTab(events, newEventTitle, { newEventTitle = it }, newEventAt, { newEventAt = it }, ::addEvent)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NotesTab(notes: List<NoteItem>, newNote: String, onNewNoteChange: (String) -> Unit, category: String, onCategoryChange: (String) -> Unit, onAdd: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = newNote, onValueChange = onNewNoteChange, label = { Text("メモ") }, modifier = Modifier.weight(1f), singleLine = true)
            DropdownMenuBox(category, onCategoryChange)
            Button(onClick = onAdd) { Text("追加") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        notes.forEach { note ->
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(note.content, style = MaterialTheme.typography.bodyLarge)
                    Text("${categoryLabel(note.category)} · ${note.author_name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun DropdownMenuBox(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(categoryLabel(selected)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("general", "shopping", "appointment").forEach { c ->
                DropdownMenuItem(text = { Text(categoryLabel(c)) }, onClick = { onSelect(c); expanded = false })
            }
        }
    }
}

private fun categoryLabel(c: String): String = when (c) { "shopping" -> "買い物"; "appointment" -> "予定"; "task" -> "お手伝い"; else -> "一般" }

@Composable
private fun TasksTab(tasks: List<TaskItem>, newTask: String, onNewTaskChange: (String) -> Unit, newAssignee: String, onNewAssigneeChange: (String) -> Unit, onAdd: () -> Unit, onDone: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = newTask, onValueChange = onNewTaskChange, label = { Text("お手伝い") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(value = newAssignee, onValueChange = onNewAssigneeChange, label = { Text("担当") }, modifier = Modifier.weight(0.6f), singleLine = true)
            Button(onClick = onAdd) { Text("追加") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        tasks.forEach { task ->
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.bodyLarge, textDecoration = if (task.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                    val meta = listOfNotNull(task.assignee?.let { "担当: $it" }, task.due?.take(16)?.replace("T", " "))
                    if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!task.done) TextButton(onClick = { onDone(task.id) }) { Text("完了") }
                else Text("完了", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun EventsTab(events: List<EventItem>, newTitle: String, onNewTitleChange: (String) -> Unit, newAt: String, onNewAtChange: (String) -> Unit, onAdd: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = newTitle, onValueChange = onNewTitleChange, label = { Text("予定タイトル") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(value = newAt, onValueChange = onNewAtChange, label = { Text("日時") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = onAdd) { Text("追加") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        events.forEach { ev ->
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(ev.title, style = MaterialTheme.typography.bodyLarge)
                    Text(ev.event_at.take(16).replace("T", " "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}
