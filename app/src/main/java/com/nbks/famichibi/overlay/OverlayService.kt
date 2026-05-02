package com.nbks.famichibi.overlay

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class OverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var overlayView: OverlayView? = null
    private var httpServer: VrmHttpServer? = null
    private var httpPort: Int = 0
    private val chatWebSocket = ChatWebSocket()
    private lateinit var prefs: PreferencesRepository

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = PreferencesRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        if (overlayView == null) {
            setupOverlay()
            connectWebSocket()
        }

        return START_STICKY
    }

    private fun setupOverlay() {
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
            width = 320
            height = 500
        }

        overlayView = OverlayView(this, windowManager, layoutParams).apply {
            onTapListener = {
                // Handle tap
            }
        }

        // Start local HTTP server for VRM delivery
        httpServer = VrmHttpServer(0, this)
        try {
            httpServer?.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            httpPort = httpServer?.listeningPort ?: 8765
            Log.d(TAG, "HTTP server started on port $httpPort")

            overlayView?.loadViewerUrl("http://localhost:$httpPort/")
            overlayView?.onModelLoaded = {
                serviceScope.launch {
                    val userVrm = prefs.vrmPath.first()
                    val vrmFileName = if (userVrm.isNotEmpty()) {
                        File(userVrm).name
                    } else {
                        "AvatarSample_M.vrm"
                    }
                    val vrmUrl = "http://localhost:$httpPort/vrm/$vrmFileName"
                    Log.d(TAG, "Loading VRM from: $vrmUrl")
                    overlayView?.loadVrm(vrmUrl)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
        }

        windowManager.addView(overlayView, layoutParams)

        serviceScope.launch {
            val decorations = prefs.decorations.first()
            overlayView?.updateDecorations(decorations)
        }
    }

    private fun connectWebSocket() {
        serviceScope.launch {
            val serverUrl = prefs.serverUrl.first()
            val roomId = prefs.roomId.first()
            chatWebSocket.connect(serverUrl, roomId)
        }

        serviceScope.launch {
            chatWebSocket.messages.collect { message ->
                handleMessage(message)
            }
        }
    }

    private fun handleMessage(message: ChatMessage) {
        overlayView?.showMessage(message.sender, message.content)
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
        val intent = Intent(this, MainActivity::class.java).apply {
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
        chatWebSocket.disconnect()
        serviceScope.cancel()
        overlayView?.let {
            windowManager.removeView(it)
        }
        overlayView = null
        try {
            httpServer?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping HTTP server", e)
        }
        httpServer = null
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "famichibi_overlay"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.stopService(intent)
        }

        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return manager.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == OverlayService::class.java.name }
        }
    }
}
