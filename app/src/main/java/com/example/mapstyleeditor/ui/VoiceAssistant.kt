package com.example.mapstyleeditor.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.mapstyleeditor.BuildConfig
import com.example.mapstyleeditor.voice.Listening
import com.example.mapstyleeditor.voice.OfflineVoice
import com.example.mapstyleeditor.voice.PhoneMic
import com.example.mapstyleeditor.voice.SystemSpeech
import com.example.mapstyleeditor.voice.VoiceCommand
import com.example.mapstyleeditor.voice.VoiceOutcome
import com.example.mapstyleeditor.voice.parseVoiceCommand
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Presses of a hardware voice key (a steering-wheel button, a remote's search key), or MinMap
 * being opened as the voice command app. The voice panel starts listening on each one.
 */
object VoiceKeys {
    val presses = MutableStateFlow(0)
    fun press() {
        presses.value++
    }
}

/** Debug builds only (adb `--ez voice_no_system true`): act as if there were no speech recognizer. */
object VoiceDebug {
    @Volatile var noSystemRecognizer = false
}

/** What the voice panel shows. */
sealed interface VoiceStage {
    data object Closed : VoiceStage
    data class Listening(val heard: String) : VoiceStage
    data class Finding(val query: String?) : VoiceStage

    /**
     * No working speech recognizer: offer the phone ([phoneUrl], null without a network a phone can
     * reach) and, on aosp, the offline model (listening at once if it's installed).
     */
    data class NoRecognizer(
        val phoneUrl: String?,
        val offlineInstalled: Boolean,
        val offlineListening: Boolean = false,
        val heard: String = "",
        val downloading: Float? = null,
        val error: String? = null,
    ) : VoiceStage

    data class Done(val message: String, val ok: Boolean) : VoiceStage
}

/**
 * "Navigate to Starbucks" by voice. Tries, in order: the device's own speech recognizer; then
 * (aosp, where there may be none) your phone as the mic, through a QR-code page; then offline
 * recognition on the device. Whatever is heard goes to [handle].
 */
class VoiceAssistantState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val handle: suspend (VoiceCommand) -> VoiceOutcome,
    private val speak: (String) -> Unit,
) {
    var stage by mutableStateOf<VoiceStage>(VoiceStage.Closed)
        private set

    private val systemAvailable = SystemSpeech.available(context) && !VoiceDebug.noSystemRecognizer

    /** Whether to offer the mic at all: a phone build without a recognizer has nothing to listen with. */
    val usable = systemAvailable || BuildConfig.AOSP

    /** Keeps answering a phone that has the "Where to?" page open, while MinMap is on screen. */
    val phoneMic: PhoneMic? = if (BuildConfig.AOSP) PhoneMic(context, ::fromPhone) else null

    private var listening: Listening? = null
    /** Bumped on every start and cancel, so a late answer from an abandoned attempt is ignored. */
    private var attempt = 0

    fun start() {
        stopListening()
        val id = ++attempt
        if (systemAvailable && hasMic()) {
            stage = VoiceStage.Listening("")
            listening = SystemSpeech.listen(
                context,
                onPartial = { if (id == attempt) stage = VoiceStage.Listening(it) },
                onResult = { if (id == attempt) heard(it) },
                onFailed = { if (id == attempt) fallBack(id) },
            )
        } else {
            fallBack(id)
        }
    }

    fun cancel() {
        stopListening()
        attempt++
        stage = VoiceStage.Closed
    }

    /** Fetches the offline model (aosp), then listens with it. */
    fun downloadOffline() {
        val current = stage as? VoiceStage.NoRecognizer ?: return
        if (current.downloading != null) return
        val id = attempt
        stage = current.copy(downloading = 0f, error = null)
        scope.launch {
            val problem = OfflineVoice.install(context) { progress ->
                scope.launch(Dispatchers.Main) {
                    (stage as? VoiceStage.NoRecognizer)?.let { if (it.downloading != null) stage = it.copy(downloading = progress) }
                }
            }
            val now = stage as? VoiceStage.NoRecognizer ?: return@launch
            if (problem != null) {
                stage = now.copy(downloading = null, error = problem)
            } else {
                stage = now.copy(downloading = null, offlineInstalled = true)
                if (id == attempt) listenOffline(id)
            }
        }
    }

    private fun fallBack(id: Int) {
        listening = null
        if (!BuildConfig.AOSP) {
            val why = if (hasMic()) "Voice recognition isn't working right now. Try again in a moment." else "MinMap needs the microphone for voice."
            show(VoiceStage.Done(why, ok = false))
            return
        }
        val installed = OfflineVoice.installed(context)
        stage = VoiceStage.NoRecognizer(phoneUrl = phoneMic?.url(), offlineInstalled = installed)
        if (installed && hasMic()) listenOffline(id)
    }

    private fun listenOffline(id: Int) {
        (stage as? VoiceStage.NoRecognizer)?.let { stage = it.copy(offlineListening = true, heard = "") }
        listening = OfflineVoice.listen(
            context,
            onPartial = { text ->
                if (id == attempt) (stage as? VoiceStage.NoRecognizer)?.let { stage = it.copy(heard = text) }
            },
            onResult = { text ->
                if (id != attempt) return@listen
                // Silence: keep the phone option up rather than closing on the user.
                if (text == null) {
                    (stage as? VoiceStage.NoRecognizer)?.let { stage = it.copy(offlineListening = false, heard = "") }
                } else {
                    heard(text)
                }
            },
            onError = { message ->
                if (id == attempt) (stage as? VoiceStage.NoRecognizer)?.let { stage = it.copy(offlineListening = false, error = message) }
            },
        )
    }

    private fun heard(text: String?) {
        listening = null
        val command = text?.let(::parseVoiceCommand) ?: return finish(VoiceOutcome.NotHeard)
        stage = VoiceStage.Finding((command as? VoiceCommand.Navigate)?.query)
        val id = attempt
        scope.launch {
            val outcome = handle(command)
            if (id == attempt) finish(outcome)
        }
    }

    /** A request typed or said on the phone page (on the page's own thread). */
    private suspend fun fromPhone(command: VoiceCommand): VoiceOutcome {
        withContext(Dispatchers.Main) {
            stopListening()
            attempt++
            stage = VoiceStage.Finding((command as? VoiceCommand.Navigate)?.query)
        }
        val outcome = handle(command)
        withContext(Dispatchers.Main) { finish(outcome) }
        return outcome
    }

    private fun finish(outcome: VoiceOutcome) {
        show(VoiceStage.Done(outcome.message, ok = outcome is VoiceOutcome.Driving || outcome == VoiceOutcome.Stopped))
        // A drive announces itself (its first spoken turn); anything else is said here.
        if (outcome !is VoiceOutcome.Driving) speak(outcome.message)
    }

    /** Shows how it went for a few seconds, then closes. */
    private fun show(done: VoiceStage.Done) {
        stage = done
        scope.launch {
            delay(DONE_SHOWN_MS)
            if (stage === done) stage = VoiceStage.Closed
        }
    }

    private fun stopListening() {
        listening?.stop()
        listening = null
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val DONE_SHOWN_MS = 4_000L
    }
}

