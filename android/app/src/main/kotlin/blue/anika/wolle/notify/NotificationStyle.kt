package blue.anika.wolle.notify

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import blue.anika.wolle.R

/**
 * What every Stricknani notification shares: the brand accent (tints the small icon and the app
 * name), a visible timestamp and an optional sub text naming the area ("Sync", "Project timer").
 */
fun NotificationCompat.Builder.applyBrand(
    context: Context,
    subText: CharSequence? = null,
    whenMillis: Long = System.currentTimeMillis(),
): NotificationCompat.Builder = apply {
    setColor(ContextCompat.getColor(context, R.color.notification_accent))
    setShowWhen(true)
    setWhen(whenMillis)
    if (!subText.isNullOrBlank()) setSubText(subText)
}
