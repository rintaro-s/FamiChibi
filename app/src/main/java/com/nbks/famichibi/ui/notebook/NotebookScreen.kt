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
import com.nbks.famichibi.data.PreferencesRepository
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

@Serializable
data class NoteItem(
    val id: String,
    val content: String,
    val category: String,
    val created_by: String,
    val created_at: String,
    val due_at: String? = null,
    val is_pinned: Boolean = false
)

@Serializable
data class TaskItem(
    val id: String,
    val title: String,
    val assignee_user_id: String? = null,
    val assignee_name: String? = null,
    val due_at: String? = null,
    val done: Boolean = false,
    val created_by: String,
    val created_at: String
)

@Serializable
data class EventItem(
    val id: String,
    val title: String,
    val event_at: String,
    val created_by: String,
    val created_at: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookScreen(
    prefs: PreferencesRepository,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var roomId by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("http://10.0.2.2:8000") }
    var serverId by remember { mutableStateOf("default") }
    var userName by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    var aiEnabled by remember { mutableStateOf(false) }

    var notes by remember { mutableStateOf(listOf<NoteItem>()) }
    var tasks by remember { mutableStateOf(listOf<TaskItem>()) }
    var events by remember { mutableStateOf(listOf<EventItem>()) }

    var newNote by remember { mutableStateOf("") }
    var noteCategory by remember { mutableStateOf("general") }
    var newTask by remember { mutableStateOf("") }
    var newTaskAssignee by remember { mutableStateOf("") }
    var newEventTitle by remember { mutableStateOf("") }
    var newEventAt by remember { mutableStateOf("") }

    val httpClient = remember { HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } } }

    fun refresh() {
        if (roomId.isEmpty()) return
        scope.launch {
            try {
                notes = Json.decodeFromString(httpClient.get("$serverUrl/s/$serverId/rooms/$roomId/notes").bodyAsText())
                tasks = Json.decodeFromString(httpClient.get("$serverUrl/s/$serverId/rooms/$roomId/tasks").bodyAsText())
                events = Json.decodeFromString(httpClient.get("$serverUrl/s/$serverId/rooms/$roomId/events").bodyAsText())
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("読み込みに失敗しました")
            }
        }
    }

    LaunchedEffect(Unit) {
        roomId = prefs.roomId.first()
        serverUrl = prefs.serverUrl.first()
        serverId = prefs.activeServerId.first()
        userName = prefs.userName.first()
        aiEnabled = prefs.aiEnabled.first()
        refresh()
    }

    fun addNote() {
        if (roomId.isEmpty() || newNote.isBlank()) return
        scope.launch {
            try {
                httpClient.submitForm(
                    url = "$serverUrl/s/$serverId/rooms/$roomId/notes",
                    formParameters = Parameters.build {
                        append("content", newNote)
                        append("category", noteCategory)
                        append("created_by", userName)
                    }
                )
                newNote = ""
                refresh()
            } catch (_: Exception) {}
        }
    }

    fun pinNote(id: String) {
        scope.launch {
            try {
                httpClient.post("$serverUrl/s/$serverId/rooms/$roomId/notes/$id/pin")
                refresh()
            } catch (_: Exception) {}
        }
    }

    fun addTask() {
        if (roomId.isEmpty() || newTask.isBlank()) return
        scope.launch {
            try {
                httpClient.submitForm(
                    url = "$serverUrl/s/$serverId/rooms/$roomId/tasks",
                    formParameters = Parameters.build {
                        append("title", newTask)
                        append("created_by", userName)
                        append("assignee_name", newTaskAssignee)
                    }
                )
                newTask = ""
                newTaskAssignee = ""
                refresh()
            } catch (_: Exception) {}
        }
    }

    fun doneTask(id: String) {
        scope.launch {
            try {
                httpClient.post("$serverUrl/s/$serverId/rooms/$roomId/tasks/$id/done")
                refresh()
            } catch (_: Exception) {}
        }
    }

    fun addEvent() {
        if (roomId.isEmpty() || newEventTitle.isBlank() || newEventAt.isBlank()) return
        scope.launch {
            try {
                httpClient.submitForm(
                    url = "$serverUrl/s/$serverId/rooms/$roomId/events",
                    formParameters = Parameters.build {
                        append("title", newEventTitle)
                        append("event_at", newEventAt)
                        append("created_by", userName)
                    }
                )
                newEventTitle = ""
                newEventAt = ""
                refresh()
            } catch (_: Exception) {}
        }
    }

    fun summarizeNotes() {
        if (roomId.isEmpty()) return
        scope.launch {
            try {
                val context = notes.joinToString("\n") { it.content }
                if (context.isBlank()) {
                    snackbarHostState.showSnackbar("メモがありません")
                    return@launch
                }
                val response = httpClient.submitForm(
                    url = "$serverUrl/s/$serverId/summarize",
                    formParameters = Parameters.build { append("context", context) }
                )
                if (response.status == HttpStatusCode.OK) {
                    val body = Json.decodeFromString<Map<String, String>>(response.bodyAsText())
                    val summary = body["summary"] ?: "要約できませんでした"
                    snackbarHostState.showSnackbar("要約: $summary")
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("要約に失敗しました")
            }
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
            Text("家族ノート", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Row {
                if (aiEnabled && tab == 0) {
                    TextButton(onClick = { summarizeNotes() }) { Text("要約") }
                }
                TextButton(onClick = { refresh() }) { Text("更新") }
            }
        }

        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("メモ") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("お手伝い") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("予定") })
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            when (tab) {
                0 -> NotesTab(notes, newNote, { newNote = it }, noteCategory, { noteCategory = it }, ::addNote, ::pinNote)
                1 -> TasksTab(tasks, newTask, { newTask = it }, newTaskAssignee, { newTaskAssignee = it }, ::addTask, ::doneTask)
                2 -> EventsTab(events, newEventTitle, { newEventTitle = it }, newEventAt, { newEventAt = it }, ::addEvent)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NotesTab(
    notes: List<NoteItem>,
    newNote: String,
    onNewNoteChange: (String) -> Unit,
    category: String,
    onCategoryChange: (String) -> Unit,
    onAdd: () -> Unit,
    onPin: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(value = newNote, onValueChange = onNewNoteChange, label = { Text("メモ") }, modifier = Modifier.weight(1f), singleLine = true)
            DropdownMenuBox(category, onCategoryChange)
            Button(onClick = onAdd) { Text("追加") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        notes.forEach { note ->
            val pinned = note.is_pinned
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(note.content, style = MaterialTheme.typography.bodyLarge, fontWeight = if (pinned) FontWeight.SemiBold else FontWeight.Normal)
                    Text("${categoryLabel(note.category)} · ${note.created_by}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onPin(note.id) }) { Text(if (pinned) "解除" else "ピン") }
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

private fun categoryLabel(c: String): String = when (c) {
    "shopping" -> "買い物"
    "appointment" -> "予定"
    "task" -> "お手伝い"
    else -> "一般"
}

@Composable
private fun TasksTab(
    tasks: List<TaskItem>,
    newTask: String,
    onNewTaskChange: (String) -> Unit,
    newAssignee: String,
    onNewAssigneeChange: (String) -> Unit,
    onAdd: () -> Unit,
    onDone: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(value = newTask, onValueChange = onNewTaskChange, label = { Text("お手伝い") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(value = newAssignee, onValueChange = onNewAssigneeChange, label = { Text("担当") }, modifier = Modifier.weight(0.6f), singleLine = true)
            Button(onClick = onAdd) { Text("追加") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        tasks.forEach { task ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.bodyLarge, textDecoration = if (task.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                    val meta = listOfNotNull(task.assignee_name?.let { "担当: $it" }, task.due_at?.take(16)?.replace("T", " "))
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
private fun EventsTab(
    events: List<EventItem>,
    newTitle: String,
    onNewTitleChange: (String) -> Unit,
    newAt: String,
    onNewAtChange: (String) -> Unit,
    onAdd: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(value = newTitle, onValueChange = onNewTitleChange, label = { Text("予定タイトル") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(value = newAt, onValueChange = onNewAtChange, label = { Text("日時") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = onAdd) { Text("追加") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        events.forEach { ev ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(ev.title, style = MaterialTheme.typography.bodyLarge)
                    Text(ev.event_at.take(16).replace("T", " "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}
