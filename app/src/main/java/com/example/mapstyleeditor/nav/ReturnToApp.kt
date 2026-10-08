package com.example.mapstyleeditor.nav

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.example.mapstyleeditor.MainActivity

/**
 * Brings the app back over Google Maps once its guidance has started.
 *
 * Google Maps only starts navigating while it's on screen, so it has to come to the front first.
 * Android then refuses to let a background app bring itself back, unless that app has a window
 * showing. With "Display over other apps" granted, we show a small "Back to your map" bubble over
 * Google Maps for a moment, and launch from it.
 */
object ReturnToApp {
    /** Set when Drive hands off to Google Maps; cleared once we've come back (or given up). */
    @Volatile private var armedAt = 0L

    fun canReturn(context: Context) = Settings.canDrawOverlays(context)

    fun arm() {
        armedAt = System.currentTimeMillis()
    }

    /** Called with each Google Maps guidance update; returns to the app on the first one after [arm]. */
    internal fun onGuidance(context: Context) {
        val armed = armedAt
        if (armed == 0L) return
        armedAt = 0L
        if (System.currentTimeMillis() - armed > GIVE_UP_MS || !canReturn(context)) return

        val app = context.applicationContext
        val wm = app.getSystemService(WindowManager::class.java)
        val bubble = bubble(app)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(app, 64)
        }
        val main = Handler(Looper.getMainLooper())
        main.post {
            runCatching { wm.addView(bubble, params) }.onFailure { return@post }
            // Launch once the bubble is actually on screen, then take it down.
            main.postDelayed({
                app.startActivity(
                    Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
                main.postDelayed({ runCatching { wm.removeView(bubble) } }, 700)
            }, 250)
        }
    }

    private fun bubble(context: Context) = TextView(context).apply {
        text = "Back to your map…"
        setTextColor(0xFF1C1C1E.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        val padH = dp(context, 20)
        val padV = dp(context, 10)
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 24).toFloat()
            setColor(0xEEF7F7F7.toInt())
            setStroke(dp(context, 1), 0x99FFFFFF.toInt())
        }
        elevation = dp(context, 6).toFloat()
    }

    private fun dp(context: Context, value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    /** If Google Maps never starts guiding (e.g. it asks a question first), don't yank the user back later. */
    private const val GIVE_UP_MS = 60_000L
}
