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
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.mapstyleeditor.voice.Listening
import com.example.mapstyleeditor.voice.SystemSpeech
import com.example.mapstyleeditor.voice.VoiceCommand
import com.example.mapstyleeditor.voice.VoiceOutcome
import com.example.mapstyleeditor.voice.parseVoiceCommand
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Presses of a hardware voice key (a headset or steering-wheel button that reaches the app), or
 * MinMap being opened as the voice command app. The voice panel starts listening on each one.
 */
object VoiceKeys {
    val presses = MutableStateFlow(0)
    fun press() {
        presses.value++
    }
}

/** What the voice panel shows. */
sealed interface VoiceStage {
    data object Closed : VoiceStage
    data class Listening(val heard: String) : VoiceStage
    data class Finding(val query: String?) : VoiceStage
    data class Done(val message: String, val ok: Boolean) : VoiceStage
}

/**
 * "Navigate to Starbucks" by voice, through the phone's own speech recognizer. Whatever is heard
 * goes to [handle]. (The AOSP build also has a phone-as-mic page and offline recognition for head
 * units without a recognizer; a phone always has one, so this one doesn't.)
 */
class VoiceAssistantState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val handle: suspend (VoiceCommand) -> VoiceOutcome,
) {
    var stage by mutableStateOf<VoiceStage>(VoiceStage.Closed)
        private set

    /** Whether to offer the mic at all: without a recognizer there's nothing to listen with. */
    val usable = SystemSpeech.available(context)

    private var listening: Listening? = null
    /** Bumped on every start and cancel, so a late answer from an abandoned attempt is ignored. */
    private var attempt = 0

    fun start() {
        stopListening()
        val id = ++attempt
        if (!hasMic()) return show(VoiceStage.Done("MinMap needs the microphone for voice.", ok = false))
        stage = VoiceStage.Listening("")
        listening = SystemSpeech.listen(
            context,
            onPartial = { if (id == attempt) stage = VoiceStage.Listening(it) },
            onResult = { if (id == attempt) heard(it) },
            onFailed = {
                if (id == attempt) {
                    listening = null
                    show(VoiceStage.Done("Voice recognition isn't working right now. Try again in a moment.", ok = false))
                }
            },
        )
    }

    fun cancel() {
        stopListening()
        attempt++
        stage = VoiceStage.Closed
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

    private fun finish(outcome: VoiceOutcome) =
        show(VoiceStage.Done(outcome.message, ok = outcome is VoiceOutcome.Driving || outcome == VoiceOutcome.Stopped))

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
 * The voice assistant's state, wired up: asks for the microphone the first time and listens on
 * hardware voice keys.
 */
@Composable
fun rememberVoiceAssistant(handle: suspend (VoiceCommand) -> VoiceOutcome): Pair<VoiceAssistantState, () -> Unit> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentHandle by rememberUpdatedState(handle)
    val state = remember { VoiceAssistantState(context, scope) { currentHandle(it) } }
    // Asked when first needed; a "no" ends up as the "needs the microphone" message.
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { state.start() }
    val start: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) state.start() else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    val currentStart by rememberUpdatedState(start)
    var seenPresses by remember { mutableIntStateOf(VoiceKeys.presses.value) }
    LaunchedEffect(Unit) {
        VoiceKeys.presses.collect { count ->
            if (count != seenPresses) {
                seenPresses = count
                currentStart()
            }
        }
    }
    DisposableEffect(state) { onDispose { state.cancel() } }
    return state to start
}

/** The voice panel: listening, finding, and how it went. */
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
