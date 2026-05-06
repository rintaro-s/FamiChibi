package com.nbks.famichibi.overlay

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.activity.ComponentActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nbks.famichibi.R
import com.nbks.famichibi.data.PreferencesRepository
import com.nbks.famichibi.vrm.GltfParser
import com.nbks.famichibi.vrm.VrmGlRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 常時最前面に表示される透明 Activity。
 * WindowManager オーバーレイでは Filament/TextureView が破綻するため、
 * Activity ベースでフローティング表示を実現する。
 */
class VrmOverlayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VrmOverlayActivity"
        const val ACTION_SHOW_MESSAGE = "com.nbks.famichibi.SHOW_MESSAGE"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_CONTENT = "content"
        const val ACTION_FINISH = "com.nbks.famichibi.FINISH_OVERLAY"

        fun start(context: Context) {
            val intent = Intent(context, VrmOverlayActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }

        fun stop(context: Context) {
            context.sendBroadcast(
                Intent(ACTION_FINISH).apply { setPackage(context.packageName) }
            )
        }
    }

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var speechBubble: TextView
    private lateinit var decorationContainer: FrameLayout
    private lateinit var renderer: VrmGlRenderer
    private val handler = Handler(Looper.getMainLooper())
    private var bubbleHideRunnable: Runnable? = null

    private var startRawX = 0f
    private var startRawY = 0f
    private var initialX = 0
    private var initialY = 0

    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SHOW_MESSAGE -> {
                    val sender = intent.getStringExtra(EXTRA_SENDER) ?: ""
                    val content = intent.getStringExtra(EXTRA_CONTENT) ?: ""
                    showMessage(sender, content)
                }
                ACTION_FINISH -> {
                    finishAndRemoveTask()
                }
                "com.nbks.famichibi.SET_EXPRESSION" -> {
                    // OpenGL ES renderer: expressions not yet implemented
                }
                "com.nbks.famichibi.RESET_EXPRESSION" -> {
                    // OpenGL ES renderer: expressions not yet implemented
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 透明・フローティング設定
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        window.setLayout(320, 500)
        val params = window.attributes
        params.x = 100
        params.y = 300
        window.attributes = params

        setContentView(R.layout.overlay_view)

        glSurfaceView = findViewById(R.id.surfaceView)
        speechBubble = findViewById(R.id.speechBubble)
        decorationContainer = findViewById(R.id.decorationContainer)

        glSurfaceView.setEGLContextClientVersion(3)
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glSurfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        // Keep Z-order default so other views (speechBubble) can draw on top

        renderer = VrmGlRenderer()
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        setupDrag()
        speechBubble.visibility = View.GONE

        loadVrm()

        ContextCompat.registerReceiver(
            this, messageReceiver,
            IntentFilter().apply {
                addAction(ACTION_SHOW_MESSAGE)
                addAction("com.nbks.famichibi.SET_EXPRESSION")
                addAction("com.nbks.famichibi.RESET_EXPRESSION")
                addAction(ACTION_FINISH)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun loadVrm() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefs = PreferencesRepository(applicationContext)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        val touchArea = findViewById<View>(android.R.id.content)
        touchArea.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    initialX = window.attributes.x
                    initialY = window.attributes.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    val params = window.attributes
                    params.x = initialX + dx
                    params.y = initialY + dy
                    window.attributes = params
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.hypot(event.rawX - startRawX, event.rawY - startRawY) < 15f) {
                        triggerJumpReaction()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun triggerJumpReaction() {
        // TODO: expression support in OpenGL ES renderer
    }

    fun showMessage(sender: String, message: String) {
        handler.post {
            bubbleHideRunnable?.let { handler.removeCallbacks(it) }
            speechBubble.text = "$sender\n$message"
            speechBubble.visibility = View.VISIBLE
            speechBubble.alpha = 0f
            speechBubble.translationY = 20f
            speechBubble.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .start()

            bubbleHideRunnable = Runnable { hideBubble() }
            handler.postDelayed(bubbleHideRunnable!!, 6000)
        }
    }

    private fun hideBubble() {
        speechBubble.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { speechBubble.visibility = View.GONE }
            .start()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(messageReceiver)
        renderer.destroy()
    }

    override fun onBackPressed() {
        moveTaskToBack(true)
    }
}
