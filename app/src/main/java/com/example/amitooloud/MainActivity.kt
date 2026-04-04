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

class MainActivity : AppCompatActivity() {

    private val recordAudioPermission = Manifest.permission.RECORD_AUDIO
    private val postNotificationsPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }

    private var currentThreshold = 70.0
    private lateinit var tvCurrentNoise: TextView
    private lateinit var tvThreshold: TextView
    private lateinit var switchMonitor: SwitchCompat
    private lateinit var ivNoiseEmoji: ImageView

    private val PRESET_LIBRARY = 40.0
    private val PRESET_KITCHEN = 80.0
    private val PRESET_RESTAURANT = 65.0

    private val noiseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val db = intent?.getDoubleExtra(NoiseMonitorService.EXTRA_DB, 0.0) ?: 0.0
            tvCurrentNoise.text = getString(R.string.current_noise_label, db.toInt())
            
            val emojiRes = when {
                db < currentThreshold * 0.5 -> R.drawable.ic_noise_low
                db < currentThreshold -> R.drawable.ic_noise_mid
                else -> R.drawable.ic_noise_high
            }
            ivNoiseEmoji.setImageResource(emojiRes)
            
            if (db > currentThreshold) {
                tvCurrentNoise.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_danger))
            } else {
                tvCurrentNoise.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_safe))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCurrentNoise = findViewById(R.id.tvCurrentNoise)
        tvThreshold = findViewById(R.id.tvThreshold)
        ivNoiseEmoji = findViewById(R.id.ivNoiseEmoji)
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
            tvCurrentNoise.setTextColor(ContextCompat.getColor(this, R.color.status_safe))
            tvCurrentNoise.text = getString(R.string.current_noise_label, 0)
            tvThreshold.text = getString(R.string.threshold_label, currentThreshold.toInt())
            tvThreshold.visibility = View.VISIBLE
            ivNoiseEmoji.visibility = View.VISIBLE
            ivNoiseEmoji.setImageResource(R.drawable.ic_noise_low)
        } else {
            tvCurrentNoise.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            tvCurrentNoise.text = getString(R.string.current_noise_disabled)
            tvThreshold.visibility = View.GONE
            ivNoiseEmoji.visibility = View.INVISIBLE
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
