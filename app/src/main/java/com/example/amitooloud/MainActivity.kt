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
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val recordAudioPermission = Manifest.permission.RECORD_AUDIO
    private val postNotificationsPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }

    private var currentThreshold = 70.0
    private var smoothedRatio = 0.0f
    private lateinit var switchMonitor: SwitchCompat
    private lateinit var ivNoiseEmoji: ImageView
    private lateinit var noiseCard: com.google.android.material.card.MaterialCardView
    private lateinit var tvDebugDb: TextView
    private lateinit var innerNoiseLayout: android.widget.LinearLayout

    private val PRESET_LIBRARY = 40.0
    private val PRESET_KITCHEN = 80.0
    private val PRESET_RESTAURANT = 65.0

    private val noiseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val db = intent?.getDoubleExtra(NoiseMonitorService.EXTRA_DB, 0.0) ?: 0.0
            
            // Update debug text
            tvDebugDb.text = String.format(Locale.US, "Debug: %.1f dB", db)
            
            // Calculate target ratio strictly proportional to the current threshold
            // This means if threshold is 40, then 40dB = 1.0 (Red)
            // If threshold is 80, then 40dB = 0.5 (Yellow)
            val targetRatio = (db / currentThreshold).coerceIn(0.0, 1.0).toFloat()
            
            // Apply smoothing: 30% target, 70% previous for faster updates
            smoothedRatio = smoothedRatio * 0.70f + targetRatio * 0.30f
            
            // Define color points
            val colorSafe = ContextCompat.getColor(this@MainActivity, R.color.status_safe)
            val colorWarning = ContextCompat.getColor(this@MainActivity, R.color.status_warning) // Yellow
            val colorOrange = android.graphics.Color.rgb(255, 152, 0) // Orange
            val colorDanger = ContextCompat.getColor(this@MainActivity, R.color.status_danger) // Red

            // Multi-stage interpolation using the smoothed ratio
            // Logic: Stay green until 70%, transition to yellowish until 100%, then dark red.
            val finalColor = when {
                smoothedRatio < 0.70f -> colorSafe
                smoothedRatio < 0.90f -> interpolateColor(colorSafe, colorWarning, (smoothedRatio - 0.70f) / 0.20f)
                else -> interpolateColor(colorWarning, colorDanger, ((smoothedRatio - 0.90f) / 0.10f).coerceAtMost(1.0f))
            }
            
            // Clear any filter on the image itself to keep the person natural
            ivNoiseEmoji.clearColorFilter()
            
            // Set the inner layout background color
            innerNoiseLayout.setBackgroundColor(finalColor)
        }
    }

    private fun interpolateColor(colorStart: Int, colorEnd: Int, ratio: Float): Int {
        val r = (android.graphics.Color.red(colorStart) + (android.graphics.Color.red(colorEnd) - android.graphics.Color.red(colorStart)) * ratio).toInt()
        val g = (android.graphics.Color.green(colorStart) + (android.graphics.Color.green(colorEnd) - android.graphics.Color.green(colorStart)) * ratio).toInt()
        val b = (android.graphics.Color.blue(colorStart) + (android.graphics.Color.blue(colorEnd) - android.graphics.Color.blue(colorStart)) * ratio).toInt()
        return android.graphics.Color.rgb(r, g, b)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ivNoiseEmoji = findViewById(R.id.ivNoiseEmoji)
        noiseCard = findViewById(R.id.noiseCard)
        tvDebugDb = findViewById(R.id.tvDebugDb)
        innerNoiseLayout = findViewById(R.id.innerNoiseLayout)
        val rgPresets = findViewById<RadioGroup>(R.id.rgPresets)
        switchMonitor = findViewById(R.id.switchMonitor)

        updateStatus(false)
        
        rgPresets.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbLibrary -> {
                    currentThreshold = PRESET_LIBRARY
                }
                R.id.rbKitchen -> {
                    currentThreshold = PRESET_KITCHEN
                }
                R.id.rbRestaurant -> {
                    currentThreshold = PRESET_RESTAURANT
                }
            }
            
            if (switchMonitor.isChecked) {
                startNoiseService()
            }
        }

        rgPresets.check(R.id.rbLibrary)

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

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(NoiseMonitorService.ACTION_NOISE_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noiseReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(noiseReceiver)
    }

    private fun updateStatus(isMonitoring: Boolean) {
        if (isMonitoring) {
            ivNoiseEmoji.setImageResource(R.drawable.monika)
        } else {
            // Reset background to transparent when stopped
            innerNoiseLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            
            ivNoiseEmoji.clearColorFilter()
            tvDebugDb.text = "Debug: -- dB"
        }
        // Always ensure the image is visible
        ivNoiseEmoji.visibility = View.VISIBLE
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
            putExtra("THRESHOLD", currentThreshold)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStatus(true)
    }

    private fun stopNoiseService() {
        val intent = Intent(this, NoiseMonitorService::class.java)
        stopService(intent)
        updateStatus(false)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                switchMonitor.isChecked = true
                startNoiseService()
            } else {
                Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
            }
        }
    }
}
