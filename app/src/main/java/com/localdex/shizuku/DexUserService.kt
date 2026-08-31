package com.localdex.shizuku

import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import java.lang.reflect.Method

@Keep
class DexUserService : IDexUserService.Stub() {

    companion object {
        private const val TAG = "DexUserService"

        // VirtualDisplay flags matching scrcpy exactly (plus TRUSTED for Samsung DeX):
        // PUBLIC (1) | OWN_CONTENT_ONLY (8) | DESTROY_CONTENT_ON_REMOVAL (256) | TRUSTED (1024)
        private const val VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 shl 0
        private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10

        private const val VIRTUAL_DISPLAY_FLAGS =
            VIRTUAL_DISPLAY_FLAG_PUBLIC or
            VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL or
            VIRTUAL_DISPLAY_FLAG_TRUSTED
    }

    private var virtualDisplayToken: Any? = null
    private var virtualDisplayId: Int = -1

    private var inputManager: Any? = null
    private var injectInputMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    init {
        initInputManager()
    }

    private fun initInputManager() {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val inputBinder = getServiceMethod.invoke(null, "input") as? IBinder

            val iInputManagerClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = iInputManagerClass.getMethod("asInterface", IBinder::class.java)
            inputManager = asInterfaceMethod.invoke(null, inputBinder)

            val inputEventClass = Class.forName("android.view.InputEvent")
            injectInputMethod = inputManager?.javaClass?.getMethod(
                "injectInputEvent",
                inputEventClass,
                Int::class.javaPrimitiveType
            )

            try {
                setDisplayIdMethod = MotionEvent::class.java.getMethod(
                    "setDisplayId",
                    Int::class.javaPrimitiveType
                )
            } catch (e: Exception) {
                Log.w(TAG, "MotionEvent.setDisplayId method not found", e)
            }

            try {
                KeyEvent::class.java.getMethod(
                    "setDisplayId",
                    Int::class.javaPrimitiveType
                )
            } catch (e: Exception) {
                // optional
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize InputManager", e)
        }
    }

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface
    ): String {
        releaseVirtualDisplay()
        Log.i(TAG, "Creating VirtualDisplay: $name ($width x $height @ $dpi dpi, surface: $surface)")

        return try {
            if (android.os.Looper.myLooper() == null) {
                android.os.Looper.prepare()
            }
            
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread")
            var currentActivityThread = currentActivityThreadMethod.invoke(null)
            if (currentActivityThread == null) {
                val systemMainMethod = activityThreadClass.getDeclaredMethod("systemMain")
                currentActivityThread = systemMainMethod.invoke(null)
            }
            val getSystemContextMethod = activityThreadClass.getDeclaredMethod("getSystemContext")
            val systemContext = getSystemContextMethod.invoke(currentActivityThread) as android.content.Context
            
            val shellContext = systemContext.createPackageContext("com.android.shell", android.content.Context.CONTEXT_IGNORE_SECURITY)
            val displayManager = shellContext.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            
            val vDisplay = displayManager.createVirtualDisplay(
                name,
                width,
                height,
                dpi,
                surface,
                VIRTUAL_DISPLAY_FLAGS
            )
            
            val id = vDisplay?.display?.displayId
            if (id != null) {
                virtualDisplayToken = vDisplay
                virtualDisplayId = id
                
                // Force freeform windowing mode (AOSP Desktop Mode) like LocalDex does
                Runtime.getRuntime().exec(arrayOf("wm", "set-display-windowing-mode", "-d", id.toString(), "5")).waitFor()
                
                // Launch Samsung DeX Launcher specifically to avoid Launcher Chooser dialogs
                android.util.Log.i(TAG, "Launching DeX on display $id via shell...")
                Runtime.getRuntime().exec(arrayOf(
                    "am", "start",
                    "-n", "com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher",
                    "--display", id.toString()
                )).waitFor()

                // Launch DexModeActivity to ensure the environment (taskbar, window management) is initialized
                android.util.Log.i(TAG, "Initializing DexModeActivity on display $id...")
                Runtime.getRuntime().exec(arrayOf(
                    "am", "start",
                    "-n", "com.android.settings/.Settings\$DexModeActivity",
                    "--display", id.toString()
                )).waitFor()
                
                id.toString()
            } else {
                "Error: virtualDisplay is null"
            }
        } catch (e: Exception) {
            val trace = android.util.Log.getStackTraceString(e)
            e.printStackTrace()
            "Error: ${e.message}\n$trace"
        }
    }

    private fun setupDisplayDesktop(displayId: Int) {
        try {
            // 1. Force freeform windowing mode
            Runtime.getRuntime().exec(arrayOf("wm", "set-display-windowing-mode", "-d", "$displayId", "5")).waitFor()
            Runtime.getRuntime().exec(arrayOf("settings", "put", "global", "enable_freeform_support", "1")).waitFor()

            // 2. Launch Home/DeX Desktop Launcher on the virtual display to start rendering content
            Log.i(TAG, "Launching Desktop Launcher on display $displayId...")
            Runtime.getRuntime().exec(arrayOf(
                "am", "start",
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.HOME",
                "--display", "$displayId"
            )).waitFor()

            // 3. Specifically launch Samsung Desktop Launcher if present
            try {
                Runtime.getRuntime().exec(arrayOf(
                    "am", "start",
                    "-n", "com.sec.android.app.desktoplauncher/.DesktopLauncher",
                    "--display", "$displayId"
                )).waitFor()
            } catch (e: Exception) {
                // Ignore if not present
            }

            Log.i(TAG, "Desktop setup completed on display $displayId")
        } catch (e: Exception) {
            Log.w(TAG, "Error setting up display desktop", e)
        }
    }

    override fun releaseVirtualDisplay() {
        val token = virtualDisplayToken
        if (token != null) {
            try {
                val releaseMethod = token.javaClass.methods.firstOrNull { it.name == "release" }
                releaseMethod?.invoke(token)
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing virtual display token", e)
            }
            virtualDisplayToken = null
        }
        virtualDisplayId = -1
    }

    override fun getDisplayId(): Int = virtualDisplayId

    override fun setDisplayWindowingMode(displayId: Int, mode: Int) {
        try {
            Runtime.getRuntime().exec(arrayOf("wm", "set-display-windowing-mode", "-d", "$displayId", "$mode")).waitFor()
            Runtime.getRuntime().exec(arrayOf("settings", "put", "global", "enable_freeform_support", "1")).waitFor()
            Log.i(TAG, "Applied windowing mode $mode to display $displayId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set display windowing mode", e)
        }
    }

    override fun injectMotionEvent(event: MotionEvent, displayId: Int): Boolean {
        return try {
            if (displayId >= 0 && setDisplayIdMethod != null) {
                setDisplayIdMethod?.invoke(event, displayId)
            }
            injectInputMethod?.invoke(inputManager, event, 0) as? Boolean ?: true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inject MotionEvent", e)
            false
        }
    }

    override fun injectKeyEvent(event: KeyEvent, displayId: Int): Boolean {
        return try {
            try {
                val setDisplayId = event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                setDisplayId.invoke(event, displayId)
            } catch (e: Exception) {
                // ignore
            }
            injectInputMethod?.invoke(inputManager, event, 0) as? Boolean ?: true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inject KeyEvent", e)
            false
        }
    }

    override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
        if (code == 16777114) { // Shizuku constant for destroy
            destroy()
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun destroy() {
        releaseVirtualDisplay()
        System.exit(0)
    }
}
