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

        // VirtualDisplay flags:
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
    ): Int {
        releaseVirtualDisplay()
        Log.i(TAG, "Creating VirtualDisplay: $name ($width x $height @ $dpi dpi, surface: $surface)")

        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val displayBinder = getServiceMethod.invoke(null, "display") as? IBinder

            val iDisplayManagerClass = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            val asInterfaceMethod = iDisplayManagerClass.getMethod("asInterface", IBinder::class.java)
            val displayManagerService = asInterfaceMethod.invoke(null, displayBinder)
                ?: throw RuntimeException("IDisplayManager service is null")

            var displayId = -1

            // Try all createVirtualDisplay method overloads on IDisplayManager
            val methods = displayManagerService.javaClass.methods.filter { it.name == "createVirtualDisplay" }
            Log.i(TAG, "Found ${methods.size} createVirtualDisplay method overloads on IDisplayManager")

            for (method in methods) {
                try {
                    val paramTypes = method.parameterTypes
                    val args = arrayOfNulls<Any>(paramTypes.size)

                    var stringCount = 0
                    var intCount = 0

                    for (i in paramTypes.indices) {
                        val type = paramTypes[i]
                        when {
                            type == Surface::class.java -> args[i] = surface
                            type == String::class.java -> {
                                stringCount++
                                args[i] = when (stringCount) {
                                    1 -> "com.android.shell" // packageName
                                    2 -> name               // display name
                                    else -> null            // uniqueId
                                }
                            }
                            type == Int::class.javaPrimitiveType -> {
                                intCount++
                                args[i] = when (intCount) {
                                    1 -> width
                                    2 -> height
                                    3 -> dpi
                                    4 -> VIRTUAL_DISPLAY_FLAGS
                                    else -> 0
                                }
                            }
                            type.name.contains("IVirtualDisplayCallback") -> args[i] = null
                            type.name.contains("IMediaProjection") -> args[i] = null
                            type == IBinder::class.java -> args[i] = null
                            else -> args[i] = null
                        }
                    }

                    Log.i(TAG, "Invoking IDisplayManager.createVirtualDisplay with ${args.size} params: ${paramTypes.map { it.simpleName }}")
                    val result = method.invoke(displayManagerService, *args)
                    virtualDisplayToken = result

                    if (result is Int) {
                        displayId = result
                    } else if (result != null) {
                        try {
                            val idMethod = result.javaClass.getMethod("getDisplayId")
                            displayId = idMethod.invoke(result) as Int
                        } catch (e: Exception) {
                            val getDisplay = result.javaClass.getMethod("getDisplay")
                            val displayObj = getDisplay.invoke(result)
                            val idMethod = displayObj.javaClass.getMethod("getDisplayId")
                            displayId = idMethod.invoke(displayObj) as Int
                        }
                    }

                    if (displayId >= 0) {
                        Log.i(TAG, "Successfully created VirtualDisplay with ID: $displayId")
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed overload attempt: ${e.message}")
                }
            }

            if (displayId < 0) {
                throw RuntimeException("Could not create VirtualDisplay via any IDisplayManager method overload")
            }

            virtualDisplayId = displayId

            // Configure windowing mode and launch launcher/DeX on the new display
            setupDisplayDesktop(displayId)

            return displayId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VirtualDisplay", e)
            throw RuntimeException("Failed to create VirtualDisplay: ${e.message}", e)
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
