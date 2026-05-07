package com.nbks.famichibi.overlay

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.nbks.famichibi.MainActivity
import com.nbks.famichibi.R
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.network.ChatMessage
import com.nbks.famichibi.network.ChatWebSocket
import com.nbks.famichibi.vrm.GltfParser
import com.nbks.famichibi.vrm.VrmGlRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * フォアグラウンドサービス。
 * WindowManager + GLSurfaceView でオーバーレイを表示する。
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

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var glSurfaceView: GLSurfaceView? = null
    private var renderer: VrmGlRenderer? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var startRawX = 0f
    private var startRawY = 0f
    private var initialX = 0
    private var initialY = 0

    private val choreographer = Choreographer.getInstance()
    private var isDragging = false
    private var isMoving = false
    private var targetX = 0f
    private var targetY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var screenWidth = 0
    private var screenHeight = 0
    private val moveSpeed = 200f // pixels per second

    private val moveCallback = object : Choreographer.FrameCallback {
        private var lastFrameTime = 0L
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)
            if (lastFrameTime == 0L) {
                lastFrameTime = frameTimeNanos
                return
            }
            if (isDragging) return

            val deltaSec = (frameTimeNanos - lastFrameTime) / 1_000_000_000f
            lastFrameTime = frameTimeNanos

            val dx = targetX - currentX
            val dy = targetY - currentY
            val dist = kotlin.math.hypot(dx, dy)

            if (dist < 5f) {
                if (isMoving) {
                    isMoving = false
                    renderer?.animationState = VrmGlRenderer.AnimationState.IDLE
                    serviceScope.launch {
                        delay(500L + kotlin.random.Random.nextLong(2000))
                        pickNewTarget()
                    }
                }
            } else {
                isMoving = true
                renderer?.animationState = VrmGlRenderer.AnimationState.WALK
                val moveDist = moveSpeed * deltaSec
                val ratio = if (dist > 0f) moveDist / dist else 0f
                currentX += dx * kotlin.math.min(ratio, 1f)
                currentY += dy * kotlin.math.min(ratio, 1f)

                val params = layoutParams ?: return
                params.x = currentX.toInt()
                params.y = currentY.toInt()
                windowManager?.updateViewLayout(overlayView, params)

                val angle = (kotlin.math.atan2(dx.toDouble(), dy.toDouble()) * 180.0 / kotlin.math.PI).toFloat()
                renderer?.rotationY = angle
            }
        }
    }

    private fun pickNewTarget() {
        val viewW = layoutParams?.width ?: return
        val viewH = layoutParams?.height ?: return
        targetX = kotlin.random.Random.nextInt(0, screenWidth - viewW).toFloat()
        targetY = kotlin.random.Random.nextInt(0, screenHeight - viewH).toFloat()
        isMoving = true
        renderer?.animationState = VrmGlRenderer.AnimationState.WALK
    }

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

        setupOverlay()
        connectWebSocket()

        return START_STICKY
    }

    private fun setupOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_view, null)
        overlayView = view

        val glView = view.findViewById<GLSurfaceView>(R.id.surfaceView)
        glSurfaceView = glView

        glView.setEGLContextClientVersion(3)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glView.holder.setFormat(PixelFormat.TRANSLUCENT)

        val r = VrmGlRenderer()
        renderer = r
        glView.setRenderer(r)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        val params = WindowManager.LayoutParams(
            dpToPx(90),
            dpToPx(120),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.START or Gravity.TOP

        screenWidth = resources.displayMetrics.widthPixels
        screenHeight = resources.displayMetrics.heightPixels
        currentX = (screenWidth / 2f - params.width / 2f)
        currentY = (screenHeight / 2f - params.height / 2f)
        params.x = currentX.toInt()
        params.y = currentY.toInt()
        layoutParams = params

        setupDrag(view, params)
        wm.addView(view, params)

        pickNewTarget()
        choreographer.postFrameCallback(moveCallback)

        loadVrm(r)
    }

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = true
                    renderer?.animationState = VrmGlRenderer.AnimationState.IDLE
                    startRawX = event.rawX
                    startRawY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    params.x = initialX + dx
                    params.y = initialY + dy
                    currentX = params.x.toFloat()
                    currentY = params.y.toFloat()
                    windowManager?.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    if (kotlin.math.hypot(event.rawX - startRawX, event.rawY - startRawY) < 15f) {
                        triggerJumpReaction()
                    }
                    serviceScope.launch {
                        delay(800)
                        pickNewTarget()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun triggerJumpReaction() {
        // TODO: expression support
    }

    private fun loadVrm(renderer: VrmGlRenderer) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val userVrm = prefs.vrmPath.first()
                val vrmFile = if (userVrm.isNotEmpty()) File(userVrm) else copyAssetVrm()
                if (!vrmFile.exists()) {
                    Log.e(TAG, "VRM file not found: ${vrmFile.absolutePath}")
                    return@launch
                }
                Log.d(TAG, "Loading VRM: ${vrmFile.absolutePath}, size=${vrmFile.length()}")
                val bytes = vrmFile.readBytes()
                val result = GltfParser.parse(bytes)
                withContext(Dispatchers.Main) {
                    if (result != null) {
                        val (root, meshes) = result
                        renderer.loadModel(root, meshes)
                        Log.d(TAG, "VRM loaded: ${meshes.size} meshes")
                    } else {
                        Log.e(TAG, "Failed to parse VRM")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load VRM", e)
            }
        }
    }

    private fun copyAssetVrm(): File {
        val destFile = File(filesDir, "AvatarSample_M.vrm")
        if (!destFile.exists()) {
            try {
                assets.open("vrm-viewer/AvatarSample_M.vrm").use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy asset VRM", e)
            }
        }
        return destFile
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
        serviceScope.launch(Dispatchers.Main) {
            showMessage(message.sender, message.content)
        }

        val expression = when {
            "がんば" in message.content || "応援" in message.content || "頑張" in message.content -> "happy"
            "大丈夫" in message.content || "心配" in message.content -> "relaxed"
            "すごい" in message.content || "えらい" in message.content -> "surprised"
            "バカ" in message.content || "あほ" in message.content || "怒" in message.content -> "angry"
            else -> "happy"
        }

        serviceScope.launch {
            delay(4000)
        }
    }

    private fun showMessage(sender: String, message: String) {
        val bubble = overlayView?.findViewById<TextView>(R.id.speechBubble) ?: return
        bubble.text = "$sender\n$message"
        bubble.visibility = View.VISIBLE
        bubble.alpha = 0f
        bubble.translationY = 20f
        bubble.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .start()

        serviceScope.launch {
            delay(6000)
            withContext(Dispatchers.Main) {
                bubble.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction { bubble.visibility = View.GONE }
                    .start()
            }
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        choreographer.removeFrameCallback(moveCallback)
        try {
            glSurfaceView?.onPause()
            windowManager?.removeView(overlayView)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay", e)
        }
        try {
            chatWebSocket.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting WebSocket", e)
        }
        serviceScope.cancel()
    }
}
