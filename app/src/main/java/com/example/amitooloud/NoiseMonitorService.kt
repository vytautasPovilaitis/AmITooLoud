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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
    private var lastAlertTime = 0L
    private var consecutiveOverThreshold = 0
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
                val buffer = ShortArray(bufferSize)
                var sumSq = 0.0
                var totalSamples = 0

                while (isRunning) {
                    try {
                        val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (readSize < 0) {
                            // AudioRecord entered error state (e.g. audio focus lost after
                            // notification sound). Sleep briefly to avoid a spin loop.
                            Thread.sleep(50)
                            continue
                        }
                        if (readSize > 0) {
                            for (i in 0 until readSize) {
                                val sample = buffer[i].toDouble()
                                sumSq += sample * sample
                            }
                            totalSamples += readSize

                            // Accumulate 0.5s of audio so brief peaks are averaged out
                            if (totalSamples >= 22050) {
                                val rms = Math.sqrt(sumSq / totalSamples)

                                // Hard gate: rms < 50 is electronic noise floor → 0
                                // +85 offset calibrated for S25 (was +90, shifted down 5 dB)
                                var db = if (rms > 50) 20 * log10(rms / 32768.0) + 85 else 0.0


                                // Broadcast the noise level
                                val intent = Intent(ACTION_NOISE_UPDATE)
                                intent.putExtra(EXTRA_DB, db)
                                intent.setPackage(packageName)
                                sendBroadcast(intent)

                                // Alert only after 3 continuous seconds above threshold
                                // (6 × 0.5s windows). Resets as soon as it drops below.
                                if (db > thresholdDb) {
                                    consecutiveOverThreshold++
                                } else {
                                    consecutiveOverThreshold = 0
                                }
                                val now = System.currentTimeMillis()
                                if (consecutiveOverThreshold >= 6 && now - lastAlertTime > 10_000) {
                                    lastAlertTime = now
                                    sendAlertNotification(db)
                                }

                                sumSq = 0.0
                                totalSamples = 0
                            }
                        }
                    } catch (e: Exception) {
                        // Swallow transient errors (e.g. notification rate limit on Android 16)
                        // so the monitoring loop keeps running
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
            .setAutoCancel(true)
            .build()

        notificationManager.notify(alertNotificationId, alertNotification)

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        }
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
