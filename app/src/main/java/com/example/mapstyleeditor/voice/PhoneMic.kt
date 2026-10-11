package com.example.mapstyleeditor.voice

import android.content.Context
import androidx.core.content.edit
import com.example.mapstyleeditor.net.LanPage
import kotlinx.coroutines.runBlocking

/**
 * Your phone as MinMap's microphone, for devices with no speech recognizer of their own. The
 * screen shows a QR code of [url]; the phone opens a "Where to?" page, and you say the place with
 * the phone keyboard's mic (or type it). It's sent here and handled like a spoken request.
 *
 * The page's address is kept between app starts, so a phone can leave it open (or bookmarked) and
 * keep sending destinations on every drive.
 */
class PhoneMic(context: Context, private val handle: suspend (VoiceCommand) -> VoiceOutcome) {
    private val page = LanPage(PREFERRED_PORT, secret(context)) { method, form -> respond(method, form) }

    fun url(): String? = page.url()
    fun start() = page.start()
    fun stop() = page.stop()

    private fun respond(method: String, form: Map<String, String>): String {
        if (method != "POST") return formPage(null, ok = true)
        val command = if (form.containsKey("stop")) VoiceCommand.Stop else parseVoiceCommand(form["where"].orEmpty())
        val outcome = if (command == null) VoiceOutcome.NotHeard else runBlocking { handle(command) }
        val detail = (outcome as? VoiceOutcome.Driving)?.place?.detail?.takeIf { it.isNotBlank() }
        val message = outcome.message + detail?.let { " · $it" }.orEmpty()
        return formPage(message, ok = outcome is VoiceOutcome.Driving || outcome == VoiceOutcome.Stopped)
    }

    private fun formPage(message: String?, ok: Boolean) = """
        <h1>Where to?</h1>
        <p class="muted">Tap the mic on your keyboard and say a place, or type it. MinMap starts driving there.</p>
        <form method="post" action="/${page.secret}">
          <input name="where" placeholder="e.g. Starbucks, or 12 Main St" autofocus autocomplete="off" enterkeyhint="go" required>
          ${message?.let { "<p class=\"${if (ok) "ok" else "error"}\">${LanPage.escape(it)}</p>" }.orEmpty()}
          <button type="submit">Go</button>
        </form>
        <form method="post" action="/${page.secret}">
          <input type="hidden" name="stop" value="1">
          <button class="quiet" type="submit">Stop navigation</button>
        </form>
        <p class="muted small">Keep this page open to send more places. It only works on the same network as the MinMap screen.</p>
    """.trimIndent()

    private companion object {
        const val PREFERRED_PORT = 8766

        fun secret(context: Context): String {
            val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
            prefs.getString("phone_page", null)?.let { return it }
            return LanPage.randomSecret().also { prefs.edit { putString("phone_page", it) } }
        }
    }
}
