package com.meetingmind.app

/**
 * Puente simple entre RecordingService y MainActivity.
 * Como la app es de una sola Activity, evitamos Binder complejo.
 */
object RecordingBridge {
    var onAudioReady: ((String) -> Unit)? = null
    var onRecordingStopped: (() -> Unit)? = null
}
