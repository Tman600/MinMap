package com.example.mapstyleeditor.nav

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.mapstyleeditor.MainActivity
import com.example.mapstyleeditor.R

/**
 * The app's own live turn while driving: an ongoing notification asking to be promoted to a
 * "Live Update", which Android 16+ shows as a chip in the status bar (and Samsung in its Now Bar).
 * It mirrors Google Maps' current guidance and taps through to the app.
 */
object DriveNotification {
    private const val CHANNEL = "drive"
    private const val ID = 1

    fun show(context: Context, nav: NavInstruction?) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = nav?.let { listOfNotNull(it.distanceText, it.instruction).joinToString(" · ") } ?: "Starting navigation…"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_drive)
            .setContentTitle(title)
            .setContentText(nav?.arrival)
            .setLargeIcon(nav?.arrow)
            .setStyle(NotificationCompat.BigTextStyle().bigText(nav?.arrival))
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            // The status bar chip's text: just the distance, like Google Maps' own chip.
            .setShortCriticalText(nav?.distanceText ?: "Drive")
            .setRequestPromotedOngoing(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID, notification)
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            // Live Updates can't be promoted from a low-importance channel, so DEFAULT, kept silent.
            NotificationChannel(CHANNEL, "Drive mode", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Your next turn while driving"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }
}
