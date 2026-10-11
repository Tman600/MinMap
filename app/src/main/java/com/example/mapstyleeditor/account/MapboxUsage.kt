package com.example.mapstyleeditor.account

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.time.YearMonth

/**
 * Keeps track of the Mapbox requests MinMap makes on the signed-in account this month, and notices
 * when Mapbox stops answering them.
 *
 * Mapbox accounts made without a payment method ("demo access") have small monthly caps on each
 * service, and once any one is reached, every Mapbox service refuses the account's token until a
 * payment method is added. MinMap can't read the account's real usage (that needs a secret token),
 * so it counts its own requests, and shows [blocked] when Mapbox starts refusing them.
 */
object MapboxUsage {
    /** Mapbox is refusing the token: the account's demo allowance is used up (or it's been paused). */
    var blocked by mutableStateOf(false)
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("mapbox_usage", Context.MODE_PRIVATE)
    }

    /** Records the answer to a request to [url]; call it for every Mapbox API request. */
    fun record(url: String, status: Int) {
        if (!url.startsWith("https://api.mapbox.com/")) return
        when (status) {
            401, 403 -> blocked = true
            in 200..299 -> {
                blocked = false
                kindOf(url)?.let { kind ->
                    prefs?.edit { putInt(key(kind), count(kind) + 1) }
                }
            }
        }
    }

    /** The map itself couldn't load its style or tiles because Mapbox turned the token away. */
    fun mapRefused() {
        blocked = true
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
