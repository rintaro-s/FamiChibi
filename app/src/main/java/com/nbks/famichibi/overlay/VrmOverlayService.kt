package com.nbks.famichibi.overlay

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.nbks.famichibi.MainActivity
import com.nbks.famichibi.R
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.network.ChatEvent
import com.nbks.famichibi.network.ChatWebSocket
import com.nbks.famichibi.util.formatName
import com.nbks.famichibi.vrm.AssetVrmScanner
import com.nbks.famichibi.vrm.GltfParser
import com.nbks.famichibi.vrm.VrmGlRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Locale
import android.speech.tts.TextToSpeech

/**
 * フォアグラウンドサービス。
 * WindowManager + GLSurfaceView でオーバーレイを表示する。
 */
class VrmOverlayService : Service() {

    companion object {
        private const val TAG = "VrmOverlayService"
        private const val CHANNEL_ID = "famichibi_overlay"
        private const val NOTIFICATION_ID = 1
        const val ACTION_SEND_CHAT = "com.nbks.famichibi.SEND_CHAT"
        const val EXTRA_CHAT_REPLY = "chat_reply"

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
    private lateinit var prefs: PreferencesRepository
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var glSurfaceView: GLSurfaceView? = null
    private var renderer: VrmGlRenderer? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var startRawX = 0f
    private var startRawY = 0f
    private var initialX = 0
    private var initialY = 0
    private var lastDragTime = 0L
    private var lastDragX = 0f
    private var lastDragY = 0f
    private val DIZZY_VELOCITY_THRESHOLD = 2400f // px/s

    // Multi-participant support
    private data class ParticipantView(
        val userId: String,
        val userName: String,
        val view: FrameLayout,
        val glSurfaceView: GLSurfaceView,
        val renderer: VrmGlRenderer,
        val speechBubble: TextView,
        val params: WindowManager.LayoutParams,
        var currentX: Float = 0f,
        var currentY: Float = 0f,
        var targetX: Float = 0f,
        var targetY: Float = 0f,
        var isMoving: Boolean = false,
        var isDragging: Boolean = false,
    )
    private val participants = mutableMapOf<String, ParticipantView>()
    private var myUserId: String = ""
    private var eventCollectionJob: kotlinx.coroutines.Job? = null

    private val chatReplyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SEND_CHAT) {
                val results = RemoteInput.getResultsFromIntent(intent)
                val message = results?.getCharSequence(EXTRA_CHAT_REPLY)?.toString()
                if (!message.isNullOrBlank()) {
                    val memberships = runBlocking { prefs.serverMemberships.first() }
                    val membership = memberships.lastOrNull()
                    val uid = runBlocking { prefs.userId.first().ifEmpty { java.util.UUID.randomUUID().toString().also { prefs.setUserId(it) } } }
                    ChatWebSocket.sendMessage(message)
                }
            }
        }
    }

    // Demo mode
    private var demoModeEnabled = false
    private var demoJob: kotlinx.coroutines.Job? = null
    private val demoMessages = listOf(
        "こんにちは！", "今日はいい天気だね", "何してるの？", "一緒に遊ぼう！",
        "お腹すいたな〜", "眠くなってきた…", "がんばって！", "応援してるよ！",
        "いいね！", "すごいね！", "楽しいね", "また明日ね！",
        "おはよう！", "おやすみ〜", "ただいま！", "おかえり！",
    )
    private val demoPoses = listOf(
        VrmGlRenderer.AnimationState.IDLE,
        VrmGlRenderer.AnimationState.WALK,
        VrmGlRenderer.AnimationState.SKIP,
        VrmGlRenderer.AnimationState.FLAIL,
        VrmGlRenderer.AnimationState.DIZZY,
    )

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
            val deltaSec = (frameTimeNanos - lastFrameTime) / 1_000_000_000f
            lastFrameTime = frameTimeNanos

            for (pv in participants.values) {
                if (pv.isDragging) continue
                val dx = pv.targetX - pv.currentX
                val dy = pv.targetY - pv.currentY
                val dist = kotlin.math.hypot(dx, dy)

                if (dist < 5f) {
                    if (pv.isMoving) {
                        pv.isMoving = false
                        pv.renderer.animationState = VrmGlRenderer.AnimationState.IDLE
                        serviceScope.launch {
                            delay(500L + kotlin.random.Random.nextLong(2000))
                            pickNewTargetForParticipant(pv)
                        }
                    }
                } else {
                    pv.isMoving = true
                    pv.renderer.animationState = VrmGlRenderer.AnimationState.WALK
                    val moveDist = moveSpeed * deltaSec
                    val ratio = if (dist > 0f) moveDist / dist else 0f
                    pv.currentX += dx * kotlin.math.min(ratio, 1f)
                    pv.currentY += dy * kotlin.math.min(ratio, 1f)

                    pv.params.x = pv.currentX.toInt()
                    pv.params.y = pv.currentY.toInt()
                    windowManager?.updateViewLayout(pv.view, pv.params)

                    val angle = (kotlin.math.atan2(dx.toDouble(), dy.toDouble()) * 180.0 / kotlin.math.PI).toFloat()
                    pv.renderer.rotationY = angle
                }
            }
        }
    }

    private fun pickNewTarget() {
        // 自分自身のアバターは中央付近に留める
        val params = layoutParams ?: return
        params.x = (screenWidth / 2f - params.width / 2f).toInt()
        params.y = (screenHeight / 2f - params.height / 2f).toInt()
        currentX = params.x.toFloat()
        currentY = params.y.toFloat()
        try { windowManager?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
    }

    private fun pickNewTargetForParticipant(pv: ParticipantView) {
        val viewW = pv.params.width
        val viewH = pv.params.height
        val maxX = (screenWidth - viewW).coerceAtLeast(1)
        val maxY = (screenHeight - viewH).coerceAtLeast(1)
        pv.targetX = kotlin.random.Random.nextInt(0, maxX).toFloat()
        pv.targetY = kotlin.random.Random.nextInt(0, maxY).toFloat()
        pv.isMoving = true
        pv.renderer.animationState = VrmGlRenderer.AnimationState.WALK
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesRepository(applicationContext)
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.JAPAN
            }
        }
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(chatReplyReceiver, IntentFilter(ACTION_SEND_CHAT), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(chatReplyReceiver, IntentFilter(ACTION_SEND_CHAT))
        }
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

        serviceScope.launch {
            demoModeEnabled = prefs.demoMode.first()
            if (demoModeEnabled) {
                startDemoMode()
            } else {
                connectWebSocket()
            }
        }

        return START_STICKY
    }

    private fun setupOverlay() {
        if (overlayView != null) {
            Log.d(TAG, "Overlay already set up, skipping")
            return
        }
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

        // 自分のアバターも表示する（誰もいないときのデフォルト表示として）
        glView.visibility = View.VISIBLE
        view.findViewById<View>(R.id.decorationContainer).visibility = View.VISIBLE

        val params = WindowManager.LayoutParams(
            dpToPx(90),
            dpToPx(120),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.START or Gravity.TOP
        view.visibility = View.VISIBLE

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

    private fun isTouchOnAvatar(event: MotionEvent, glView: GLSurfaceView?): Boolean {
        if (glView == null || glView.visibility == View.GONE) return false
        val location = IntArray(2)
        glView.getLocationOnScreen(location)
        val x = event.rawX - location[0]
        val y = event.rawY - location[1]
        val centerX = glView.width / 2f
        val centerY = glView.height * 0.55f
        val radiusX = glView.width * 0.35f
        val radiusY = glView.height * 0.45f
        val dx = x - centerX
        val dy = y - centerY
        return (dx * dx) / (radiusX * radiusX) + (dy * dy) / (radiusY * radiusY) <= 1f
    }

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        val glView = view.findViewById<GLSurfaceView>(R.id.surfaceView)
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isTouchOnAvatar(event, glView)) return@setOnTouchListener false
                    isDragging = true
                    renderer?.animationState = VrmGlRenderer.AnimationState.IDLE
                    startRawX = event.rawX
                    startRawY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    lastDragX = event.rawX
                    lastDragY = event.rawY
                    lastDragTime = System.currentTimeMillis()
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

                    val now = System.currentTimeMillis()
                    val dt = (now - lastDragTime).coerceAtLeast(1L)
                    val moveDist = kotlin.math.hypot(event.rawX - lastDragX, event.rawY - lastDragY)
                    val velocity = (moveDist / dt) * 1000f
                    if (velocity > DIZZY_VELOCITY_THRESHOLD) {
                        renderer?.let { r ->
                            r.animationState = VrmGlRenderer.AnimationState.DIZZY
                            r.dizzyRemaining = 2.5f
                        }
                    }
                    lastDragX = event.rawX
                    lastDragY = event.rawY
                    lastDragTime = now
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
                // Prefer myVrmPath (user-imported or selected asset), fall back to legacy vrmPath, then any asset, then default
                val myVrm = prefs.myVrmPath.first()
                val legacyVrm = prefs.vrmPath.first()
                val selectedAsset = prefs.selectedAssetVrm.first()
                val vrmFile = when {
                    myVrm.isNotEmpty() -> File(myVrm)
                    legacyVrm.isNotEmpty() -> File(legacyVrm)
                    selectedAsset.isNotEmpty() -> AssetVrmScanner.copyAssetVrmToInternal(applicationContext, selectedAsset) ?: copyAssetVrm()
                    else -> copyAssetVrm()
                }
                if (!vrmFile.exists()) {
                    Log.e(TAG, "VRM file not found: ${vrmFile.absolutePath}")
                    return@launch
                }
                Log.d(TAG, "Loading VRM: ${vrmFile.absolutePath}, size=${vrmFile.length()}")
                val bytes = vrmFile.readBytes()
                val result = GltfParser.parse(bytes)
                withContext(Dispatchers.Main) {
                    if (result != null) {
                        renderer.loadModel(result.root, result.meshes, result.vrmExtension)
                        Log.d(TAG, "VRM loaded: ${result.meshes.size} meshes")
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

    private fun startDemoMode() {
        demoJob?.cancel()
        demoJob = serviceScope.launch {
            // Add 2 demo participants with different VRM models
            val assetVrms = AssetVrmScanner.listAssetVrms(applicationContext)
            val demoVrms = assetVrms.filter { it != "AvatarSample_M.vrm" }.take(2)
            if (demoVrms.isEmpty()) {
                // Fall back to default
                addDemoParticipant("demo_1", "妹", null)
                addDemoParticipant("demo_2", "兄", null)
            } else {
                addDemoParticipant("demo_1", "妹", demoVrms.getOrNull(0))
                addDemoParticipant("demo_2", "兄", demoVrms.getOrNull(1))
            }

            // Random pose and chat loop
            while (isActive) {
                delay(3000 + kotlin.random.Random.nextLong(4000))
                for ((id, pv) in participants) {
                    val pose = demoPoses.random()
                    pv.renderer.animationState = pose
                    if (pose == VrmGlRenderer.AnimationState.DIZZY) {
                        pv.renderer.dizzyRemaining = 2.0f
                    }
                    val msg = demoMessages.random()
                    showBubbleOn(pv.speechBubble, pv.userName, msg)
                }
            }
        }
    }

    private suspend fun addDemoParticipant(id: String, name: String, vrmAsset: String?) {
        if (participants.containsKey(id)) return
        if (participants.size >= 3) return
        val wm = windowManager ?: return

        val frame = FrameLayout(this)
        val glView = GLSurfaceView(this)
        glView.setEGLContextClientVersion(3)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glView.holder.setFormat(PixelFormat.TRANSLUCENT)
        val r = VrmGlRenderer()
        r.animationState = VrmGlRenderer.AnimationState.IDLE
        glView.setRenderer(r)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        val glViewParams = FrameLayout.LayoutParams(dpToPx(90), dpToPx(120)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        frame.addView(glView, glViewParams)

        val bubble = TextView(this).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.BLACK)
            background = resources.getDrawable(R.drawable.speech_bubble_bg, null)
            gravity = android.view.Gravity.CENTER
            maxWidth = dpToPx(180)
            minWidth = dpToPx(80)
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            visibility = View.GONE
        }
        val bubbleParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dpToPx(4)
        }
        frame.addView(bubble, bubbleParams)

        val params = WindowManager.LayoutParams(
            dpToPx(90),
            dpToPx(140),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.START or Gravity.TOP

        val maxX = (screenWidth - params.width).coerceAtLeast(1)
        val maxY = (screenHeight - params.height).coerceAtLeast(1)
        params.x = kotlin.random.Random.nextInt(0, maxX)
        params.y = kotlin.random.Random.nextInt(0, maxY)

        val pv = ParticipantView(
            userId = id,
            userName = name,
            view = frame,
            glSurfaceView = glView,
            renderer = r,
            speechBubble = bubble,
            params = params,
            currentX = params.x.toFloat(),
            currentY = params.y.toFloat()
        )
        participants[id] = pv
        wm.addView(frame, params)
        pickNewTargetForParticipant(pv)

        // Load VRM for this demo participant
        serviceScope.launch(Dispatchers.IO) {
            try {
                val vrmFile = if (vrmAsset != null) {
                    AssetVrmScanner.copyAssetVrmToInternal(applicationContext, vrmAsset)
                        ?: copyAssetVrm()
                } else {
                    copyAssetVrm()
                }
                if (vrmFile.exists()) {
                    val bytes = vrmFile.readBytes()
                    val result = GltfParser.parse(bytes)
                    withContext(Dispatchers.Main) {
                        if (result != null) {
                            r.loadModel(result.root, result.meshes, result.vrmExtension)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load demo VRM", e)
            }
        }
    }

    private fun stopDemoMode() {
        demoJob?.cancel()
        demoJob = null
        listOf("demo_1", "demo_2").forEach { removeParticipant(it) }
    }

    private fun connectWebSocket() {
        // Clear existing participants before reconnecting
        participants.keys.toList().forEach { removeParticipant(it) }
        ChatWebSocket.disconnect()
        eventCollectionJob?.cancel()

        serviceScope.launch {
            try {
                val memberships = prefs.serverMemberships.first()
                val membership = memberships.lastOrNull()
                val hosts = prefs.hosts.first()
                val host = hosts.find { it.id == membership?.hostId }
                val serverUrl = host?.url ?: ""
                val serverId = membership?.serverId ?: ""
                val roomId = prefs.activeChannelId.first()
                val uid = prefs.userId.first().ifEmpty { java.util.UUID.randomUUID().toString().also { prefs.setUserId(it) } }
                myUserId = uid
                val userName = membership?.nickname ?: ""
                Log.d(TAG, "Connecting WebSocket: userId=$uid, serverId=$serverId, roomId=$roomId")
                if (serverUrl.isNotEmpty() && serverId.isNotEmpty() && roomId.isNotEmpty()) {
                    ChatWebSocket.connect(serverUrl, serverId, roomId, uid, userName, "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket connect failed", e)
            }
        }

        eventCollectionJob = serviceScope.launch {
            ChatWebSocket.events.collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: ChatEvent) {
        if (myUserId.isEmpty()) {
            Log.w(TAG, "Ignoring event because myUserId is not set yet: $event")
            return
        }
        Log.d(TAG, "handleEvent: type=${event.javaClass.simpleName}, myUserId=$myUserId, participants=${participants.keys}")
        when (event) {
            is ChatEvent.Message -> {
                serviceScope.launch(Dispatchers.Main) {
                    val senderId = event.msg.sender_id
                    val senderName = event.msg.sender
                    val content = event.msg.content
                    val targetBubble = when {
                        senderId == myUserId -> {
                            participants.values.firstOrNull()?.speechBubble
                                ?: overlayView?.findViewById<TextView>(R.id.speechBubble)
                        }
                        participants.containsKey(senderId) -> {
                            participants[senderId]?.speechBubble
                        }
                        else -> {
                            participants.values.firstOrNull()?.speechBubble
                                ?: overlayView?.findViewById<TextView>(R.id.speechBubble)
                        }
                    }
                    targetBubble?.let { showBubbleOn(it, senderName, content) }
                    if (event.msg.type == "agent" || event.msg.type == "proactive") {
                        speakIfAllowed(content)
                    }
                }
            }
            is ChatEvent.Whisper -> {
                serviceScope.launch(Dispatchers.Main) {
                    val bubble = overlayView?.findViewById<TextView>(R.id.speechBubble)
                    bubble?.let { showBubbleOn(it, "${event.fromUserName}（ささやき）", event.content) }
                }
            }
            is ChatEvent.Nudge -> {
                serviceScope.launch(Dispatchers.Main) {
                    val bubble = overlayView?.findViewById<TextView>(R.id.speechBubble)
                    bubble?.let { showBubbleOn(it, "システム", "${formatName(event.fromUserName)}があなたを呼んでいます") }
                    renderer?.let { r ->
                        r.animationState = VrmGlRenderer.AnimationState.DIZZY
                        r.dizzyRemaining = 1.5f
                    }
                }
            }
            is ChatEvent.UserJoined -> {
                serviceScope.launch(Dispatchers.Main) {
                    val bubble = participants.values.firstOrNull()?.speechBubble
                        ?: overlayView?.findViewById<TextView>(R.id.speechBubble)
                    bubble?.let { showBubbleOn(it, "システム", "${formatName(event.userName)}が参加しました") }
                    if (event.userId != myUserId && !participants.containsKey(event.userId)) {
                        addParticipant(event.userId, event.userName)
                    }
                }
            }
            is ChatEvent.UserLeft -> {
                serviceScope.launch(Dispatchers.Main) {
                    val bubble = participants.values.firstOrNull()?.speechBubble
                        ?: overlayView?.findViewById<TextView>(R.id.speechBubble)
                    bubble?.let { showBubbleOn(it, "システム", "${formatName(event.userName)}が退出しました") }
                    removeParticipant(event.userId)
                }
            }
            is ChatEvent.Joined -> {
                serviceScope.launch(Dispatchers.Main) {
                    event.users.filter { it.user_id != myUserId && !participants.containsKey(it.user_id) }
                        .forEach { addParticipant(it.user_id, it.user_name) }
                    val bubble = overlayView?.findViewById<TextView>(R.id.speechBubble)
                    bubble?.let { showBubbleOn(it, "システム", "${event.roomName}に参加しました") }
                }
            }
            is ChatEvent.Error -> {
                Log.e(TAG, "Chat error: ${event.message}")
            }
            is ChatEvent.NotebookUpdate -> {
                // no overlay action needed
            }
            is ChatEvent.Reaction -> {
                // no overlay action needed
            }
            else -> {
                // VoiceSignal, VoiceUserJoined, VoiceUserLeft - not handled in overlay
            }
        }
    }

    private fun showBubbleOn(bubble: TextView, sender: String, message: String) {
        bubble.text = "$sender\n$message"
        bubble.visibility = View.VISIBLE
        bubble.alpha = 0f
        bubble.translationY = 10f
        bubble.animate().cancel()
        bubble.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .start()

        bubble.removeCallbacks(null)
        bubble.postDelayed({
            if (bubble.visibility == View.VISIBLE) {
                bubble.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { bubble.visibility = View.GONE }
                    .start()
            }
        }, 4000)
    }

    private suspend fun isQuietHours(): Boolean {
        if (!prefs.quietEnabled.first()) return false
        val start = prefs.quietHoursStart.first()
        val end = prefs.quietHoursEnd.first()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (start < end) hour in start until end else hour >= start || hour < end
    }

    private fun speakIfAllowed(text: String) {
        serviceScope.launch {
            try {
                if (isQuietHours()) return@launch
                if (!prefs.ttsEnabled.first()) return@launch
                if (ttsReady) {
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "overlay_msg")
                }
            } catch (_: Exception) {}
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

        val remoteInput = RemoteInput.Builder(EXTRA_CHAT_REPLY)
            .setLabel("メッセージを入力...")
            .build()

        val replyIntent = Intent(ACTION_SEND_CHAT).apply {
            setPackage(packageName)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            this, 1, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "送信",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FamiChibi")
            .setContentText("アバターが画面で待機中...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(replyAction)
            .build()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addParticipant(userId: String, userName: String) {
        if (participants.containsKey(userId)) return
        if (participants.size >= 3) {
            Log.w(TAG, "Max participants reached, ignoring $userName")
            return
        }
        val wm = windowManager ?: return

        val frame = FrameLayout(this)
        val glView = GLSurfaceView(this)
        glView.setEGLContextClientVersion(3)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glView.holder.setFormat(PixelFormat.TRANSLUCENT)
        val r = VrmGlRenderer()
        glView.setRenderer(r)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        val glViewParams = FrameLayout.LayoutParams(dpToPx(90), dpToPx(120)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        frame.addView(glView, glViewParams)

        // Speech bubble for this participant
        val bubble = TextView(this).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.BLACK)
            background = resources.getDrawable(R.drawable.speech_bubble_bg, null)
            gravity = android.view.Gravity.CENTER
            maxWidth = dpToPx(180)
            minWidth = dpToPx(80)
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            visibility = View.GONE
        }
        val bubbleParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dpToPx(4)
        }
        frame.addView(bubble, bubbleParams)

        val params = WindowManager.LayoutParams(
            dpToPx(90),
            dpToPx(140),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.START or Gravity.TOP

        // 画面上のランダムな位置に配置
        val maxX = (screenWidth - params.width).coerceAtLeast(1)
        val maxY = (screenHeight - params.height).coerceAtLeast(1)
        params.x = kotlin.random.Random.nextInt(0, maxX)
        params.y = kotlin.random.Random.nextInt(0, maxY)

        val pv = ParticipantView(
            userId = userId,
            userName = userName,
            view = frame,
            glSurfaceView = glView,
            renderer = r,
            speechBubble = bubble,
            params = params,
            currentX = params.x.toFloat(),
            currentY = params.y.toFloat()
        )
        participants[userId] = pv

        wm.addView(frame, params)
        setupDragForParticipant(pv)
        loadVrmForRenderer(r)
        pickNewTargetForParticipant(pv)
        Log.d(TAG, "Added participant: $userName at (${params.x}, ${params.y})")
    }

    private fun removeParticipant(userId: String) {
        val pv = participants.remove(userId) ?: return
        try {
            pv.glSurfaceView.onPause()
            windowManager?.removeView(pv.view)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing participant view", e)
        }
        Log.d(TAG, "Removed participant: ${pv.userName}")
    }

    private fun setupDragForParticipant(pv: ParticipantView) {
        pv.view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isTouchOnAvatar(event, pv.glSurfaceView)) return@setOnTouchListener false
                    pv.isDragging = true
                    pv.renderer.animationState = VrmGlRenderer.AnimationState.IDLE
                    startRawX = event.rawX
                    startRawY = event.rawY
                    initialX = pv.params.x
                    initialY = pv.params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    pv.params.x = initialX + dx
                    pv.params.y = initialY + dy
                    pv.currentX = pv.params.x.toFloat()
                    pv.currentY = pv.params.y.toFloat()
                    windowManager?.updateViewLayout(pv.view, pv.params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    pv.isDragging = false
                    serviceScope.launch {
                        delay(800)
                        pickNewTargetForParticipant(pv)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun loadVrmForRenderer(renderer: VrmGlRenderer) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val vrmFile = copyAssetVrm()
                if (!vrmFile.exists()) {
                    Log.e(TAG, "Default VRM not found")
                    return@launch
                }
                val bytes = vrmFile.readBytes()
                val result = GltfParser.parse(bytes)
                withContext(Dispatchers.Main) {
                    if (result != null) {
                        renderer.loadModel(result.root, result.meshes, result.vrmExtension)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load default VRM for participant", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        choreographer.removeFrameCallback(moveCallback)
        stopDemoMode()
        try {
            unregisterReceiver(chatReplyReceiver)
        } catch (_: Exception) {}
        try {
            glSurfaceView?.onPause()
            windowManager?.removeView(overlayView)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay", e)
        }
        participants.values.forEach { pv ->
            try {
                pv.glSurfaceView.onPause()
                windowManager?.removeView(pv.view)
            } catch (_: Exception) {}
        }
        participants.clear()
        try {
            ChatWebSocket.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting WebSocket", e)
        }
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        serviceScope.cancel()
    }
}
