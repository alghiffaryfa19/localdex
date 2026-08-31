package com.localdex

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.localdex.scrcpy.ScrcpySession
import com.localdex.shizuku.ShizukuSessionManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fullscreen interactive view of the DeX display.
 * Supports Direct Hardware Surface (via Shizuku) or Legacy scrcpy decoder.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var closeButton: ImageButton
    private lateinit var cursorView: ImageView

    private var surfaceReady = false
    private var surfaceGivenToDecoder = false

    private val isShizukuMode: Boolean
        get() = ShizukuSessionManager.hasShizukuPermission()

    private val session: ScrcpySession?
        get() = ScrcpySession.current

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isShizukuMode && session == null) {
            finish()
            return
        }

        setContentView(R.layout.activity_viewer)
        root = findViewById(R.id.viewerRoot)
        surfaceView = findViewById(R.id.surfaceView)
        statusText = findViewById(R.id.viewerStatus)
        closeButton = findViewById(R.id.closeButton)
        cursorView = findViewById(R.id.cursorView)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                surfaceGivenToDecoder = false
                if (isShizukuMode) {
                    startShizukuDisplay(holder.surface)
                } else {
                    offerSurface()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                surfaceGivenToDecoder = false
                if (isShizukuMode) {
                    ShizukuSessionManager.stopSession()
                } else {
                    session?.videoDecoder?.clearSurface()
                }
            }
        })

        surfaceView.setOnTouchListener { view, event ->
            if (isShizukuMode) {
                forwardShizukuMotionEvent(event, view.width, view.height)
            } else {
                val s = session ?: return@setOnTouchListener false
                s.controller?.forwardMotionEvent(
                    event, view.width, view.height, s.videoWidth, s.videoHeight
                )
            }
            updateCursor(event)
            true
        }

        surfaceView.setOnGenericMotionListener { view, event ->
            if (isShizukuMode) {
                forwardShizukuMotionEvent(event, view.width, view.height)
            } else {
                val s = session ?: return@setOnGenericMotionListener false
                s.controller?.forwardGenericMotionEvent(
                    event, view.width, view.height, s.videoWidth, s.videoHeight
                )
            }
            updateCursor(event)
            true
        }

        makeCloseButtonDraggable()

        if (isShizukuMode) {
            observeShizukuState()
        } else {
            observeScrcpyState()
        }
    }

    private fun startShizukuDisplay(surface: Surface) {
        val spec = Prefs.getDisplaySpec(this)
        val match = Regex("(\\d{3,4})x(\\d{3,4})/(\\d{2,3})").find(spec)
        val width = match?.groupValues?.get(1)?.toIntOrNull() ?: 1920
        val height = match?.groupValues?.get(2)?.toIntOrNull() ?: 1440
        val dpi = match?.groupValues?.get(3)?.toIntOrNull() ?: 240

        applyAspectRatio(width, height)

        lifecycleScope.launch {
            statusText.text = "Starting Direct Surface (Shizuku)…"
            ShizukuSessionManager.startSession(this@ViewerActivity, surface, width, height, dpi)
        }
    }

    private fun observeShizukuState() {
        lifecycleScope.launch {
            ShizukuSessionManager.state.collectLatest { state ->
                when (state) {
                    is ShizukuSessionManager.State.Idle -> {}
                    is ShizukuSessionManager.State.Connecting -> {
                        statusText.visibility = View.VISIBLE
                        statusText.text = "Initializing Hardware Surface…"
                    }
                    is ShizukuSessionManager.State.Running -> {
                        statusText.visibility = View.GONE
                        applyAspectRatio(state.width, state.height)
                        launchDeXOnDisplay(state.displayId)
                    }
                    is ShizukuSessionManager.State.Error -> {
                        statusText.visibility = View.VISIBLE
                        statusText.text = "Error: ${state.message}"
                        AlertDialog.Builder(this@ViewerActivity)
                            .setTitle("Shizuku DeX Error")
                            .setMessage(state.message)
                            .setPositiveButton("OK") { _, _ -> finish() }
                            .show()
                    }
                }
            }
        }
    }

    private fun launchDeXOnDisplay(displayId: Int) {
        try {
            android.widget.Toast.makeText(this, "Starting DeX on Virtual Display $displayId...", android.widget.Toast.LENGTH_SHORT).show()
            val primaryIntent = android.content.Intent().apply {
                setClassName("com.sec.android.app.launcher", "com.honeyspace.dexservice.SecondaryLauncher")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            
            val options = android.app.ActivityOptions.makeBasic()
            options.launchDisplayId = displayId
            
            if (primaryIntent.resolveActivity(packageManager) != null) {
                startActivity(primaryIntent, options.toBundle())
            } else {
                val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_SECONDARY_HOME)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
                startActivity(fallbackIntent, options.toBundle())
            }
        } catch (e: Exception) {
            android.util.Log.e("ViewerActivity", "Failed to launch DeX", e)
            android.widget.Toast.makeText(this, "Failed to launch DeX: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun observeScrcpyState() {
        val activeSession = session ?: return
        lifecycleScope.launch {
            activeSession.state.collectLatest { state ->
                when (state) {
                    is ScrcpySession.State.Starting -> statusText.text = state.message
                    is ScrcpySession.State.Running -> {
                        statusText.visibility = View.GONE
                        applyAspectRatio(state.videoWidth, state.videoHeight)
                        offerSurface()
                    }
                    is ScrcpySession.State.Stopped -> {
                        if (state.error != null) {
                            AlertDialog.Builder(this@ViewerActivity)
                                .setTitle("Session ended")
                                .setMessage(state.error)
                                .setPositiveButton("OK") { _, _ -> finish() }
                                .setOnDismissListener { finish() }
                                .show()
                        } else {
                            finish()
                        }
                    }
                }
            }
        }
    }

    private fun forwardShizukuMotionEvent(event: MotionEvent, viewWidth: Int, viewHeight: Int) {
        val spec = Prefs.getDisplaySpec(this)
        val match = Regex("(\\d{3,4})x(\\d{3,4})/(\\d{2,3})").find(spec)
        val targetWidth = match?.groupValues?.get(1)?.toIntOrNull() ?: 1920
        val targetHeight = match?.groupValues?.get(2)?.toIntOrNull() ?: 1440

        if (viewWidth == 0 || viewHeight == 0) return

        val scaleX = targetWidth.toFloat() / viewWidth
        val scaleY = targetHeight.toFloat() / viewHeight

        val transformedEvent = MotionEvent.obtain(event)
        transformedEvent.setLocation(event.x * scaleX, event.y * scaleY)
        ShizukuSessionManager.injectMotionEvent(transformedEvent)
        transformedEvent.recycle()
    }

    /** Hands the surface to the decoder once both exist. Idempotent. */
    private fun offerSurface() {
        if (!surfaceReady || surfaceGivenToDecoder) return
        val decoder = session?.videoDecoder ?: return
        decoder.setSurface(surfaceView.holder.surface)
        surfaceGivenToDecoder = true
    }

    /** Sizes the SurfaceView to exactly the video aspect ratio, centered. */
    private fun applyAspectRatio(videoWidth: Int, videoHeight: Int) {
        if (videoWidth == 0 || videoHeight == 0) return
        root.post {
            val containerWidth = root.width
            val containerHeight = root.height
            if (containerWidth == 0 || containerHeight == 0) return@post

            val scale = minOf(
                containerWidth.toFloat() / videoWidth,
                containerHeight.toFloat() / videoHeight
            )
            val params = surfaceView.layoutParams as FrameLayout.LayoutParams
            params.width = (videoWidth * scale).toInt()
            params.height = (videoHeight * scale).toInt()
            params.gravity = Gravity.CENTER
            surfaceView.layoutParams = params
        }
    }

    private fun updateCursor(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                return
            }
        }

        val loc = IntArray(2)
        surfaceView.getLocationInWindow(loc)
        cursorView.translationX = loc[0] + event.x
        cursorView.translationY = loc[1] + event.y

        if (cursorView.visibility != View.VISIBLE) {
            cursorView.visibility = View.VISIBLE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeCloseButtonDraggable() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startTx = 0f
        var startTy = 0f
        var dragging = false
        val startDrag = Runnable {
            dragging = true
            closeButton.alpha = 0.9f
            closeButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        closeButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startTx = view.translationX
                    startTy = view.translationY
                    dragging = false
                    view.postDelayed(startDrag, 350)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (dragging) {
                        view.translationX = startTx + dx
                        view.translationY = startTy + dy
                    } else if (dx * dx + dy * dy > slop * slop) {
                        view.removeCallbacks(startDrag)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(startDrag)
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (dragging) {
                        dragging = false
                        view.alpha = 0.5f
                    } else if (dx * dx + dy * dy <= slop * slop) {
                        confirmStop()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(startDrag)
                    if (dragging) {
                        dragging = false
                        view.alpha = 0.5f
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun confirmStop() {
        val displayId = if (isShizukuMode) ShizukuSessionManager.currentDisplayId else (session?.displayId ?: -1)
        val displayNote = if (displayId >= 0) "\n\nVirtual display id: $displayId" else ""
        AlertDialog.Builder(this)
            .setTitle("Stop DeX?")
            .setMessage(
                "This ends the session and removes the virtual display.$displayNote"
            )
            .setPositiveButton("Stop") { _, _ ->
                DexService.stop(this)
                if (isShizukuMode) {
                    ShizukuSessionManager.stopSession()
                }
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isShizukuMode) {
                ShizukuSessionManager.injectKeyEvent(event)
            } else {
                session?.controller?.sendKeyPress(KeyEvent.KEYCODE_BACK)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        if (isShizukuMode) {
            ShizukuSessionManager.injectKeyEvent(down)
            ShizukuSessionManager.injectKeyEvent(up)
        } else {
            session?.controller?.sendKeyPress(KeyEvent.KEYCODE_BACK)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (!isShizukuMode && session == null) finish()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
