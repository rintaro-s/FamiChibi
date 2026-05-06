package com.nbks.famichibi.overlay

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nbks.famichibi.MainActivity
import com.nbks.famichibi.R
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.network.ChatMessage
import com.nbks.famichibi.network.ChatWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * フォアグラウンドサービス。
 * WindowManager オーバーレイではなく、VrmOverlayActivity を常時最前面に保つ。
 */
class VrmOverlayService : Service() {

    companion object {
        private const val TAG = "VrmOverlayService"
        private const val CHANNEL_ID = "famichibi_overlay"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, VrmOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VrmOverlayService::class.java)
            context.stopService(intent)
        }

        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return manager.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == VrmOverlayService::class.java.name }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val chatWebSocket = ChatWebSocket()
    private lateinit var prefs: PreferencesRepository

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        VrmOverlayActivity.start(this)
        connectWebSocket()

        return START_STICKY
    }

    private fun connectWebSocket() {
        serviceScope.launch {
            try {
                val serverUrl = prefs.serverUrl.first()
                val roomId = prefs.roomId.first()
                chatWebSocket.connect(serverUrl, roomId)
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket connect failed", e)
            }
        }

        serviceScope.launch {
            chatWebSocket.messages.collect { message ->
                handleMessage(message)
            }
        }
    }

    private fun handleMessage(message: ChatMessage) {
        sendBroadcast(
            Intent(VrmOverlayActivity.ACTION_SHOW_MESSAGE).apply {
                putExtra(VrmOverlayActivity.EXTRA_SENDER, message.sender)
                putExtra(VrmOverlayActivity.EXTRA_CONTENT, message.content)
                setPackage(packageName)
            }
        )

        val expression = when {
            "がんば" in message.content || "応援" in message.content || "頑張" in message.content -> "happy"
            "大丈夫" in message.content || "心配" in message.content -> "relaxed"
            "すごい" in message.content || "えらい" in message.content -> "surprised"
            "バカ" in message.content || "あほ" in message.content || "怒" in message.content -> "angry"
            else -> "happy"
        }

        sendBroadcast(
            Intent("com.nbks.famichibi.SET_EXPRESSION").apply {
                putExtra("expression", expression)
                putExtra("weight", 1.0f)
                setPackage(packageName)
            }
        )

        serviceScope.launch {
            delay(4000)
            sendBroadcast(
                Intent("com.nbks.famichibi.RESET_EXPRESSION").apply {
                    setPackage(packageName)
                }
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FamiChibi Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "家族アバターを画面に常駐表示"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val intent = Intent(this, VrmOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FamiChibi")
            .setContentText("アバターが画面で待機中...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            chatWebSocket.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting WebSocket", e)
        }
        serviceScope.cancel()
    }
}
