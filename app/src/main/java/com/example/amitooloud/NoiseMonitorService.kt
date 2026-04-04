package com.example.amitooloud

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.math.log10

class NoiseMonitorService : Service() {

    companion object {
        const val ACTION_NOISE_UPDATE = "com.example.amitooloud.NOISE_UPDATE"
        const val EXTRA_DB = "extra_db"
    }

    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private var thresholdDb = 80.0 // Default threshold in decibels
    private val channelId = "NoiseMonitorChannel"
    private val alertChannelId = "NoiseAlertChannel"
    private val notificationId = 1
    private val alertNotificationId = 2

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        thresholdDb = intent?.getDoubleExtra("THRESHOLD", 80.0) ?: 80.0
        startForegroundService()
        startMonitoring()
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun startMonitoring() {
        if (isRunning) return
        isRunning = true

        val bufferSize = AudioRecord.getMinBufferSize(
            44100,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            // VOICE_COMMUNICATION triggers hardware noise suppression optimized for speech
            val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION

            audioRecord = AudioRecord(
                audioSource,
                44100,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            audioRecord?.startRecording()

            Thread {
                val bufferSizeInShorts = bufferSize
                val buffer = ShortArray(bufferSizeInShorts)
                
                var sumSq = 0.0
                var totalSamples = 0
                val sampleRate = 44100
                val windowSize = sampleRate 

                while (isRunning) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        for (i in 0 until readSize) {
                            val sample = buffer[i].toDouble()
                            sumSq += sample * sample
                        }
                        totalSamples += readSize

                        // When we have enough samples for the 1s window
                        if (totalSamples >= windowSize) {
                            val rms = Math.sqrt(sumSq / totalSamples)
                            
                            // 20 * log10(rms / 32768.0) is dB relative to full scale (dBFS)
                            var db = if (rms > 0.1) 20 * log10(rms / 32768.0) + 90 else 0.0

                            // If dB is very low (noise floor), let's map it closer to 0
                            if (db < 30) {
                                db = (db - 27.0).coerceAtLeast(0.0) * (30.0 / 3.0)
                            }

                            // Broadcast the noise level
                            val intent = Intent(ACTION_NOISE_UPDATE)
                            intent.putExtra(EXTRA_DB, db)
                            sendBroadcast(intent)

                            if (db > thresholdDb) {
                                sendAlertNotification(db)
                            }

                            // Reset for the next window
                            sumSq = 0.0
                            totalSamples = 0
                        }
                    }
                }
            }.start()
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun sendAlertNotification(db: Double) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alertNotification = NotificationCompat.Builder(this, alertChannelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(alertNotificationId, alertNotification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                getString(R.string.channel_name_background),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.channel_desc_background)
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                alertChannelId,
                getString(R.string.channel_name_alerts),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_desc_alerts)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    override fun onDestroy() {
        isRunning = false
        audioRecord?.stop()
        audioRecord?.release()
        super.onDestroy()
    }
}
