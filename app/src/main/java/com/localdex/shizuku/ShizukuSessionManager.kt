package com.localdex.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

object ShizukuSessionManager {

    private const val TAG = "ShizukuSessionManager"

    sealed class State {
        object Idle : State()
        object Connecting : State()
        data class Running(val displayId: Int, val width: Int, val height: Int) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var userService: IDexUserService? = null
    var currentDisplayId: Int = -1
        private set

    private var activeWidth: Int = 1920
    private var activeHeight: Int = 1440
    private var activeDpi: Int = 240

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            if (!isShizukuAvailable()) return false
            if (Shizuku.isPreV11()) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Throwable) {
            false
        }
    }

    fun requestPermission(requestCode: Int) {
        if (isShizukuAvailable() && !hasShizukuPermission()) {
            Shizuku.requestPermission(requestCode)
        }
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Shizuku UserService connected")
            userService = IDexUserService.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Shizuku UserService disconnected")
            userService = null
            _state.value = State.Idle
        }
    }

    suspend fun getOrBindUserService(context: Context): IDexUserService? {
        userService?.let { return it }

        if (!hasShizukuPermission()) {
            _state.value = State.Error("Shizuku permission not granted")
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val serviceStub = IDexUserService.Stub.asInterface(service)
                    userService = serviceStub
                    if (continuation.isActive) {
                        continuation.resume(serviceStub)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    userService = null
                    _state.value = State.Idle
                }
            }

            try {
                val args = Shizuku.UserServiceArgs(
                    ComponentName(context.packageName, DexUserService::class.java.name)
                )
                    .daemon(false)
                    .processNameSuffix("dex_service_v4")
                    .debuggable(false)
                    .version(4)

                Shizuku.bindUserService(args, connection)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind Shizuku UserService", e)
                _state.value = State.Error("Failed to bind Shizuku UserService: ${e.message}")
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }

    suspend fun startSession(
        context: Context,
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int
    ): Boolean {
        _state.value = State.Connecting
        activeWidth = width
        activeHeight = height
        activeDpi = dpi

        val service = getOrBindUserService(context)
        if (service == null) {
            _state.value = State.Error("Could not connect to Shizuku shell service")
            return false
        }

        return try {
            val result = service.createVirtualDisplay(
                "LocalDex-Direct",
                width,
                height,
                dpi,
                surface
            )
            
            val displayId = result.toIntOrNull()
            
            if (displayId != null) {
                currentDisplayId = displayId
                Log.i(TAG, "Direct Surface VirtualDisplay created with ID: $displayId")
                _state.value = State.Running(displayId, width, height)
                true
            } else {
                currentDisplayId = -1
                Log.e(TAG, "Failed to create virtual display: $result")
                _state.value = State.Error("Failed to create virtual display: $result")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting session", e)
            _state.value = State.Error("Session error: ${e.message}")
            false
        }
    }

    fun injectMotionEvent(event: MotionEvent) {
        val service = userService ?: return
        val displayId = currentDisplayId
        if (displayId >= 0) {
            try {
                service.injectMotionEvent(event, displayId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inject MotionEvent", e)
            }
        }
    }

    fun injectKeyEvent(event: KeyEvent) {
        val service = userService ?: return
        val displayId = currentDisplayId
        if (displayId >= 0) {
            try {
                service.injectKeyEvent(event, displayId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inject KeyEvent", e)
            }
        }
    }

    fun stopSession() {
        try {
            userService?.releaseVirtualDisplay()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing virtual display", e)
        }
        currentDisplayId = -1
        _state.value = State.Idle
    }
}
