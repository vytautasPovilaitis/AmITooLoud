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
    private var smoothedRatio = 0.0f
    private lateinit var switchMonitor: SwitchCompat
    private lateinit var ivNoiseEmoji: ImageView
    private lateinit var noiseCard: com.google.android.material.card.MaterialCardView
    private lateinit var controlCard: com.google.android.material.card.MaterialCardView
    private lateinit var tvDebugDb: TextView
    private lateinit var innerNoiseLayout: android.widget.LinearLayout

    private val PRESET_LIBRARY = 45.0
    private val PRESET_KITCHEN = 85.0
    private val PRESET_RESTAURANT = 65.0

    private val noiseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val db = intent?.getDoubleExtra(NoiseMonitorService.EXTRA_DB, 0.0) ?: 0.0

            tvDebugDb.text = String.format(Locale.US, "Debug: %.1f dB", db)

            val targetRatio = (db / currentThreshold).coerceIn(0.0, 1.0).toFloat()
            smoothedRatio = smoothedRatio * 0.65f + targetRatio * 0.35f

            val atThreshold = db >= currentThreshold
            ivNoiseEmoji.setImageResource(when {
                atThreshold -> R.drawable.monika_pikta
                smoothedRatio >= 0.75f -> R.drawable.monika_mid
                else -> R.drawable.monika_laiminga
            })
            innerNoiseLayout.setBackgroundColor(
                if (atThreshold) android.graphics.Color.parseColor("#B71C1C")
                else colorForRatio(smoothedRatio)
            )
        }
    }

    // Stays green until warning (75%), then yellow → orange gradients → deep red
    private val colorStops = listOf(
        0.00f to android.graphics.Color.parseColor("#388E3C"),
        0.74f to android.graphics.Color.parseColor("#388E3C"),
        0.75f to android.graphics.Color.parseColor("#FDD835"),
        0.83f to android.graphics.Color.parseColor("#FFB300"),
        0.88f to android.graphics.Color.parseColor("#FF9800"),
        0.93f to android.graphics.Color.parseColor("#F44336"),
        1.00f to android.graphics.Color.parseColor("#B71C1C")
    )

    private fun colorForRatio(ratio: Float): Int {
        if (ratio <= colorStops.first().first) return colorStops.first().second
        if (ratio >= colorStops.last().first) return colorStops.last().second
        val upper = colorStops.indexOfFirst { it.first >= ratio }
        val (r1, c1) = colorStops[upper - 1]
        val (r2, c2) = colorStops[upper]
        return interpolateColor(c1, c2, (ratio - r1) / (r2 - r1))
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
        controlCard = findViewById(R.id.controlCard)
        tvDebugDb = findViewById(R.id.tvDebugDb)
        innerNoiseLayout = findViewById(R.id.innerNoiseLayout)
        val rgPresets = findViewById<RadioGroup>(R.id.rgPresets)
        switchMonitor = findViewById(R.id.switchMonitor)

        // Edge-to-edge: push control card above the navigation bar on Android 15+
        ViewCompat.setOnApplyWindowInsetsListener(controlCard) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = navBar.bottom)
            insets
        }

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
        if (isMonitoring) {
            ivNoiseEmoji.setImageResource(R.drawable.monika_laiminga)
        } else {
            // Reset background to transparent when stopped
            innerNoiseLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            ivNoiseEmoji.setImageResource(R.drawable.monika_laiminga)
            
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
