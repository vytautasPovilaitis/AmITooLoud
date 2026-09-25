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
        const val EXTRA_THRESHOLD = "THRESHOLD"

        // 48 kHz is the S25's native capture rate, so the HAL doesn't have to resample
        private const val SAMPLE_RATE = 48_000
        private const val WINDOW_SAMPLES = SAMPLE_RATE / 2 // 0.5 s per dB reading
        private const val READ_SAMPLES = SAMPLE_RATE / 10 // 100 ms per read() → fewer CPU wakeups
        private const val ALERT_WINDOWS = 6 // 6 × 0.5 s = 3 s rolling average for alerts

        // A-weighted, so this reads as dBA. Hard gate: rms < 50 is the electronic noise floor → 0.
        // +85 offset calibrated for S25 (was +90, shifted down 5 dB) before A-weighting;
        // re-check against a dBA reference meter
        private fun rmsToDb(rms: Double) = if (rms > 50) 20 * log10(rms / 32768.0) + 85 else 0.0

        // Lets the activity restore the switch state after being recreated
        @Volatile
        var isMonitoring = false
            private set
    }

    private var audioRecord: AudioRecord? = null
    private var monitorThread: Thread? = null
    @Volatile
    private var isRunning = false
    @Volatile
    private var thresholdDb = 80.0 // Default threshold in decibels
    private var lastAlertTime = 0L
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
        intent?.let { thresholdDb = it.getDoubleExtra(EXTRA_THRESHOLD, thresholdDb) }
        if (isRunning) {
            // Just a threshold change: already foreground, so skip re-posting the notification
            return START_NOT_STICKY
        }
        if (!startForegroundService()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startMonitoring()
        // Not sticky: a microphone FGS can't be restarted from the background on Android 14+
        return START_NOT_STICKY
    }

    private fun startForegroundService(): Boolean {
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(notificationId, notification)
            }
            true
        } catch (e: Exception) {
            // Missing permission or not allowed to start a microphone FGS right now
            false
        }
    }

    private fun startMonitoring() {
        if (isRunning) return

        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes <= 0) {
            stopSelf()
            return
        }
        // Room for two reads so the recorder never overflows between them (16-bit = 2 bytes)
        val bufferBytes = maxOf(minBufferBytes, READ_SAMPLES * 2 * 2)

        try {
            // VOICE_RECOGNITION: tuned for speech but without the aggressive noise
            // suppression / AGC that VOICE_COMMUNICATION applies
            val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION

            val record = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                stopSelf()
                return
            }
            audioRecord = record
            record.startRecording()
            isRunning = true
            isMonitoring = true

            monitorThread = Thread {
                val buffer = ShortArray(READ_SAMPLES)
                val aWeighting = AWeightingFilter(SAMPLE_RATE)
                // Drop the first read while the filter settles on the mic's DC offset / start-up pop
                var warmupSamples = READ_SAMPLES
                var sumSq = 0.0
                // Mean square of the last ALERT_WINDOWS readings (ring buffer)
                val recentMeanSq = DoubleArray(ALERT_WINDOWS)
                var recentIndex = 0
                var recentCount = 0
                var totalSamples = 0

                while (isRunning) {
                    try {
                        val readSize = record.read(buffer, 0, buffer.size)
                        if (readSize < 0) {
                            // AudioRecord entered error state (e.g. audio focus lost after
                            // notification sound). Sleep briefly to avoid a spin loop.
                            Thread.sleep(50)
                            continue
                        }
                        if (readSize > 0) {
                            if (warmupSamples > 0) {
                                for (i in 0 until readSize) aWeighting.process(buffer[i].toDouble())
                                warmupSamples -= readSize
                                continue
                            }
                            for (i in 0 until readSize) {
                                val sample = aWeighting.process(buffer[i].toDouble())
                                sumSq += sample * sample
                            }
                            totalSamples += readSize

                            // Accumulate 0.5s of audio so brief peaks are averaged out
                            if (totalSamples >= WINDOW_SAMPLES) {
                                val meanSq = sumSq / totalSamples
                                val db = rmsToDb(Math.sqrt(meanSq))

                                // Broadcast the noise level
                                val intent = Intent(ACTION_NOISE_UPDATE)
                                intent.putExtra(EXTRA_DB, db)
                                intent.setPackage(packageName)
                                sendBroadcast(intent)

                                // Alert when the average over the last 3 s is above the threshold.
                                // Energy average (Leq, like a sound level meter), so a short dip
                                // doesn't reset it the way a per-reading check would.
                                recentMeanSq[recentIndex] = meanSq
                                recentIndex = (recentIndex + 1) % ALERT_WINDOWS
                                if (recentCount < ALERT_WINDOWS) recentCount++
                                val now = System.currentTimeMillis()
                                if (recentCount == ALERT_WINDOWS && now - lastAlertTime > 10_000) {
                                    val averageDb = rmsToDb(Math.sqrt(recentMeanSq.average()))
                                    if (averageDb > thresholdDb) {
                                        lastAlertTime = now
                                        sendAlertNotification(averageDb)
                                    }
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
            }.also { it.start() }
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
        isMonitoring = false
        // Let the reader thread exit its read() before the recorder is released
        monitorThread?.join(500)
        monitorThread = null
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        audioRecord = null
        super.onDestroy()
    }
}
