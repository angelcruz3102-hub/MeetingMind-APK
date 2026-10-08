package com.meetingmind.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.meetingmind.app.action.START"
        const val ACTION_STOP  = "com.meetingmind.app.action.STOP"

        private const val CHANNEL_ID = "meetingmind_recording_channel"
        private const val NOTIF_ID   = 4242
        private const val TAG        = "RecordingService"
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null                  // Solo Android 7-9
    private var outputUri: Uri? = null                    // Solo Android 10+
    private var outputPfd: ParcelFileDescriptor? = null   // Solo Android 10+
    private var outputDisplayName: String = ""
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
    // Crear destino en Music/MeetingMind/
    // ─────────────────────────────────────────────────────────────
    private fun createOutputTarget() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        outputDisplayName = "MeetingMind_${timestamp}.m4a"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // ── Android 10+ → MediaStore (sin permisos de almacenamiento) ──
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, outputDisplayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MUSIC + "/MeetingMind"
                )
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY
            )
            val uri = contentResolver.insert(collection, values)
                ?: throw IOException("No se pudo crear el archivo en MediaStore")
            outputUri = uri
            outputPfd = contentResolver.openFileDescriptor(uri, "w")
                ?: throw IOException("No se pudo abrir el descriptor del archivo")
            Log.i(TAG, "Destino MediaStore: $uri → Music/MeetingMind/$outputDisplayName")
        } else {
            // ── Android 7-9 → File API legacy (requiere WRITE_EXTERNAL_STORAGE) ──
            @Suppress("DEPRECATION")
            val musicDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC
            )
            val appDir = File(musicDir, "MeetingMind")
            if (!appDir.exists() && !appDir.mkdirs()) {
                throw IOException("No se pudo crear la carpeta Music/MeetingMind")
            }
            val file = File(appDir, outputDisplayName)
            outputFile = file
            Log.i(TAG, "Destino File legacy: ${file.absolutePath}")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // INICIO DE GRABACIÓN
    // ─────────────────────────────────────────────────────────────
    private fun startRecording() {
        if (isRecording) return

        try {
            createChannel()
            promoteToForeground()
            createOutputTarget()

            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder())
                .apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(16_000)
                    setAudioEncodingBitRate(64_000)
                    setAudioChannels(1)

                    if (outputUri != null && outputPfd != null) {
                        setOutputFile(outputPfd!!.fileDescriptor)
                    } else {
                        setOutputFile(outputFile!!.absolutePath)
                    }

                    prepare()
                    start()
                }
            isRecording = true
            Log.i(TAG, "Grabación iniciada → $outputDisplayName")
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar MediaRecorder", e)
            cleanupRecorder()
            stopForegroundCompat()
            stopSelf()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // DETENER → Base64 → callback a la Activity
    // ⚠️ EL ARCHIVO **NO** SE BORRA: queda en Music/MeetingMind/
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

        // Finalizar la transacción en MediaStore → archivo visible para el usuario
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                contentResolver.update(outputUri!!, values, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo finalizar IS_PENDING", e)
            }
        }

        // Procesar Base64 en hilo de fondo
        Thread {
            try {
                val bytes: ByteArray? = when {
                    outputUri != null -> {
                        contentResolver.openInputStream(outputUri!!)?.use { it.readBytes() }
                    }
                    outputFile != null && outputFile!!.exists() -> {
                        FileInputStream(outputFile!!).use { it.readBytes() }
                    }
                    else -> null
                }

                if (bytes != null && bytes.isNotEmpty()) {
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    RecordingBridge.onAudioReady?.invoke(base64)
                } else {
                    Log.w(TAG, "No se pudo leer el archivo generado")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error codificando Base64", e)
            } finally {
                // 🔥 CAMBIO CLAVE: NO BORRAMOS EL ARCHIVO
                // El archivo permanece en Music/MeetingMind/ para que el usuario
                // pueda reintentar la transcripción con "Subir Audios" si falla la red.
                // file.delete()  ← ELIMINADO INTENCIONALMENTE
                RecordingBridge.onRecordingStopped?.invoke()
            }
        }.start()

        stopForegroundCompat()
        stopSelf()
    }

    private fun cleanupRecorder() {
        try { recorder?.reset(); recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { outputPfd?.close() } catch (_: Exception) {}
        outputPfd = null
        isRecording = false
    }

    // ─────────────────────────────────────────────────────────────
    // FOREGROUND + NOTIFICACIÓN (sin cambios)
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
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

        val contentPi = PendingIntent.getActivity(this, 0, openApp, piFlags)

        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("🎙️ Meeting Mind grabando")
            .setContentText("Grabación activa en segundo plano")
            .setOngoing(true)
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
