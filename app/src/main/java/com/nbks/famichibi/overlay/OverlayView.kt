package com.nbks.famichibi.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.animation.doOnEnd
import com.nbks.famichibi.R
import com.nbks.famichibi.data.DecorationItem
import kotlin.math.abs

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class OverlayView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams
) : FrameLayout(context) {

    private val webView: DraggableWebView
    private val speechBubble: TextView
    private val decorationContainer: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private var bubbleHideRunnable: Runnable? = null
    private var startWindowX = 0
    private var startWindowY = 0
    private var startRawX = 0f
    private var startRawY = 0f
    private var startTime = 0L
    private var isDragging = false
    private val decorationViews = mutableMapOf<String, View>()

    var onTapListener: (() -> Unit)? = null
    var onModelLoaded: (() -> Unit)? = null

    init {
        // 親View（FrameLayout）自体も透明に
        setBackgroundColor(Color.TRANSPARENT)

        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.overlay_view, this, true)

        webView = findViewById(R.id.webView)
        speechBubble = findViewById(R.id.speechBubble)
        decorationContainer = findViewById(R.id.decorationContainer)

        setupWebView()
        setupDrag()
        speechBubble.visibility = View.GONE
    }

    private fun setupWebView() {
        webView.apply {
            // 徹底的な透明設定
            setBackgroundColor(Color.TRANSPARENT)
            setBackgroundResource(0)
            background = null
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "WebView page finished: $url")
                    // ページ読み込み完了後、少し遅延してVRM読み込みを開始
                    handler.postDelayed({
                        onModelLoaded?.invoke()
                    }, 500)
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    Log.e(TAG, "WebView error $errorCode: $description at $failingUrl")
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        val msg = "[WebView ${it.sourceId()}:${it.lineNumber()}] ${it.message()}"
                        when (it.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, msg)
                            ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, msg)
                            else -> Log.d(TAG, msg)
                        }
                    }
                    return true
                }
            }

            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        }
    }

    fun loadViewerUrl(url: String) {
        Log.d(TAG, "Loading viewer URL: $url")
        webView.loadUrl(url)
    }

    private fun setupDrag() {
        webView.dragListener = { dx, dy ->
            layoutParams.x = startWindowX + dx.toInt()
            layoutParams.y = startWindowY + dy.toInt()
            windowManager.updateViewLayout(this, layoutParams)
        }

        webView.touchStartListener = { rawX, rawY ->
            startRawX = rawX
            startRawY = rawY
            startWindowX = layoutParams.x
            startWindowY = layoutParams.y
            startTime = System.currentTimeMillis()
            isDragging = false
        }

        webView.touchMoveListener = { dx, dy ->
            if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) {
                isDragging = true
            }
        }

        webView.tapListener = {
            onTapListener?.invoke()
        }
    }

    fun loadVrm(path: String, retryCount: Int = 20) {
        Log.d(TAG, "Loading VRM: $path (retry left: $retryCount)")
        val escaped = path.replace("\\", "\\\\").replace("'", "\\'")
        val js = "typeof window.loadVrm !== 'undefined' ? (window.loadVrm('$escaped'), 'ok') : 'not_ready'"
        webView.evaluateJavascript(js) { result ->
            Log.d(TAG, "loadVrm check result: $result")
            if (result == "\"not_ready\"" && retryCount > 0) {
                handler.postDelayed({ loadVrm(path, retryCount - 1) }, 500)
            } else if (result == "\"ok\"") {
                Log.d(TAG, "VRM load initiated successfully")
            } else {
                Log.e(TAG, "VRM load failed or not ready: $result")
            }
        }
    }

    fun showMessage(sender: String, message: String) {
        handler.post {
            bubbleHideRunnable?.let { handler.removeCallbacks(it) }

            speechBubble.text = "$sender\n$message"
            speechBubble.visibility = View.VISIBLE
            speechBubble.alpha = 0f
            speechBubble.translationY = 20f

            val fadeIn = ObjectAnimator.ofFloat(speechBubble, "alpha", 0f, 1f)
            val slideUp = ObjectAnimator.ofFloat(speechBubble, "translationY", 20f, 0f)

            AnimatorSet().apply {
                playTogether(fadeIn, slideUp)
                duration = 300
                start()
            }

            val expression = when {
                "がんば" in message || "応援" in message || "頑張" in message -> "happy"
                "大丈夫" in message || "心配" in message -> "relaxed"
                "すごい" in message || "えらい" in message -> "surprised"
                "バカ" in message || "あほ" in message || "怒" in message -> "angry"
                else -> "happy"
            }
            setExpression(expression)

            bubbleHideRunnable = Runnable {
                hideBubble()
                resetExpression()
            }
            handler.postDelayed(bubbleHideRunnable!!, 6000)
        }
    }

    private fun hideBubble() {
        val fadeOut = ObjectAnimator.ofFloat(speechBubble, "alpha", 1f, 0f)
        fadeOut.duration = 300
        fadeOut.doOnEnd { speechBubble.visibility = View.GONE }
        fadeOut.start()
    }

    private fun setExpression(name: String) {
        val js = "if(window.setExpression) window.setExpression('$name', 1.0);"
        webView.evaluateJavascript(js, null)
    }

    private fun resetExpression() {
        val js = "if(window.resetExpression) window.resetExpression();"
        webView.evaluateJavascript(js, null)
    }

    fun updateDecorations(decorations: List<DecorationItem>) {
        handler.post {
            decorationContainer.removeAllViews()
            decorationViews.clear()
            decorations.forEach { item ->
                val view = createDecorationView(item)
                decorationContainer.addView(view)
                decorationViews[item.id] = view
            }
        }
    }

    private fun createDecorationView(item: DecorationItem): View {
        val view = View(context).apply {
            setBackgroundResource(getDecorationDrawable(item.type))
            layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                leftMargin = item.x.toInt()
                topMargin = item.y.toInt()
            }
            pivotX = 60f
            pivotY = 60f
            scaleX = item.scale
            scaleY = item.scale
            rotation = item.rotation
        }
        return view
    }

    private fun getDecorationDrawable(type: String): Int {
        return when (type) {
            "ribbon" -> android.R.drawable.ic_menu_add
            "heart" -> android.R.drawable.btn_star_big_on
            "crown" -> android.R.drawable.ic_menu_myplaces
            "flower" -> android.R.drawable.ic_menu_gallery
            else -> android.R.drawable.ic_menu_help
        }
    }

    fun updatePosition(x: Int, y: Int) {
        layoutParams.x = x
        layoutParams.y = y
        windowManager.updateViewLayout(this, layoutParams)
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun onModelLoaded() {
            handler.post {
                onModelLoaded?.invoke()
            }
        }

        @JavascriptInterface
        fun onTap(x: Float, y: Float) {
            handler.post {
                onTapListener?.invoke()
            }
        }
    }

    companion object {
        private const val TAG = "OverlayView"
    }
}

class DraggableWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    var dragListener: ((Float, Float) -> Unit)? = null
    var tapListener: (() -> Unit)? = null
    var touchStartListener: ((Float, Float) -> Unit)? = null
    var touchMoveListener: ((Float, Float) -> Unit)? = null

    private var startRawX = 0f
    private var startRawY = 0f
    private var startTime = 0L
    private var isDragging = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        background = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startRawX = event.rawX
                startRawY = event.rawY
                startTime = System.currentTimeMillis()
                isDragging = false
                touchStartListener?.invoke(startRawX, startRawY)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - startRawX
                val dy = event.rawY - startRawY
                if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) {
                    isDragging = true
                }
                touchMoveListener?.invoke(dx, dy)
                if (isDragging) {
                    dragListener?.invoke(dx, dy)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!isDragging && System.currentTimeMillis() - startTime < 300) {
                    tapListener?.invoke()
                }
                if (isDragging) {
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }
}
