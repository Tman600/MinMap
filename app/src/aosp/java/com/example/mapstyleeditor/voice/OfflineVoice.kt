package com.example.mapstyleeditor.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Speech recognition on the device itself (Vosk), for when there's no recognizer and no phone:
 * works without Google and without signal. The English model isn't in the APK; it's downloaded
 * once, when asked for, and checked against its known checksum before use.
 */
object OfflineVoice {
    const val SUPPORTED = true
    const val DOWNLOAD_MB = 40

    @Volatile private var model: Model? = null

    fun installed(context: Context) = File(modelDir(context), READY_MARKER).exists()

    /** Downloads and unpacks the model. Null when it worked; otherwise what went wrong. */
    suspend fun install(context: Context, onProgress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        val zip = File(context.cacheDir, "vosk-model.zip")
        try {
            download(zip, onProgress)
            if (sha256(zip) != MODEL_SHA256) return@withContext "The voice download was damaged. Try again."
            unpack(zip, File(context.filesDir, "vosk"))
            null
        } catch (e: Exception) {
            Log.w(TAG, "Offline voice install failed", e)
            "The voice download didn't finish. Check the connection and try again."
        } finally {
            zip.delete()
        }
    }

    /**
     * Listens for one request with the offline model. [onResult] gets what was heard, or null after
     * a few seconds of nothing. Callbacks arrive on the main thread.
     */
    fun listen(
        context: Context,
        onPartial: (String) -> Unit,
        onResult: (String?) -> Unit,
        onError: (String) -> Unit,
    ): Listening {
        var service: SpeechService? = null
        var stopped = false
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        // Loading the model takes a moment (and memory), so it's done off the main thread, once.
        thread(name = "minmap-offline-voice") {
            val recognizer = runCatching { Recognizer(loadModel(context), SAMPLE_RATE) }.getOrElse {
                Log.w(TAG, "Offline voice model didn't load", it)
                main.post { onError("Offline voice couldn't start.") }
                return@thread
            }
            main.post {
                if (stopped) return@post recognizer.close()
                var done = false
                fun finish(text: String?) {
                    if (done) return
                    done = true
                    service?.shutdown()
                    recognizer.close()
                    onResult(text)
                }
                service = runCatching { SpeechService(recognizer, SAMPLE_RATE) }.getOrElse {
                    recognizer.close()
                    onError("The microphone isn't available.")
                    return@post
                }
                service?.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        text(hypothesis, "partial")?.let(onPartial)
                    }
                    // The end of a phrase: that's the request.
                    override fun onResult(hypothesis: String?) {
                        text(hypothesis, "text")?.let(::finish)
                    }
                    override fun onFinalResult(hypothesis: String?) = finish(text(hypothesis, "text"))
                    override fun onError(exception: Exception?) {
                        if (!done) {
                            done = true
                            service?.shutdown()
                            recognizer.close()
                            onError("Offline voice stopped working.")
                        }
                    }
                    override fun onTimeout() = finish(null)
                }, LISTEN_MS)
            }
        }
        return Listening {
            stopped = true
            main.post { service?.stop() }
        }
    }

    /** Debug builds only: what the offline model hears in a 16 kHz mono 16-bit WAV file. */
    fun recognizeFile(context: Context, wav: File): String? = runCatching {
        Recognizer(loadModel(context), SAMPLE_RATE).use { recognizer ->
            val bytes = wav.readBytes()
            // The samples follow the "data" chunk header; the chunks before it vary in size.
            var at = 12
            while (at + 8 <= bytes.size && String(bytes, at, 4, Charsets.US_ASCII) != "data") {
                val size = (bytes[at + 4].toInt() and 0xff) or ((bytes[at + 5].toInt() and 0xff) shl 8) or
                    ((bytes[at + 6].toInt() and 0xff) shl 16) or ((bytes[at + 7].toInt() and 0xff) shl 24)
                at += 8 + size + (size and 1)
            }
            val audio = bytes.copyOfRange((at + 8).coerceAtMost(bytes.size), bytes.size)
            audio.toList().chunked(4096).forEach { recognizer.acceptWaveForm(it.toByteArray(), it.size) }
            text(recognizer.finalResult, "text")
        }
    }.onFailure { Log.w(TAG, "File recognition failed", it) }.getOrNull()

    @Synchronized
    private fun loadModel(context: Context): Model = model ?: Model(modelDir(context).path).also { model = it }

    private fun text(json: String?, key: String) =
        json?.let { runCatching { JSONObject(it).optString(key) }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }

    private fun modelDir(context: Context) = File(context.filesDir, "vosk/$MODEL_NAME")

    private fun download(target: File, onProgress: (Float) -> Unit) {
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: MODEL_BYTES
            var done = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Unpacks into [root] (replacing any earlier copy), then marks the model ready. */
    private fun unpack(zip: File, root: File) {
        root.deleteRecursively()
        root.mkdirs()
        val base = root.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val out = File(root, entry.name)
                // Never write outside the folder, whatever the archive says.
                if (!out.canonicalPath.startsWith(base)) error("Bad entry ${entry.name}")
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }
        File(root, "$MODEL_NAME/$READY_MARKER").writeText("ok")
    }

    private const val TAG = "MinMapOfflineVoice"
    private const val SAMPLE_RATE = 16_000f
    private const val LISTEN_MS = 8_000
    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    private const val MODEL_BYTES = 41_205_931L
    private const val MODEL_SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    private const val READY_MARKER = ".minmap-ready"
}
