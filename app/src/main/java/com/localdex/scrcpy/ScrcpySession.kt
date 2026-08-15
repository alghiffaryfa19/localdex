package com.localdex.scrcpy

import android.content.Context
import android.util.Log
import com.localdex.Adb
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * One DeX session: starts scrcpy-server on the device (over the device's own wireless
 * debugging), which creates a virtual display, and owns the video/control sockets
 * until stopped.
 *
 * A virtual display shows no preview window on the main screen (unlike an overlay
 * display) and touches no global display settings. On Android 16 QPR2+ / One UI 8.5+
 * such displays default to fullscreen windowing (scrcpy issue #6143), so once the
 * display id is known the session forces it to freeform with
 * `wm set-display-windowing-mode` — giving movable, resizable windows.
 */
class ScrcpySession(
    private val context: Context,
    private val displaySpec: String,
) {
    sealed class State {
        data class Starting(val message: String) : State()
        data class Running(val videoWidth: Int, val videoHeight: Int, val displayId: Int) : State()
        data class Stopped(val error: String?) : State()
    }

    companion object {
        private const val TAG = "ScrcpySession"

        private const val SERVER_ASSET = "scrcpy-server"
        private const val SERVER_REMOTE_PATH = "/data/local/tmp/localdex-scrcpy-server.jar"
        private const val SERVER_VERSION = "4.1"

        private const val CONNECT_RETRIES = 40
        private const val CONNECT_RETRY_DELAY_MS = 250L

        /** The one live session, owned by DexService. */
        @Volatile
        var current: ScrcpySession? = null
    }

    private val _state = MutableStateFlow<State>(State.Starting("Connecting…"))
    val state: StateFlow<State> = _state

    // Survives cancellation of callers: stop() must always run to completion so
    // nothing (server process, streams) leaks past the session.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val stopped = AtomicBoolean(false)

    private var manager: AbsAdbConnectionManager? = null
    private var shellStream: AdbStream? = null
    private var videoStream: AdbStream? = null
    private var controlStream: AdbStream? = null

    var videoDecoder: VideoDecoder? = null
        private set
    var controller: Controller? = null
        private set

    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    /** The Android display id of the virtual display, for `scrcpy --display-id=N`. */
    @Volatile
    var displayId = -1
        private set

    /** Tail of the server's stdout/stderr, kept for error reporting. */
    private val serverLog = StringBuilder()

    private val displayIdPattern = Regex("New display: .*\\(id=(\\d+)\\)")

    fun start() {
        scope.launch {
            try {
                runSession()
            } catch (e: Exception) {
                Log.e(TAG, "Session failed", e)
                stop(errorForUser(e))
            }
        }
    }

    private suspend fun runSession() {
        val manager = Adb.createManager(context)
        this.manager = manager

        if (!manager.autoConnect(context, 10_000)) {
            throw IOException("Could not connect to ADB. Is wireless debugging on and paired?")
        }

        setStarting("Preparing display…")
        // Read into memory (~700 KB): AssetFileDescriptor can't report the size of a
        // compressed asset, and pushFile needs the exact byte count for `head -c`.
        val serverBytes = context.assets.open(SERVER_ASSET).use { it.readBytes() }
        Adb.pushFile(manager, serverBytes.inputStream(), serverBytes.size.toLong(), SERVER_REMOTE_PATH)

        // "Enable freeform windows" (a standard developer option). Without it, apps on
        // the DeX display open full screen with no window controls. Takes effect for
        // newly started apps; some builds want a reboot.
        try {
            Adb.runShell(manager, "settings put global enable_freeform_support 1")
        } catch (e: Exception) {
            Log.w(TAG, "Could not enable freeform windows", e)
        }

        val scid = Random.nextInt(1, Int.MAX_VALUE)
        val scidHex = String.format("%08x", scid)
        val command = "CLASSPATH=$SERVER_REMOTE_PATH app_process / com.genymobile.scrcpy.Server " +
            "$SERVER_VERSION scid=$scidHex log_level=info " +
            "video=true audio=false control=true video_codec=h264 " +
            "tunnel_forward=true send_device_meta=false send_dummy_byte=false " +
            "new_display=$displaySpec"

        val shell = manager.openStream("shell:$command")
        shellStream = shell
        checkNotStopped(shell)
        startServerLogReader(shell)

        setStarting("Waiting for display…")
        val socketName = "localabstract:scrcpy_$scidHex"
        val video = connectWithRetry(manager, socketName)
        videoStream = video
        checkNotStopped(video)
        val control = connectWithRetry(manager, socketName, retries = 8)
        controlStream = control
        checkNotStopped(control)

        val decoder = VideoDecoder(
            input = video.openInputStream(),
            onVideoSize = { w, h ->
                videoWidth = w
                videoHeight = h
                _state.value = State.Running(w, h, displayId)
            },
            onError = { message -> stop(message) },
        )
        videoDecoder = decoder
        decoder.start()

        val ctrl = Controller(control.openOutputStream(), control.openInputStream())
        controller = ctrl
        ctrl.start()

        setStarting("Waiting for display…")
    }

    /**
     * If stop() ran while a stream was being opened, its cleanup may have missed the
     * stream that was just assigned — close it here and bail out of startup.
     */
    private fun checkNotStopped(stream: AdbStream) {
        if (stopped.get()) {
            try {
                stream.close()
            } catch (e: Exception) {
                // Best effort.
            }
            throw CancellationException("Session stopped during startup")
        }
    }

    private fun setStarting(message: String) {
        if (_state.value !is State.Stopped) {
            _state.value = State.Starting(message)
        }
    }

    private suspend fun connectWithRetry(
        manager: AbsAdbConnectionManager,
        socketName: String,
        retries: Int = CONNECT_RETRIES,
    ): AdbStream {
        var lastError: Exception? = null
        repeat(retries) {
            try {
                val stream = withTimeoutOrNull(3000L) {
                    runInterruptible { manager.openStream(socketName) }
                }
                if (stream != null) return stream
            } catch (e: Exception) {
                lastError = e
            }
            delay(CONNECT_RETRY_DELAY_MS)
        }
        throw IOException(
            "The display did not start. Server said:\n${serverLogTail()}",
            lastError
        )
    }

    private fun startServerLogReader(shell: AdbStream) {
        Thread({
            try {
                val input = shell.openInputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val text = String(buffer, 0, read)
                    synchronized(serverLog) {
                        serverLog.append(text)
                        if (serverLog.length > 8192) serverLog.delete(0, serverLog.length - 8192)
                    }
                    parseDisplayId(text)
                    Log.i(TAG, "[server] ${text.trim()}")
                }
            } catch (e: IOException) {
                // Stream closed on stop; done.
            }
        }, "localdex-server-log").start()
    }

    private fun parseDisplayId(logChunk: String) {
        if (displayId != -1) return
        val id = displayIdPattern.find(logChunk)?.groupValues?.get(1)?.toIntOrNull() ?: return
        displayId = id
        Log.i(TAG, "Virtual display id: $id")

        // Freeform is not activated on app-created virtual displays on this Android
        // generation, but the per-display windowing mode (checked before all desktop
        // mode heuristics in DisplayWindowSettings.getWindowingModeLocked) can simply
        // be forced. Mode 5 = WINDOWING_MODE_FREEFORM.
        scope.launch {
            try {
                val manager = this@ScrcpySession.manager ?: return@launch
                Adb.runShell(manager, "wm set-display-windowing-mode -d $id 5")
                val check = Adb.runShell(manager, "wm get-display-windowing-mode -d $id")
                Log.i(TAG, "Windowing mode after force: $check")
            } catch (e: Exception) {
                Log.w(TAG, "Could not force freeform on display $id", e)
            }
        }

        // If video is already running, re-emit so observers pick up the id.
        val current = _state.value
        if (current is State.Running) {
            _state.value = current.copy(displayId = id)
        }
    }

    private fun serverLogTail(): String = synchronized(serverLog) {
        serverLog.toString().trim().takeLast(500).ifEmpty { "(no output)" }
    }

    private fun errorForUser(e: Exception): String {
        return e.message ?: e.javaClass.simpleName
    }

    /** Idempotent; safe from any thread. Tears everything down, then reports [error]. */
    fun stop(error: String? = null) {
        if (!stopped.compareAndSet(false, true)) return

        scope.launch {
            videoDecoder?.stop()
            controller?.stop()

            listOf(videoStream, controlStream, shellStream).forEach { stream ->
                try {
                    stream?.close()
                } catch (e: Exception) {
                    // Best effort.
                }
            }

            try {
                manager?.close()
            } catch (e: Exception) {
                // Best effort.
            }

            _state.value = State.Stopped(error)
            if (current === this@ScrcpySession) current = null
            scope.cancel()
        }
    }
}
