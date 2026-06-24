package com.nbks.famichibi.ui.channels

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.nbks.famichibi.data.HostConfig
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.data.ServerMembership
import com.nbks.famichibi.network.ApiClient
import com.nbks.famichibi.network.ChatEvent
import com.nbks.famichibi.network.ChatMessage
import com.nbks.famichibi.network.ChatWebSocket
import com.nbks.famichibi.util.formatName
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import com.nbks.famichibi.network.JsonConfig
import java.util.*

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChannelScreen(
    navController: NavController,
    prefs: PreferencesRepository,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var userId by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var membership by remember { mutableStateOf<ServerMembership?>(null) }
    var host by remember { mutableStateOf<HostConfig?>(null) }
    var channelId by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var chatInput by remember { mutableStateOf("") }
    var quickPhrases by remember { mutableStateOf(listOf<String>()) }
    var users by remember { mutableStateOf(listOf<com.nbks.famichibi.network.RoomUser>()) }
    var ttsEnabled by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }

    var showReactionSheet by remember { mutableStateOf(false) }
    var selectedMessageId by remember { mutableStateOf("") }
    var showWhisperSheet by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    val seenMessageIds = remember { mutableSetOf<String>() }

    val listState = rememberLazyListState()
    val micPermission = rememberPermissionState(android.Manifest.permission.RECORD_AUDIO)
    var isListening by remember { mutableStateOf(false) }

    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val t = TextToSpeech(context) { }
        tts.value = t
        onDispose { t.stop(); t.shutdown() }
    }

    fun speak(text: String) {
        if (!ttsEnabled) return
        tts.value?.let { engine ->
            engine.language = Locale.JAPAN
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "msg")
        }
    }

    fun startListening() {
        if (!micPermission.status.isGranted) { micPermission.launchPermissionRequest(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { scope.launch { snackbarHostState.showSnackbar("音声認識が利用できません") }; return }
        isListening = true
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPAN.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) { isListening = false }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    chatInput = matches[0]
                    if (chatInput.isNotBlank() && connected) {
                        ChatWebSocket.sendMessage(chatInput)
                        chatInput = ""
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(intent)
    }

    LaunchedEffect(Unit) {
        userId = prefs.userId.first()
        userName = prefs.userName.first()
        val memberships = prefs.serverMemberships.first()
        membership = memberships.lastOrNull()
        channelId = prefs.activeChannelId.first()
        val hosts = prefs.hosts.first()
        host = hosts.find { it.id == membership?.hostId }
        quickPhrases = prefs.quickPhrases.first()
        ttsEnabled = prefs.ttsEnabled.first()
        val h = host ?: return@LaunchedEffect
        val m = membership ?: return@LaunchedEffect
        if (channelId.isNotEmpty()) {
            ChatWebSocket.connect(h.url, m.serverId, channelId, userId, m.nickname.ifEmpty { userName }, "")
            try {
                val response = ApiClient.get("${h.url}/s/${m.serverId}/channels/$channelId/messages?limit=100", userId, m.nickname.ifEmpty { userName })
                if (response.status == HttpStatusCode.OK) messages = JsonConfig.json.decodeFromString(response.bodyAsText())
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    LaunchedEffect(Unit) {
        ChatWebSocket.events.collect { event ->
            when (event) {
                is ChatEvent.Message -> {
                    if (event.msg.id.isNotBlank() && !seenMessageIds.add(event.msg.id)) return@collect
                    messages = messages + event.msg
                    if (event.msg.type == "agent") speak(event.msg.content)
                }
                is ChatEvent.UserJoined, is ChatEvent.UserLeft -> users = ChatWebSocket.roomUsers.value
                is ChatEvent.Joined -> { users = event.users; connected = true }
                is ChatEvent.Whisper -> messages = messages + ChatMessage(
                    sender = "${event.fromUserName}（ささやき）", sender_id = event.fromUserId,
                    content = event.content, type = "whisper", timestamp = event.timestamp
                )
                is ChatEvent.Nudge -> messages = messages + ChatMessage(
                    sender = "システム", sender_id = "", content = "${formatName(event.fromUserName)}があなたを呼んでいます",
                    type = "agent", timestamp = event.timestamp
                )
                is ChatEvent.Error -> snackbarHostState.showSnackbar(event.message)
                else -> {}
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { ChatWebSocket.disconnect() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(membership?.serverName ?: "チャット", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${users.size}人在室 ${if (connected) "" else "· 未接続"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row {
                TextButton(onClick = { showWhisperSheet = true }, enabled = connected, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("ささやき", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { navController.navigate("notebook") }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("ノート", style = MaterialTheme.typography.bodySmall) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(messages, key = { it.id }) { msg ->
                val isMe = msg.sender_id == userId
                val bg = when {
                    msg.type == "agent" -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    isMe -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.surface
                }
                Column(
                    modifier = Modifier.fillMaxWidth().background(bg)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (msg.id.isNotBlank()) { selectedMessageId = msg.id; showReactionSheet = true } }
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                ) {
                    Text(msg.sender, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(msg.content, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }

        if (quickPhrases.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickPhrases.forEach { phrase ->
                    OutlinedButton(onClick = { if (connected) ChatWebSocket.sendMessage(phrase) }, enabled = connected) { Text(phrase) }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = { startListening() },
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = if (isListening) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant),
                enabled = connected
            ) { Icon(Icons.Default.Mic, contentDescription = "音声入力") }
            OutlinedTextField(
                value = chatInput, onValueChange = { chatInput = it },
                placeholder = { Text("メッセージ…") }, modifier = Modifier.weight(1f), singleLine = true,
                enabled = connected
            )
            IconButton(
                onClick = {
                    if (chatInput.isNotBlank() && connected && !isSending) {
                        isSending = true
                        ChatWebSocket.sendMessage(chatInput)
                        chatInput = ""
                        scope.launch { kotlinx.coroutines.delay(300); isSending = false }
                    }
                },
                enabled = chatInput.isNotBlank() && connected && !isSending,
                modifier = Modifier.size(44.dp)
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信") }
        }
    }

    if (showReactionSheet) {
        ModalBottomSheet(onDismissRequest = { showReactionSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp)) {
                Text("リアクション", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("いいね", "好き", "笑", "驚き", "応援").forEach { label ->
                        Button(
                            onClick = { ChatWebSocket.sendReaction(selectedMessageId, label); showReactionSheet = false },
                            modifier = Modifier.weight(1f)
                        ) { Text(label, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showWhisperSheet) {
        var target by remember { mutableStateOf<com.nbks.famichibi.network.RoomUser?>(null) }
        var whisperText by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { showWhisperSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp)) {
                Text("ささやきを送る", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(12.dp))
                users.filter { it.user_id != userId }.forEach { u ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(u.user_name, style = MaterialTheme.typography.bodyLarge)
                        RadioButton(selected = target?.user_id == u.user_id, onClick = { target = u })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = whisperText, onValueChange = { whisperText = it }, label = { Text("メッセージ") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val t = target ?: return@Button
                        ChatWebSocket.sendWhisper(t.user_id, whisperText)
                        messages = messages + ChatMessage(
                            sender = "${userName} → ${t.user_name}（ささやき）", sender_id = userId,
                            content = whisperText, type = "whisper", timestamp = java.time.Instant.now().toString()
                        )
                        whisperText = ""; showWhisperSheet = false
                    },
                    enabled = target != null && whisperText.isNotBlank() && connected,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("送信") }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
