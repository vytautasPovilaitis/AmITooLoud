package com.example.amitooloud

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val recordAudioPermission = Manifest.permission.RECORD_AUDIO
    private val postNotificationsPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }

    private var currentThreshold = 70.0
    private lateinit var switchMonitor: SwitchCompat
    private lateinit var controlCard: com.google.android.material.card.MaterialCardView
    private lateinit var tvDebugDb: TextView

    private val PRESET_LIBRARY = 45.0
    private val PRESET_KITCHEN = 65.0
    private val PRESET_RESTAURANT = 55.0

    private val noiseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Ignore a late update that arrives after monitoring was switched off
            if (!switchMonitor.isChecked) return
            val db = intent?.getDoubleExtra(NoiseMonitorService.EXTRA_DB, 0.0) ?: 0.0

            tvDebugDb.text = String.format(Locale.US, "Debug: %.1f dB", db)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        controlCard = findViewById(R.id.controlCard)
        tvDebugDb = findViewById(R.id.tvDebugDb)
        val rgPresets = findViewById<RadioGroup>(R.id.rgPresets)
        switchMonitor = findViewById(R.id.switchMonitor)

        // Edge-to-edge is enforced on Android 15+: keep content clear of the status bar /
        // camera cutout at the top and the navigation bar at the bottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.appBarLayout)) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(top = top.top)
            insets
        }
        // Padding goes on the card's content: MaterialCardView ignores its own padding
        val controlContent = findViewById<View>(R.id.controlContent)
        val contentPaddingBottom = controlContent.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(controlCard) { _, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            controlContent.updatePadding(bottom = contentPaddingBottom + navBar.bottom)
            insets
        }

        val prefs = getPreferences(Context.MODE_PRIVATE)
        // Stored by entry name: resource ids aren't stable between builds
        val savedPreset = prefs.getString(KEY_PRESET, null)
        rgPresets.check(when (savedPreset) {
            "rbKitchen" -> R.id.rbKitchen
            "rbRestaurant" -> R.id.rbRestaurant
            else -> R.id.rbLibrary
        })
        currentThreshold = thresholdFor(rgPresets.checkedRadioButtonId)

        // Restore the switch if the service is still running from before a recreation
        switchMonitor.isChecked = NoiseMonitorService.isMonitoring
        updateStatus(switchMonitor.isChecked)

        rgPresets.setOnCheckedChangeListener { _, checkedId ->
            currentThreshold = thresholdFor(checkedId)
            prefs.edit().putString(KEY_PRESET, resources.getResourceEntryName(checkedId)).apply()

            if (switchMonitor.isChecked) {
                updateServiceThreshold()
            }
        }

        switchMonitor.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (checkPermissions()) {
                    startNoiseService()
                } else {
                    requestPermissions()
                    switchMonitor.isChecked = false
                }
            } else {
                stopNoiseService()
            }
        }
    }

    private fun thresholdFor(presetId: Int): Double = when (presetId) {
        R.id.rbKitchen -> PRESET_KITCHEN
        R.id.rbRestaurant -> PRESET_RESTAURANT
        else -> PRESET_LIBRARY
    }

    override fun onStart() {
        super.onStart()
        // The service may have stopped itself (e.g. the microphone became unavailable)
        if (switchMonitor.isChecked && !NoiseMonitorService.isMonitoring) {
            switchMonitor.isChecked = false
        }
        val filter = IntentFilter(NoiseMonitorService.ACTION_NOISE_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noiseReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(noiseReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(noiseReceiver)
        } catch (_: IllegalArgumentException) {
            // receiver was never registered (e.g. permissions denied before onStart registered it)
        }
    }

    private fun updateStatus(isMonitoring: Boolean) {
        if (!isMonitoring) {
            tvDebugDb.text = "Debug: -- dB"
        }
    }

    private fun checkPermissions(): Boolean {
        val audioPermission = ContextCompat.checkSelfPermission(this, recordAudioPermission) == PackageManager.PERMISSION_GRANTED
        val notificationPermission = if (postNotificationsPermission != null) {
            ContextCompat.checkSelfPermission(this, postNotificationsPermission) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return audioPermission && notificationPermission
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(recordAudioPermission)
        postNotificationsPermission?.let { permissions.add(it) }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
    }

    private fun startNoiseService() {
        val intent = Intent(this, NoiseMonitorService::class.java).apply {
            putExtra(NoiseMonitorService.EXTRA_THRESHOLD, currentThreshold)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStatus(true)
    }

    // Plain startService is enough while visible; startForegroundService would oblige the
    // service to call startForeground() again for every preset change
    private fun updateServiceThreshold() {
        startService(Intent(this, NoiseMonitorService::class.java).apply {
            putExtra(NoiseMonitorService.EXTRA_THRESHOLD, currentThreshold)
        })
    }

    private fun stopNoiseService() {
        val intent = Intent(this, NoiseMonitorService::class.java)
        stopService(intent)
        updateStatus(false)
    }

    companion object {
        private const val KEY_PRESET = "preset"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // The switch listener starts the service
                switchMonitor.isChecked = true
            } else {
                Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
            }
        }
    }
}
