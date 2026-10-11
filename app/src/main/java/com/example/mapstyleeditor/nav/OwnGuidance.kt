package com.example.mapstyleeditor.nav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.graphics.createBitmap
import java.util.Locale

/*
 * MinMap guiding by itself (the aosp build, for Android without Google Maps): the turn card's arrow
 * and the spoken instructions. Everything here works from the one Directions response a route
 * already comes with, so guidance costs no Mapbox requests of its own.
 */

/** The turn card's arrow for a Mapbox maneuver: white on transparent, like Google's (tinted when shown). */
fun maneuverArrow(type: String, modifier: String?): Bitmap {
    val size = 144
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 16f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }

    if (type == "arrive") {
        // A pin: where you're going.
        canvas.drawCircle(72f, 58f, 34f, fill)
        canvas.drawPath(Path().apply { moveTo(46f, 80f); lineTo(72f, 132f); lineTo(98f, 80f); close() }, fill)
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        canvas.drawCircle(72f, 58f, 13f, hole)
        return bitmap
    }
    val m = modifier.orEmpty()
    val left = "left" in m
    if (left) canvas.scale(-1f, 1f, size / 2f, size / 2f) // Drawn turning right; mirrored for left.

    if (m == "uturn") {
        // Up, round the top, and back down.
        canvas.drawPath(Path().apply { moveTo(96f, 132f); lineTo(96f, 64f); quadTo(96f, 30f, 68f, 30f); quadTo(40f, 30f, 40f, 64f); lineTo(40f, 96f) }, stroke)
        canvas.drawPath(Path().apply { moveTo(40f, 124f); lineTo(16f, 90f); lineTo(64f, 90f); close() }, fill)
        return bitmap
    }
    // Straight up from the bottom, then off at the maneuver's angle (0 is straight on).
    val angle = when {
        "sharp" in m -> 135.0
        "slight" in m -> 40.0
        "right" in m || left -> 90.0
        else -> 0.0
    }
    val rad = Math.toRadians(angle)
    val dx = Math.sin(rad).toFloat()
    val dy = -Math.cos(rad).toFloat()
    // The stem sits left of centre when the arrow turns, so the turned part has room.
    val cx = if (angle == 0.0) 72f else 52f
    val cy = 76f
    val tipX = cx + dx * 56f
    val tipY = cy + dy * 56f
    canvas.drawPath(Path().apply { moveTo(cx, 132f); lineTo(cx, cy); lineTo(tipX - dx * 14f, tipY - dy * 14f) }, stroke)
    // Arrowhead pointing along (dx, dy).
    val px = -dy
    val py = dx
    canvas.drawPath(
        Path().apply {
            moveTo(tipX + dx * 8f, tipY + dy * 8f)
            lineTo(tipX - dx * 22f + px * 24f, tipY - dy * 22f + py * 24f)
            lineTo(tipX - dx * 22f - px * 24f, tipY - dy * 22f - py * 24f)
            close()
        },
        fill,
    )
    return bitmap
}

/**
 * Speaks turn instructions with whatever text-to-speech engine the device has (AOSP's own, or any
 * installed one), ducking music while it talks. On a device with no voice installed it stays
 * silent and the turn card alone guides.
 */
class GuidanceVoice(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    @Volatile private var ready = false

    /** Null if the device has no text-to-speech engine at all. */
    var available: Boolean? = null
        private set

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        available = ready
        if (ready) {
            tts.setAudioAttributes(attributes)
            tts.language = Locale.getDefault()
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                audio?.abandonAudioFocusRequest(focus)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                audio?.abandonAudioFocusRequest(focus)
            }
        })
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        audio?.requestAudioFocus(focus)
        // Replaces anything still being said: the newest instruction is the one that matters.
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "minmap-guidance")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        audio?.abandonAudioFocusRequest(focus)
    }
}
