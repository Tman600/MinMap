package com.example.mapstyleeditor.demo

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.createBitmap
import com.example.mapstyleeditor.nav.NavInstruction
import com.mapbox.geojson.Point

/**
 * Drive mode without Google Maps, for product screenshots on an emulator: debug builds only, started
 * from adb with a destination and the turn card's text. Positions come from the emulator's own GPS
 * (adb emu geo fix), so the puck, route line and camera all run exactly as on a real drive.
 *
 *   adb shell am start -n app.minmap/com.example.mapstyleeditor.MainActivity \
 *     --es demo_dest "-73.9857,40.7484" --es demo_instruction "Turn left onto W 34th St" \
 *     --es demo_distance "0.3 mi" --es demo_arrival "Arrive 9:52 AM"
 */
class DemoDrive(val destination: Point, val instruction: NavInstruction) {
    companion object {
        fun from(intent: Intent?, appInfo: ApplicationInfo): DemoDrive? {
            if (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            val dest = intent?.getStringExtra("demo_dest")?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() } ?: return null
            if (dest.size != 2) return null
            val text = intent.getStringExtra("demo_instruction") ?: "Continue straight"
            return DemoDrive(
                Point.fromLngLat(dest[0], dest[1]),
                NavInstruction(
                    instruction = text,
                    distanceText = intent.getStringExtra("demo_distance"),
                    distanceMeters = null,
                    // No street: the drive session then leaves the route line as Mapbox planned it.
                    street = null,
                    arrival = intent.getStringExtra("demo_arrival"),
                    arrow = arrow(left = !text.contains("right", ignoreCase = true)),
                    exitAction = null,
                ),
            )
        }

        /** A turn arrow like Google's: white on transparent, turning left or right. */
        private fun arrow(left: Boolean): Bitmap {
            val size = 144
            val bitmap = createBitmap(size, size)
            val canvas = Canvas(bitmap)
            if (left) canvas.scale(-1f, 1f, size / 2f, size / 2f)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 16f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            // Up from the bottom, then round the corner to the right.
            canvas.drawPath(
                Path().apply {
                    moveTo(48f, 128f)
                    lineTo(48f, 78f)
                    quadTo(48f, 52f, 74f, 52f)
                    lineTo(104f, 52f)
                },
                stroke,
            )
            val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
            canvas.drawPath(
                Path().apply {
                    moveTo(132f, 52f)
                    lineTo(98f, 26f)
                    lineTo(98f, 78f)
                    close()
                },
                head,
            )
            return bitmap
        }
    }
}
