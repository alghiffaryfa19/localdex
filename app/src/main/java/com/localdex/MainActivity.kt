package com.localdex

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.localdex.scrcpy.ScrcpySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var refreshButton: Button
    private lateinit var configGroup: View
    private lateinit var displaySpecField: EditText
    private lateinit var startButton: Button
    private lateinit var viewerButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        actionButton = findViewById(R.id.actionButton)
        refreshButton = findViewById(R.id.refreshButton)
        configGroup = findViewById(R.id.configGroup)
        displaySpecField = findViewById(R.id.displaySpecField)
        startButton = findViewById(R.id.startButton)
        viewerButton = findViewById(R.id.viewerButton)
        stopButton = findViewById(R.id.stopButton)

        displaySpecField.setText(Prefs.getDisplaySpec(this))

        refreshButton.setOnClickListener { checkStatus(forceCheck = true) }
        startButton.setOnClickListener { startDex() }
        viewerButton.setOnClickListener {
            startActivity(Intent(this, ViewerActivity::class.java))
        }
        stopButton.setOnClickListener {
            DexService.stop(this)
            statusText.postDelayed({ checkStatus() }, 500)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val fromPairing = intent?.getBooleanExtra(EXTRA_FROM_PAIRING, false) == true
        if (fromPairing) {
            intent.removeExtra(EXTRA_FROM_PAIRING)
        }
        checkStatus(forceCheck = fromPairing)
    }

    private fun checkStatus(forceCheck: Boolean = false) {
        if (ScrcpySession.current != null) {
            showRunningState()
            return
        }

        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                Adb.getConnectionStatus(this@MainActivity, forceCheck)
            }

            when (status) {
                Adb.ConnectionStatus.CONNECTED -> {
                    showConnectedState()
                    stopService(Intent(this@MainActivity, PairingInputService::class.java))
                    Prefs.setHasPairedBefore(this@MainActivity)
                    acquireWirelessDebuggingPermission()
                }
                else -> {
                    // Holding WRITE_SECURE_SETTINGS lets us switch wireless debugging
                    // back on ourselves (it turns off on reboot / network change).
                    if (Prefs.hasPairedBefore(this@MainActivity) &&
                        !WirelessDebugging.isEnabled(this@MainActivity) &&
                        WirelessDebugging.canToggle(this@MainActivity)
                    ) {
                        statusText.text = "Turning wireless debugging on…"
                        val enabled = WirelessDebugging.enable(this@MainActivity)
                        if (enabled) {
                            checkStatus(forceCheck = true)
                            return@launch
                        }
                    }
                    showSetupChecklist()
                }
            }
        }
    }

    private fun acquireWirelessDebuggingPermission() {
        if (permissionGrantAttempted || WirelessDebugging.canToggle(this)) return
        permissionGrantAttempted = true
        lifecycleScope.launch {
            WirelessDebugging.tryAcquireTogglePermission(this@MainActivity)
        }
    }

    private fun showRunningState() {
        val displayId = ScrcpySession.current?.displayId ?: -1
        statusText.text = if (displayId >= 0) {
            "🖥️ DeX is running on display $displayId.\n\n" +
                "From a computer on the same adb connection you can open the same " +
                "desktop with:\n\nscrcpy --display-id=$displayId"
        } else {
            // The display id arrives from the server log moments after start.
            statusText.postDelayed({ if (ScrcpySession.current != null) checkStatus() }, 1000)
            "🖥️ DeX session is starting…"
        }
        actionButton.visibility = View.GONE
        refreshButton.visibility = View.GONE
        configGroup.visibility = View.GONE
        startButton.visibility = View.GONE
        viewerButton.visibility = View.VISIBLE
        stopButton.visibility = View.VISIBLE
    }

    private fun showConnectedState() {
        statusText.text = "✅ ADB connected.\n\n" +
            "Display spec is WIDTHxHEIGHT/DPI — lower DPI means more room, higher " +
            "DPI means bigger UI."
        actionButton.visibility = View.GONE
        refreshButton.visibility = View.VISIBLE
        configGroup.visibility = View.VISIBLE
        startButton.visibility = View.VISIBLE
        viewerButton.visibility = View.GONE
        stopButton.visibility = View.GONE
    }

    private fun showSetupChecklist() {
        val devModeEnabled = isDeveloperOptionsEnabled()
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()

        val step1 = if (devModeEnabled) "✅" else "⬜"
        val step2 = if (notificationsEnabled) "✅" else "⬜"
        val step3 = if (devModeEnabled && notificationsEnabled) "⬜" else "⚪"

        statusText.text = buildString {
            append("Setup Progress:\n\n")
            append("$step1 Step 1: Enable Developer Options\n")
            if (!devModeEnabled) {
                append("   • Settings → About phone → Software information\n")
                append("   • Tap \"Build number\" 7 times\n\n")
            } else {
                append("   Complete!\n\n")
            }
            append("$step2 Step 2: Enable Notifications\n")
            if (!notificationsEnabled) {
                append("   • Required to enter pairing codes\n\n")
            } else {
                append("   Complete!\n\n")
            }
            append("$step3 Step 3: Pair with Wireless ADB\n")
            if (devModeEnabled && notificationsEnabled) {
                append("   • Tap \"Start Pairing\" below\n")
                append("   • In Wireless debugging, tap \"Pair device with pairing code\"\n")
                append("   • Enter the code in the LocalDex notification\n")
            } else {
                append("   Complete previous steps first\n")
            }
        }

        when {
            !devModeEnabled -> {
                actionButton.text = "Open Settings"
                actionButton.setOnClickListener {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Please open Settings manually", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            !notificationsEnabled -> {
                actionButton.text = "Enable Notifications"
                actionButton.setOnClickListener {
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                }
            }
            else -> {
                actionButton.text = "Start Pairing"
                actionButton.setOnClickListener { startPairing() }
            }
        }

        actionButton.visibility = View.VISIBLE
        refreshButton.visibility = View.VISIBLE
        configGroup.visibility = View.GONE
        startButton.visibility = View.GONE
        viewerButton.visibility = View.GONE
        stopButton.visibility = View.GONE
    }

    private fun startPairing() {
        startService(Intent(this, PairingInputService::class.java))
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (ex: Exception) {
                Toast.makeText(
                    this,
                    "Open Settings → Developer options → Wireless debugging manually",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startDex() {
        val spec = displaySpecField.text.toString().trim()
        if (!Regex("\\d{3,4}x\\d{3,4}/\\d{2,3}").matches(spec)) {
            Toast.makeText(this, "Display spec must look like 1920x1440/240", Toast.LENGTH_LONG).show()
            return
        }
        Prefs.setDisplaySpec(this, spec)

        DexService.start(this)
        startActivity(Intent(this, ViewerActivity::class.java))
    }

    private fun isDeveloperOptionsEnabled(): Boolean {
        return try {
            Settings.Global.getInt(
                contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
        } catch (e: Exception) {
            true
        }
    }

    companion object {
        /** Set by [PairingInputService] when it brings the app forward after pairing. */
        const val EXTRA_FROM_PAIRING = "com.localdex.FROM_PAIRING"

        // Only try the self-grant once per session.
        private var permissionGrantAttempted = false
    }
}
