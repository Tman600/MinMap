package com.example.mapstyleeditor.account

import com.example.mapstyleeditor.net.LanPage
import kotlinx.coroutines.runBlocking

/**
 * Lets a phone send the Mapbox token to this device, for screens where typing a 90-character token
 * is miserable (car head units). The sign-in screen shows a QR code of [url]; the phone opens it,
 * signs in to Mapbox in its own browser, pastes the token into the page and sends it here.
 *
 * It runs only while the sign-in screen is up, and a token is only accepted once Mapbox confirms
 * it (the same check as pasting it on the device).
 */
class TokenHandoff(private val onToken: (token: String, user: String) -> Unit) {
    @Volatile private var failures = 0
    private val page = LanPage(PREFERRED_PORT) { method, form -> respond(method, form) }

    fun url(): String? = page.url()
    fun start() = page.start()
    fun stop() = page.stop()

    private fun respond(method: String, form: Map<String, String>): String {
        // Someone trying token after token: stop answering until the sign-in screen is reopened.
        if (failures >= MAX_FAILURES) return "<p>This link has expired. Scan the code on the screen again.</p>"
        if (method != "POST") return formPage(error = null)
        return when (val result = runBlocking { checkToken(form["token"].orEmpty().trim()) }) {
            is TokenCheck.Valid -> {
                onToken(form["token"].orEmpty().trim(), result.user)
                val who = result.user.takeIf { it.isNotEmpty() }?.let { " as <b>${LanPage.escape(it)}</b>" }.orEmpty()
                "<h1>Connected</h1><p>MinMap is signed in$who. The map is loading on the screen; you can close this page.</p>"
            }
            is TokenCheck.Invalid -> {
                failures++
                formPage(error = result.reason)
            }
        }
    }

    private fun formPage(error: String?) = """
        <h1>Connect MinMap</h1>
        <p class="muted">Sends your Mapbox token to the MinMap screen you just scanned.</p>
        <ol>
          <li>Open <a href="$TOKENS_PAGE" target="_blank" rel="noreferrer">your Mapbox access tokens</a>
            (sign in, or sign up free) and copy the <b>Default public token</b>.</li>
          <li>Paste it here and send it.</li>
        </ol>
        <form method="post" action="/${page.secret}">
          <input class="mono" name="token" placeholder="pk...." autocomplete="off" autocapitalize="none" autocorrect="off" spellcheck="false" required>
          ${error?.let { "<p class=\"error\">${LanPage.escape(it)}</p>" }.orEmpty()}
          <button type="submit">Send to MinMap</button>
        </form>
        <p class="muted small">The token goes straight to the MinMap screen over your own network and stays on that device.</p>
    """.trimIndent()

    private companion object {
        /** Tried first so the address stays the same between visits; any free port if it's taken. */
        const val PREFERRED_PORT = 8765
        /** Rejected tokens before the page stops answering (until the sign-in screen is reopened). */
        const val MAX_FAILURES = 20
        const val TOKENS_PAGE = "https://account.mapbox.com/access-tokens/"
    }
}
