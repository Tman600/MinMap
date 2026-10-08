package com.example.mapstyleeditor.account

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mapbox.common.MapboxOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The Mapbox account the map runs on. Each person signs in with their own public access token
 * (from account.mapbox.com), so their map loads and searches count against their own free tier.
 * Kept on the phone only.
 */
object MapboxAccount {
    /** The signed-in public token; empty when signed out. */
    var token by mutableStateOf("")
        private set

    /** The Mapbox username the token belongs to, for the "signed in as" line. */
    var user by mutableStateOf("")
        private set

    fun load(context: Context) {
        val prefs = prefs(context)
        user = prefs.getString(KEY_USER, "").orEmpty()
        use(prefs.getString(KEY_TOKEN, "").orEmpty())
    }

    fun signIn(context: Context, token: String, user: String) {
        prefs(context).edit {
            putString(KEY_TOKEN, token)
            putString(KEY_USER, user)
        }
        this.user = user
        use(token)
    }

    fun signOut(context: Context) {
        prefs(context).edit { clear() }
        user = ""
        token = ""
    }

    private fun use(token: String) {
        // Mapbox needs the token set before a map is created; the map only shows once there is one.
        if (token.isNotEmpty()) MapboxOptions.accessToken = token
        this.token = token
    }

    private fun prefs(context: Context) = context.getSharedPreferences("mapbox_account", Context.MODE_PRIVATE)

    private const val KEY_TOKEN = "token"
    private const val KEY_USER = "user"
}

sealed interface TokenCheck {
    data class Valid(val user: String) : TokenCheck
    data class Invalid(val reason: String) : TokenCheck
}

/** Asks Mapbox whether [token] is a working public token, and whose it is. */
suspend fun checkToken(token: String): TokenCheck = withContext(Dispatchers.IO) {
    if (token.startsWith("sk.")) {
        return@withContext TokenCheck.Invalid("That's a secret token. Use a public one (it starts with pk.).")
    }
    if (!token.startsWith("pk.")) {
        return@withContext TokenCheck.Invalid("Mapbox public tokens start with pk.")
    }
    val body = runCatching {
        val conn = URL("https://api.mapbox.com/tokens/v2?access_token=" + URLEncoder.encode(token, "UTF-8"))
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            (if (conn.responseCode < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull() ?: return@withContext TokenCheck.Invalid("Couldn't reach Mapbox. Check your connection and try again.")
    val json = runCatching { JSONObject(body) }.getOrNull()
    when (json?.optString("code")) {
        "TokenValid" -> TokenCheck.Valid(json.optJSONObject("token")?.optString("user").orEmpty())
        "TokenExpired" -> TokenCheck.Invalid("That token has expired. Copy a current one from Mapbox.")
        "TokenRevoked" -> TokenCheck.Invalid("That token was deleted on Mapbox. Copy a current one.")
        else -> TokenCheck.Invalid("Mapbox didn't accept that token. Check it was copied in full.")
    }
}
