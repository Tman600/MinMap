package com.example.mapstyleeditor.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Something listening for one request; [stop] gives up on it. */
fun interface Listening {
    fun stop()
}

/**
 * The device's own speech recognizer (Google's on most phones; whatever a head unit maker
 * installed, if anything). Hears one request, then reports it.
 */
object SystemSpeech {
    fun available(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Listens once. [onResult] gets what was heard, or null if nothing was (silence, no match);
     * [onFailed] means the recognizer itself didn't work (no network for it, busy, missing), so
     * another way of listening should be tried. Call on the main thread.
     */
    fun listen(
        context: Context,
        onPartial: (String) -> Unit,
        onResult: (String?) -> Unit,
        onFailed: () -> Unit,
    ): Listening {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        var done = false
        fun finish(block: () -> Unit) {
            if (done) return
            done = true
            block()
            recognizer.destroy()
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let(onPartial)
            }

            override fun onResults(results: Bundle?) = finish { onResult(first(results)) }

            override fun onError(error: Int) = finish {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onResult(null)
                    else -> onFailed()
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
        )
        return Listening { finish { recognizer.cancel() } }
    }

    private fun first(bundle: Bundle?) =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
