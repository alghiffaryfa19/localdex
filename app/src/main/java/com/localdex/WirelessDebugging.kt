package com.localdex

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Reads and (when possible) toggles the system's Wireless debugging switch.
 *
 * Flipping it needs WRITE_SECURE_SETTINGS, which the shell user can grant because the
 * permission is declared `development`. Once paired, the app grants it to itself over
 * its own ADB connection, and from then on can turn wireless debugging back on after
 * a reboot or a network change. Before the first pairing only the user can enable it.
 */
object WirelessDebugging {
    private const val TAG = "WirelessDebugging"

    // Settings.Global.ADB_WIFI_ENABLED is @hide, so the key is spelled out.
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    private const val VERIFY_TIMEOUT_MS = 3000L
    private const val POLL_INTERVAL_MS = 250L

    fun isEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $ADB_WIFI_ENABLED", e)
            false
        }
    }

    fun canToggle(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Turns wireless debugging on, returning whether it is actually on afterwards.
     * The write is verified by reading the value back: it can fail silently without
     * the permission, and some builds revert it.
     */
    suspend fun enable(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isEnabled(context)) return@withContext true
        if (!canToggle(context)) {
            Log.d(TAG, "Cannot enable wireless debugging: WRITE_SECURE_SETTINGS not held")
            return@withContext false
        }

        try {
            Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write $ADB_WIFI_ENABLED", e)
            return@withContext false
        }

        var waited = 0L
        while (waited < VERIFY_TIMEOUT_MS) {
            if (isEnabled(context)) return@withContext true
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }

        Log.w(TAG, "Wireless debugging did not come on after writing the setting")
        false
    }

    /**
     * Asks the device — over the app's own ADB connection — to grant the permission
     * that lets it toggle wireless debugging later. Requires a working connection now.
     */
    suspend fun tryAcquireTogglePermission(context: Context): Boolean {
        if (canToggle(context)) return true

        val command = "pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"
        Adb.runShellCommand(context, command)
            .onSuccess { output ->
                if (output.isNotEmpty()) Log.d(TAG, "pm grant said: $output")
            }
            .onFailure { error ->
                Log.w(TAG, "Could not run pm grant", error)
            }

        val granted = canToggle(context)
        Log.d(TAG, "WRITE_SECURE_SETTINGS granted: $granted")
        return granted
    }
}