/**
 * The voice assistant's state, wired up: asks for the microphone the first time, listens on
 * hardware voice keys, and keeps the phone page served while MinMap is on screen.
 */
@Composable
fun rememberVoiceAssistant(
    handle: suspend (VoiceCommand) -> VoiceOutcome,
    speak: (String) -> Unit,
): Pair<VoiceAssistantState, () -> Unit> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentHandle by rememberUpdatedState(handle)
    val state = remember { VoiceAssistantState(context, scope, { currentHandle(it) }, speak) }
    // Asked when first needed; either answer goes on (no mic still leaves the phone page).
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { state.start() }
    val start: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) state.start() else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    val currentStart by rememberUpdatedState(start)
    var seenPresses by remember { mutableStateOf(VoiceKeys.presses.value) }
    LaunchedEffect(Unit) {
        VoiceKeys.presses.collect { count ->
            if (count != seenPresses) {
                seenPresses = count
                currentStart()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { runCatching { state.phoneMic?.start() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { state.phoneMic?.stop() }
    DisposableEffect(state) { onDispose { state.cancel(); state.phoneMic?.stop() } }
    return state to start
}

/** The voice panel: listening, finding, the phone/offline choices, and how it went. */
@Composable
fun VoicePanel(
    state: VoiceAssistantState,
    hazeState: HazeState,
    darkMap: Boolean,
    modifier: Modifier = Modifier,
) {
    val stage = state.stage
    val ink = if (darkMap) Color.White else Color(0xFF1C1C1E)
    val soft = ink.copy(alpha = 0.65f)
    val glass = remember(darkMap) { glassStyle(darkMap) }
    val edge = if (darkMap) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.6f)
    val shape = RoundedCornerShape(26.dp)

    AnimatedVisibility(visible = stage != VoiceStage.Closed, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(shape)
                .hazeBlur(HazeInput.Sources(hazeState), style = glass)
                .border(1.dp, edge, shape)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Column(Modifier.padding(end = 32.dp)) {
                when (stage) {
                    VoiceStage.Closed -> Unit
                    is VoiceStage.Listening -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MicGlyph(Color(0xFFE5484D), Modifier.size(22.dp))
                            Spacer(Modifier.size(10.dp))
                            Text("Listening…", color = ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stage.heard.ifBlank { "Say a place, like “navigate to Starbucks”." },
                            color = if (stage.heard.isBlank()) soft else ink,
                            fontSize = 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    is VoiceStage.Finding -> Text(
                        stage.query?.let { "Finding “$it”…" } ?: "One moment…",
                        color = ink,
                        fontSize = 18.sp,
                    )
                    is VoiceStage.Done -> Text(
                        stage.message,
                        color = if (stage.ok) ink else Color(0xFFE5484D),
                        fontSize = 17.sp,
                    )
                    is VoiceStage.NoRecognizer -> NoRecognizerChoices(stage, ink, soft, edge, onDownload = state::downloadOffline)
                }
            }
            // Close (and stop listening).
            Box(
                Modifier.align(Alignment.TopEnd).size(32.dp).clip(RoundedCornerShape(50)).clickable { state.cancel() },
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = soft, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun NoRecognizerChoices(
    stage: VoiceStage.NoRecognizer,
    ink: Color,
    soft: Color,
    edge: Color,
    onDownload: () -> Unit,
) {
    Text("Where to?", color = ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
    // 1. The phone as the mic.
    if (stage.phoneUrl != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            QrCode(stage.phoneUrl, Modifier.size(132.dp))
            Column {
                Text("Say it on your phone", color = ink, fontSize = 16.sp)
                Text(
                    "Scan this, then tap the mic on your phone's keyboard. Keep the page open for next time.",
                    color = soft,
                    fontSize = 13.sp,
                )
            }
        }
    } else {
        Text("Connect to Wi-Fi or your phone's hotspot to use your phone as the mic.", color = soft, fontSize = 13.sp)
    }
    // 2. Offline, on this device.
    if (OfflineVoice.SUPPORTED) {
        Spacer(Modifier.height(12.dp))
        when {
            stage.offlineListening -> Row(verticalAlignment = Alignment.CenterVertically) {
                MicGlyph(Color(0xFFE5484D), Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    stage.heard.ifBlank { "Or just say it: listening…" },
                    color = if (stage.heard.isBlank()) soft else ink,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            stage.downloading != null -> Text(
                "Downloading offline voice… ${(stage.downloading * 100).toInt()}%",
                color = soft,
                fontSize = 14.sp,
            )
            !stage.offlineInstalled -> Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, edge, RoundedCornerShape(50))
                    .clickable(onClick = onDownload)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text("Use voice on this unit (${OfflineVoice.DOWNLOAD_MB} MB, once)", color = ink, fontSize = 14.sp) }
        }
        stage.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Color(0xFFE5484D), fontSize = 13.sp)
        }
    }
}

/** A microphone, drawn so the app doesn't need an icon library for one glyph. */
@Composable
fun MicGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.1f
        // Capsule.
        drawRoundRect(color, Offset(w * 0.32f, 0f), Size(w * 0.36f, h * 0.62f), CornerRadius(w * 0.18f))
        // Cradle and stand.
        drawArc(color, 0f, 180f, false, Offset(w * 0.16f, h * 0.22f), Size(w * 0.68f, h * 0.56f), style = Stroke(stroke, cap = StrokeCap.Round))
        drawLine(color, Offset(w / 2, h * 0.78f), Offset(w / 2, h * 0.96f), stroke, StrokeCap.Round)
    }
}
