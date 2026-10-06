package com.meetingmind.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileInputStream

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.meetingmind.app.action.START"
        const val ACTION_STOP  = "com.meetingmind.app.action.STOP"

        private const val CHANNEL_ID = "meetingmind_recording_channel"
        private const val NOTIF_ID   = 4242
        private const val TAG        = "RecordingService"
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP  -> stopRecordingAndSend()
        }
        return START_NOT_STICKY
    }

    // ─────────────────────────────────────────────────────────────
    // INICIO DE GRABACIÓN
    // ─────────────────────────────────────────────────────────────
    private fun startRecording() {
        if (isRecording) return

        try {
            createChannel()
            promoteToForeground()

            outputFile = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")

            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder())
                .apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)   // contenedor .m4a
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(16_000)
                    setAudioEncodingBitRate(64_000)
                    setAudioChannels(1)
                    setOutputFile(outputFile!!.absolutePath)
                    prepare()
                    start()
                }
            isRecording = true
            Log.i(TAG, "Grabación iniciada → ${outputFile!!.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar MediaRecorder", e)
            cleanupRecorder()
            stopForegroundCompat()
            stopSelf()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // DETENER → Base64 → callback a la Activity
    // ─────────────────────────────────────────────────────────────
    private fun stopRecordingAndSend() {
        if (!isRecording) {
            stopForegroundCompat(); stopSelf(); return
        }
        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stop() falló (archivo posiblemente corrupto)", e)
        } finally {
            cleanupRecorder()
        }

        val file = outputFile
        if (file != null && file.exists() && file.length() > 0) {
            // Procesamos en hilo de fondo (Base64 es pesado)
            Thread {
                try {
                    val bytes = FileInputStream(file).use { it.readBytes() }
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    RecordingBridge.onAudioReady?.invoke(base64)
                } catch (e: Exception) {
                    Log.e(TAG, "Error codificando Base64", e)
                } finally {
                    file.delete()
                    RecordingBridge.onRecordingStopped?.invoke()
                }
            }.start()
        } else {
            RecordingBridge.onRecordingStopped?.invoke()
        }

        stopForegroundCompat()
        stopSelf()
    }

    private fun cleanupRecorder() {
        try { recorder?.reset(); recorder?.release() } catch (_: Exception) {}
        recorder = null
        isRecording = false
    }

    // ─────────────────────────────────────────────────────────────
    // FOREGROUND + NOTIFICACIÓN
    // ─────────────────────────────────────────────────────────────
    private fun promoteToForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    private fun buildNotification(): Notification {
        // Tap en la notificación → abre MainActivity
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

        val contentPi = PendingIntent.getActivity(this, 0, openApp, piFlags)

        // Botón "Detener" en la notificación
        val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("🎙️ Meeting Mind grabando")
            .setContentText("Grabación activa en segundo plano")
            .setOngoing(true)                    // ← INMATABLE
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPi)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Grabación de audio",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Notificación persistente durante la grabación"
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        cleanupRecorder()
        super.onDestroy()
    }
}
