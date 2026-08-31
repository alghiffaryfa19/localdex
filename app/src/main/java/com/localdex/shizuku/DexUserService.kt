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

        // VirtualDisplay flags: PUBLIC | OWN_CONTENT_ONLY | DESTROY_CONTENT_ON_REMOVAL | TRUSTED
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
            Log.e(TAG, "Failed to initialize InputManager via reflection", e)
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
        Log.i(TAG, "Creating VirtualDisplay: $name ($width x $height @ $dpi dpi)")

        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val displayBinder = getServiceMethod.invoke(null, "display") as? IBinder

            val iDisplayManagerClass = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            val asInterfaceMethod = iDisplayManagerClass.getMethod("asInterface", IBinder::class.java)
            val displayManagerService = asInterfaceMethod.invoke(null, displayBinder)

            // Look for createVirtualDisplay in IDisplayManager
            val methods = displayManagerService?.javaClass?.methods ?: emptyArray()
            val createMethod = methods.firstOrNull { it.name == "createVirtualDisplay" }
                ?: throw NoSuchMethodException("createVirtualDisplay method not found on IDisplayManager")

            val paramTypes = createMethod.parameterTypes
            val args = arrayOfNulls<Any>(paramTypes.size)

            for (i in paramTypes.indices) {
                when {
                    paramTypes[i] == String::class.java && i == 0 -> args[i] = null // callback or packageName
                    paramTypes[i] == String::class.java -> args[i] = if (args[0] == null && i == 2) "com.android.shell" else name
                    paramTypes[i] == Int::class.javaPrimitiveType -> {
                        // Match width, height, dpi, flags
                        val remainingInts = paramTypes.take(i + 1).count { it == Int::class.javaPrimitiveType }
                        args[i] = when (remainingInts) {
                            1 -> width
                            2 -> height
                            3 -> dpi
                            4 -> VIRTUAL_DISPLAY_FLAGS
                            else -> 0
                        }
                    }
                    paramTypes[i] == Surface::class.java -> args[i] = surface
                    paramTypes[i] == IBinder::class.java -> args[i] = null
                    else -> args[i] = null
                }
            }

            // More precise fallback if the heuristic above needs direct mapping:
            // Standard AOSP signature:
            // createVirtualDisplay(IVirtualDisplayCallback callback, IMediaProjection projection,
            //                      String packageName, String name, int width, int height, int densityDpi,
            //                      Surface surface, int flags, String uniqueId)
            val result = try {
                createMethod.invoke(displayManagerService, *args)
            } catch (e: Exception) {
                Log.w(TAG, "First createVirtualDisplay attempt failed, trying DisplayManagerGlobal", e)
                createViaDisplayManagerGlobal(name, width, height, dpi, surface)
            }

            virtualDisplayToken = result
            if (result is Int) {
                virtualDisplayId = result
            } else if (result != null) {
                // If it returns a token or VirtualDisplay object, get the display ID
                try {
                    val getDisplayId = result.javaClass.getMethod("getDisplayId")
                    virtualDisplayId = getDisplayId.invoke(result) as Int
                } catch (e: Exception) {
                    val getDisplay = result.javaClass.getMethod("getDisplay")
                    val displayObj = getDisplay.invoke(result)
                    val idMethod = displayObj.javaClass.getMethod("getDisplayId")
                    virtualDisplayId = idMethod.invoke(displayObj) as Int
                }
            }

            Log.i(TAG, "VirtualDisplay created successfully with ID: $virtualDisplayId")

            // Enable freeform mode on this display
            if (virtualDisplayId >= 0) {
                setDisplayWindowingMode(virtualDisplayId, 5) // 5 = WINDOWING_MODE_FREEFORM
            }

            return virtualDisplayId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VirtualDisplay", e)
            throw RuntimeException("Failed to create VirtualDisplay: ${e.message}", e)
        }
    }

    private fun createViaDisplayManagerGlobal(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface
    ): Any? {
        val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val getInstanceMethod = dmgClass.getMethod("getInstance")
        val dmg = getInstanceMethod.invoke(null)

        val methods = dmg.javaClass.methods
        val createMethod = methods.first { it.name == "createVirtualDisplay" }
        val paramTypes = createMethod.parameterTypes
        val args = arrayOfNulls<Any>(paramTypes.size)

        for (i in paramTypes.indices) {
            when {
                paramTypes[i] == String::class.java -> args[i] = name
                paramTypes[i] == Int::class.javaPrimitiveType -> {
                    val remainingInts = paramTypes.take(i + 1).count { it == Int::class.javaPrimitiveType }
                    args[i] = when (remainingInts) {
                        1 -> width
                        2 -> height
                        3 -> dpi
                        4 -> VIRTUAL_DISPLAY_FLAGS
                        else -> 0
                    }
                }
                paramTypes[i] == Surface::class.java -> args[i] = surface
                else -> args[i] = null
            }
        }

        return createMethod.invoke(dmg, *args)
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
            // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
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
