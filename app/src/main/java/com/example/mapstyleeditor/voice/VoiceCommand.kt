package com.example.mapstyleeditor.voice

import com.example.mapstyleeditor.learn.Trip
import com.example.mapstyleeditor.nav.distanceMeters
import com.example.mapstyleeditor.places.PlaceSearch
import com.example.mapstyleeditor.ui.Place
import com.mapbox.geojson.Point
import java.util.Locale

/** What a spoken (or typed-on-the-phone) request asks for. */
sealed interface VoiceCommand {
    /** "Navigate to Starbucks", "take me to the nearest gas station", or just "Starbucks". */
    data class Navigate(val query: String) : VoiceCommand

    /** "Stop navigation", "cancel", "end the drive". */
    data object Stop : VoiceCommand
}

/** How a command went, to show on screen and on the phone page. */
sealed interface VoiceOutcome {
    val message: String

    data class Driving(val place: Place) : VoiceOutcome {
        override val message get() = "Driving to ${place.name}"
    }

    data class NotFound(val query: String) : VoiceOutcome {
        override val message get() = "Couldn't find “$query”. Try saying it another way."
    }

    data object Stopped : VoiceOutcome {
        override val message get() = "Navigation stopped."
    }

    data object NothingToStop : VoiceOutcome {
        override val message get() = "Not navigating right now."
    }

    data object NotHeard : VoiceOutcome {
        override val message get() = "Didn't catch that. Try again, e.g. “navigate to Starbucks”."
    }
}

/**
 * Reads a request in plain words. Speech engines differ (the system one capitalises and punctuates,
 * the offline one gives bare lowercase words), so everything is compared lowercased and unpunctuated.
 * Anything that isn't a stop is a place: the mic button already means "where to?".
 */
fun parseVoiceCommand(heard: String): VoiceCommand? {
    var text = heard.lowercase(Locale.US)
        .replace(Regex("[^\\p{L}\\p{N}'& -]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    WAKE_WORDS.firstOrNull { text == it || text.startsWith("$it ") }?.let { text = text.removePrefix(it).trim() }
    if (STOP.matches(text)) return VoiceCommand.Stop

    LEADS.firstOrNull { text == it || text.startsWith("$it ") }?.let { text = text.removePrefix(it).trim() }
    text = text.removeSuffix(" please").trim()
    // "The nearest gas station near me" is a search for "gas station" (results come nearest first).
    FILLER_START.firstOrNull { text.startsWith("$it ") }?.let { text = text.removePrefix(it).trim() }
    FILLER_END.firstOrNull { text.endsWith(" $it") }?.let { text = text.removeSuffix(it).trim() }
    return if (text.isBlank()) null else VoiceCommand.Navigate(text)
}

private val WAKE_WORDS = listOf("ok minmap", "okay minmap", "hey minmap", "ok min map", "okay min map", "hey min map", "minmap", "min map")
private val STOP = Regex("^(stop|cancel|end|exit|quit)( the)?( navigation| navigating| route| drive| driving| directions| trip)?$")

/** Longest first, so "navigate me to" wins over "navigate". */
private val LEADS = listOf(
    "get directions to", "give me directions to", "directions to", "navigate me to", "navigation to", "navigate to",
    "take me to", "bring me to", "get me to", "drive me to", "drive to", "let's go to", "lets go to", "go to",
    "head to", "route to", "find me", "find", "show me", "where is", "navigate", "take me", "directions",
).sortedByDescending { it.length }
private val FILLER_START = listOf("the nearest", "the closest", "nearest", "closest", "a nearby", "the", "a", "an")
private val FILLER_END = listOf("near me", "nearby", "around here", "close by")

/**
 * Turns a spoken place into somewhere to drive. A place you've driven to before under that name is
 * used straight away (no Mapbox request); anything else is one Search Box lookup, near you. A
 * category ("gas station") goes to the nearest place of that kind.
 */
class VoiceDestinations(token: String, private val trips: () -> List<Trip>) {
    private val search = PlaceSearch(token)

    suspend fun find(query: String, near: Point?, here: Point?): Place? {
        learned(query, here)?.let { return it }
        val first = search.suggest(query, near, here).firstOrNull() ?: return null
        val category = first.category
        if (category != null) return search.category(category, near, here).firstOrNull()?.let { search.open(it) }
        return search.open(first)
    }

    private fun learned(query: String, here: Point?): Place? {
        val wanted = simplify(query)
        return trips()
            .filter { simplify(it.name) == wanted }
            // Your usual Starbucks, unless you're somewhere else entirely.
            .filter { here == null || distanceMeters(here, it.destination) < LEARNED_RANGE_METERS }
            .maxByOrNull { it.startedAt }
            ?.let { Place(it.name, "", it.destination.longitude(), it.destination.latitude()) }
    }

    private fun simplify(s: String) = s.lowercase(Locale.US).replace(Regex("[^\\p{L}\\p{N}]"), "")

    private companion object {
        const val LEARNED_RANGE_METERS = 50_000.0
    }
}
