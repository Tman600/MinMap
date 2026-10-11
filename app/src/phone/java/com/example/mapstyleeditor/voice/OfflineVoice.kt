package com.example.mapstyleeditor.voice

import android.content.Context
import java.io.File

/**
 * The phone build has no offline voice: phones come with a speech recognizer, and the offline
 * engine (aosp build only) would add several megabytes. Same shape as the aosp version.
 */
object OfflineVoice {
    const val SUPPORTED = false
    const val DOWNLOAD_MB = 0

    fun installed(context: Context) = false

    suspend fun install(context: Context, onProgress: (Float) -> Unit): String? = "Offline voice isn't part of this build."

    fun listen(
        context: Context,
        onPartial: (String) -> Unit,
        onResult: (String?) -> Unit,
        onError: (String) -> Unit,
    ): Listening {
        onError("Offline voice isn't part of this build.")
        return Listening {}
    }

    fun recognizeFile(context: Context, wav: File): String? = null
}
