package com.example.mapstyleeditor.nav

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Google Maps' current guidance, as shown in its navigation notification. */
data class NavInstruction(
    /** The full instruction, e.g. "Turn right onto Main St". */
    val instruction: String,
    /** Distance to the maneuver as Google shows it, e.g. "500 ft". */
    val distanceText: String?,
    val distanceMeters: Double?,
    /** The road the maneuver leads onto, e.g. "Main St". */
    val street: String?,
    /** e.g. "Arrive 12:36 PM". */
    val arrival: String?,
    /** Google's maneuver arrow (white on transparent). */
    val arrow: Bitmap?,
    /** Google's own "Exit navigation" action, so ending a drive here also ends it in Google Maps. */
    val exitAction: PendingIntent?,
)

/** Where the latest Google Maps guidance lands. Only ever holds Google Maps' navigation notification. */
object GoogleNav {
    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    private val _current = MutableStateFlow<NavInstruction?>(null)
    val current: StateFlow<NavInstruction?> = _current.asStateFlow()

    internal fun update(instruction: NavInstruction?) {
        _current.value = instruction
    }

    fun hasNotificationAccess(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun listenerComponent(context: Context) = ComponentName(context, GoogleNavListener::class.java)
}

/**
 * Android hands a notification listener every notification on the phone; this one looks only at
 * Google Maps' navigation notification and ignores the rest without reading them.
 */
class GoogleNavListener : NotificationListenerService() {
    override fun onListenerConnected() {
        GoogleNav.update(activeNotifications?.firstOrNull(::isMapsNavigation)?.let { parse(it.notification) })
    }

    override fun onListenerDisconnected() = GoogleNav.update(null)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isMapsNavigation(sbn)) return
        GoogleNav.update(parse(sbn.notification))
        ReturnToApp.onGuidance(this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isMapsNavigation(sbn)) GoogleNav.update(null)
    }

    private fun isMapsNavigation(sbn: StatusBarNotification) =
        sbn.packageName == GoogleNav.MAPS_PACKAGE && sbn.notification.category == Notification.CATEGORY_NAVIGATION

    private fun parse(n: Notification): NavInstruction {
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        // Samsung's "Now Bar" copy of the guidance splits distance and road cleanly. Other phones
        // get the classic layout, where the title may be the distance and the text the instruction.
        val primary = extras.getCharSequence(KEY_PRIMARY)?.toString()?.trim()
        val secondary = extras.getCharSequence(KEY_SECONDARY)?.toString()?.trim()

        val titleIsDistance = parseDistanceMeters(title) != null && title.length <= 12
        val instruction = if (titleIsDistance && !text.isNullOrEmpty()) text else title
        val distanceText = primary?.takeIf { parseDistanceMeters(it) != null }
            ?: title.takeIf { titleIsDistance }
            ?: DISTANCE_PREFIX.find(instruction)?.value?.trim()

        return NavInstruction(
            instruction = instruction,
            distanceText = distanceText,
            distanceMeters = distanceText?.let(::parseDistanceMeters),
            street = streetFrom(secondary) ?: streetFrom(instruction),
            arrival = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            arrow = n.getLargeIcon()?.loadDrawable(this)?.toBitmap(),
            exitAction = n.actions?.firstOrNull()?.actionIntent,
        )
    }

    private companion object {
        const val KEY_PRIMARY = "android.ongoingActivityNoti.primaryInfo"
        const val KEY_SECONDARY = "android.ongoingActivityNoti.secondaryInfo"
        val DISTANCE_PREFIX = Regex("""^\d[\d.,]*\s?(ft|mi|m|km)\b""", RegexOption.IGNORE_CASE)
        val ROAD_AFTER = Regex("""\b(?:onto|on|toward|towards|to stay on|to)\s+(.+)$""", RegexOption.IGNORE_CASE)

        /** "Turn right onto Main St" or "toward Main St" -> "Main St". */
        fun streetFrom(text: String?): String? {
            if (text.isNullOrBlank()) return null
            return ROAD_AFTER.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}

/** "500 ft", "0.3 mi", "1,2 km", "200 m" -> meters. */
fun parseDistanceMeters(text: String): Double? {
    val match = Regex("""^\s*(\d[\d.,]*)\s?(ft|mi|m|km)\b""", RegexOption.IGNORE_CASE).find(text) ?: return null
    val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    return number * when (match.groupValues[2].lowercase()) {
        "ft" -> 0.3048
        "mi" -> 1609.344
        "km" -> 1000.0
        else -> 1.0
    }
}
