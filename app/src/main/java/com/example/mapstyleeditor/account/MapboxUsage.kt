package com.example.mapstyleeditor.account

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

/**
 * Keeps track of the Mapbox requests MinMap makes on the signed-in account this month, and notices
 * when Mapbox stops answering them.
 *
 * Mapbox accounts made without a payment method ("demo access") have small monthly caps on each
 * service, and once any one is reached, every Mapbox service refuses the account's token until a
 * payment method is added. MinMap can't read the account's real usage (that needs a secret token),
 * so it counts its own requests, and shows [blocked] when Mapbox starts refusing them.
 *
 * A refusal is only a suspicion until Mapbox confirms it: the map's own style is fetched once with
 * the token, and only a 401/403 there shows the panel. The same check clears it again (on returning
 * to the app, or "Check again"), so the panel never outlives the problem.
 */
object MapboxUsage {
    /** Mapbox is refusing the token: the account's demo allowance is used up (or it's been paused). */
    var blocked by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checking = false
    private var checkedAt = 0L

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("mapbox_usage", Context.MODE_PRIVATE)
    }

    /** Records the answer to a request to [url]; call it for every Mapbox API request. */
    fun record(url: String, status: Int) {
        if (!url.startsWith("https://api.mapbox.com/")) return
        when (status) {
            401, 403 -> confirm()
            in 200..299 -> {
                blocked = false
                kindOf(url)?.let { kind ->
                    prefs?.edit { putInt(key(kind), count(kind) + 1) }
                }
            }
        }
    }

    /** The map couldn't load something; if the message says the token was turned away, check. */
    fun mapError(message: String) {
        if (AUTH_ERROR.containsMatchIn(message)) confirm()
    }

    /**
     * Asks Mapbox whether it's really refusing the token (one style request), at most every half
     * minute unless [force]d, and shows or clears the panel to match.
     */
    fun confirm(force: Boolean = false) {
        val token = MapboxAccount.token
        if (token.isEmpty() || checking) return
        if (!force && System.currentTimeMillis() - checkedAt < CHECK_EVERY_MS) return
        checking = true
        scope.launch {
            val refused = styleRefused(token)
            withContext(Dispatchers.Main) {
                // Unanswered (offline) counts as not refused: no panel for a dropped connection.
                blocked = refused == true
                checkedAt = System.currentTimeMillis()
                checking = false
            }
        }
    }

    /** Signed out, or signed in again: start from a clean slate. */
    fun reset() {
        blocked = false
    }

    fun count(kind: Kind): Int = prefs?.getInt(key(kind), 0) ?: 0

    /** "12 searches · 30 routes · 4 street lookups" for this month, or null before any. */
    fun summary(): String? = Kind.entries.mapNotNull { k ->
        count(k).takeIf { it > 0 }?.let { "$it ${if (it == 1) k.one else k.many}" }
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** "HTTP status code 401", "Unauthorized", "Forbidden"... but not tile numbers that contain 401. */
    private val AUTH_ERROR = Regex("status code 40[13]\\b|unauthori[sz]ed|not authorized|forbidden|invalid token", RegexOption.IGNORE_CASE)
    private const val CHECK_EVERY_MS = 30_000L

    private fun key(kind: Kind) = "${YearMonth.now()}-${kind.name}"

    private fun kindOf(url: String): Kind? = when {
        "/searchbox/v1/retrieve" in url -> Kind.SEARCHES
        "/searchbox/v1/category" in url -> Kind.NEARBY
        "/directions/" in url -> Kind.ROUTES
        "/tilequery/" in url -> Kind.STREETS
        "/geocode/" in url -> Kind.ADDRESSES
        else -> null
    }

    /** What Mapbox bills MinMap's requests as, roughly: one picked search result is one search session. */
    enum class Kind(val one: String, val many: String) {
        SEARCHES("search", "searches"),
        NEARBY("nearby list", "nearby lists"),
        ROUTES("route", "routes"),
        STREETS("street lookup", "street lookups"),
        ADDRESSES("address lookup", "address lookups"),
    }
}
