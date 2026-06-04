package com.nbks.famichibi.ui.home

import androidx.compose.material3.ExperimentalMaterial3Api
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.overlay.VrmOverlayService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    prefs: PreferencesRepository,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var userName by remember { mutableStateOf("お兄ちゃん") }
    var roomId by remember { mutableStateOf("") }
    var isOverlayRunning by remember { mutableStateOf(VrmOverlayService.isRunning(context)) }
    var showBriefing by remember { mutableStateOf(false) }
    var briefingText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        userName = prefs.userName.first()
        roomId = prefs.roomId.first()
        val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        if (prefs.lastBriefingDate.first() != today) {
            briefingText = buildBriefing(userName, roomId.isNotEmpty())
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
        ) {
            Text(
                text = "こんにちは、${userName}さん",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "家族とアバターが待っています。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Column(modifier = Modifier.fillMaxWidth()) {
            HomeActionRow(
                icon = Icons.Default.Videocam,
                label = "部屋を探す / 作る",
                onClick = { navController.navigate("rooms") }
            )
            HomeActionRow(
                icon = Icons.AutoMirrored.Filled.Message,
                label = if (roomId.isNotEmpty()) "チャットを開く" else "チャットをはじめる",
                enabled = roomId.isNotEmpty(),
                onClick = { navController.navigate("chat") }
            )
            HomeActionRow(
                icon = if (isOverlayRunning) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                label = if (isOverlayRunning) "アバターを停止" else "アバターを表示",
                onClick = {
                    if (isOverlayRunning) {
                        VrmOverlayService.stop(context)
                    } else {
                        if (!Settings.canDrawOverlays(context)) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } else {
                            VrmOverlayService.start(context)
                        }
                    }
                }
            )
            HomeActionRow(
                icon = Icons.Default.Book,
                label = "家族ノート",
                onClick = { navController.navigate("notebook") }
            )
            HomeActionRow(
                icon = Icons.Default.Settings,
                label = "設定",
                onClick = { navController.navigate("settings") }
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "今日のひとこと",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "部屋に参加して、家族とメッセージをやり取りしてみましょう。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showBriefing) {
        ModalBottomSheet(onDismissRequest = { showBriefing = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)) {
                Text(
                    text = "今日のファミリー情報",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = briefingText, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { showBriefing = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("閉じる") }
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
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.weight(1f)
        )
        if (enabled) {
            TextButton(onClick = onClick) { Text("開く") }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

private fun buildBriefing(userName: String, hasRoom: Boolean): String {
    return if (hasRoom) {
        "${userName}さん、おはようございます。\n今日も家族とつながりましょう。"
    } else {
        "${userName}さん、おはようございます。\nまずは部屋を作成するか、参加してみましょう。"
    }
}
