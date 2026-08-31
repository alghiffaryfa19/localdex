package com.localdex

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.localdex.shizuku.ShizukuSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity(), Shizuku.OnRequestPermissionResultListener {

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
        viewerButton.setOnClickListener { openViewer() }
        stopButton.setOnClickListener {
            DexService.stop(this)
            statusText.postDelayed({ checkStatus() }, 500)
        }

        Shizuku.addRequestPermissionResultListener(this)
        Shizuku.addBinderReceivedListenerSticky {
            checkStatus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(this)
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Shizuku permission granted!", Toast.LENGTH_SHORT).show()
            checkStatus()
        } else {
            Toast.makeText(this, "Shizuku permission was denied", Toast.LENGTH_LONG).show()
            checkStatus()
        }
    }

    private fun openViewer() {
        val intent = Intent(this, ViewerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        checkStatus()
    }

    private fun checkStatus(forceCheck: Boolean = false) {
        if (ShizukuSessionManager.currentDisplayId >= 0) {
            showRunningState()
            return
        }

        if (ShizukuSessionManager.hasShizukuPermission()) {
            showConnectedState()
        } else if (ShizukuSessionManager.isShizukuAvailable()) {
            showShizukuNeedPermissionState()
        } else {
            // Fallback: check Wireless ADB if Shizuku is not running
            checkAdbFallback(forceCheck)
        }
    }

    private fun showShizukuNeedPermissionState() {
        statusText.text = buildString {
            append("⚡ Shizuku Service Detected!\n\n")
            append("LocalDex can run with Hardware Direct Surface (zero video encoding, maximum battery efficiency).\n\n")
            append("Please grant Shizuku permission to continue.")
        }
        actionButton.text = "Grant Shizuku Permission"
        actionButton.setOnClickListener {
            ShizukuSessionManager.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
        actionButton.visibility = View.VISIBLE
        refreshButton.visibility = View.VISIBLE
        configGroup.visibility = View.GONE
        startButton.visibility = View.GONE
        viewerButton.visibility = View.GONE
        stopButton.visibility = View.GONE
    }

    private fun showRunningState() {
        val displayId = ShizukuSessionManager.currentDisplayId
        statusText.text = "🖥️ DeX is running with Direct Surface on display $displayId.\n\n" +
            "Zero video encoding overhead / 60+ FPS hardware composite."
        actionButton.visibility = View.GONE
        refreshButton.visibility = View.GONE
        configGroup.visibility = View.GONE
        startButton.visibility = View.GONE
        viewerButton.visibility = View.VISIBLE
        stopButton.visibility = View.VISIBLE
    }

    private fun showConnectedState() {
        statusText.text = "⚡ Shizuku connected (Elevated Shell Mode).\n\n" +
            "Hardware Direct Surface active — zero video encode/decode latency.\n" +
            "Display spec is WIDTHxHEIGHT/DPI (e.g. 1920x1440/240)."
        actionButton.visibility = View.GONE
        refreshButton.visibility = View.VISIBLE
        configGroup.visibility = View.VISIBLE
        startButton.visibility = View.VISIBLE
        viewerButton.visibility = View.GONE
        stopButton.visibility = View.GONE
    }

    private fun checkAdbFallback(forceCheck: Boolean) {
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                Adb.getConnectionStatus(this@MainActivity, forceCheck)
            }

            when (status) {
                Adb.ConnectionStatus.CONNECTED -> {
                    statusText.text = "✅ ADB connected (Legacy mode).\n\n" +
                        "Tip: For better performance & battery life, start Shizuku!"
                    actionButton.visibility = View.GONE
                    refreshButton.visibility = View.VISIBLE
                    configGroup.visibility = View.VISIBLE
                    startButton.visibility = View.VISIBLE
                    viewerButton.visibility = View.GONE
                    stopButton.visibility = View.GONE
                }
                else -> {
                    showSetupChecklist()
                }
            }
        }
    }

    private fun showSetupChecklist() {
        statusText.text = buildString {
            append("Setup Required:\n\n")
            append("Option 1 (Recommended): Start Shizuku\n")
            append("   • Open Shizuku app\n")
            append("   • Start Shizuku via Wireless Debugging\n")
            append("   • Return here and tap Refresh\n\n")
            append("Option 2: Pair Wireless ADB directly\n")
            append("   • Tap \"Start Pairing\" below\n")
        }

        actionButton.text = "Open Shizuku / Start Pairing"
        actionButton.setOnClickListener {
            val shizukuIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (shizukuIntent != null) {
                startActivity(shizukuIntent)
            } else {
                startPairing()
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
        openViewer()
    }

    companion object {
        const val EXTRA_FROM_PAIRING = "com.localdex.FROM_PAIRING"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }
}
