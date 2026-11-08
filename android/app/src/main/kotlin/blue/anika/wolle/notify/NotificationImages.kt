package blue.anika.wolle.notify

import android.content.Context
import android.graphics.Bitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** A thumbnail for a notification: a square [icon] for the large icon and a wide [picture]. */
class NotificationImage(val icon: Bitmap, val picture: Bitmap)

/**
 * Loads an item's thumbnail for a notification through the app's authenticated image pipeline
 * (Coil's singleton loader, which uses the `@MediaClient` OkHttp client: auth header, server URL
 * rewrite, disk cache). Every failure - no URL, offline, 401, timeout, a decoder error - is just
 * "no image": the notification is posted without one.
 */
@Singleton
class NotificationImages @Inject constructor(@ApplicationContext private val context: Context) {

    /** [url] must already be absolute (`MediaUrlResolver.resolve`). Runs off the main thread. */
    suspend fun load(url: String?): NotificationImage? {
        if (url.isNullOrBlank()) return null
        return try {
            withTimeoutOrNull(LOAD_TIMEOUT_MILLIS) {
                val request =
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(Size(PICTURE_WIDTH, PICTURE_HEIGHT))
                        .scale(Scale.FILL)
                        .precision(Precision.INEXACT)
                        .allowHardware(false)
                        .build()
                val result =
                    SingletonImageLoader.get(context).execute(request) as? SuccessResult
                        ?: return@withTimeoutOrNull null
                val source = result.image.toBitmap()
                NotificationImage(
                    icon = centerCropSquare(source, ICON_SIZE),
                    picture = scaleDownTo(source, PICTURE_WIDTH),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private companion object {
        const val LOAD_TIMEOUT_MILLIS = 8_000L
        const val ICON_SIZE = 256
        const val PICTURE_WIDTH = 1024
        const val PICTURE_HEIGHT = 512
    }
}

/** The centered square of [source], scaled to [size] px. */
internal fun centerCropSquare(source: Bitmap, size: Int): Bitmap {
    val side = minOf(source.width, source.height)
    val x = (source.width - side) / 2
    val y = (source.height - side) / 2
    val square = Bitmap.createBitmap(source, x, y, side, side)
    return if (side == size) square else Bitmap.createScaledBitmap(square, size, size, true)
}

/** [source] unchanged, or scaled down to [maxWidth] px wide keeping the aspect ratio. */
internal fun scaleDownTo(source: Bitmap, maxWidth: Int): Bitmap {
    if (source.width <= maxWidth) return source
    val height = (source.height.toLong() * maxWidth / source.width).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(source, maxWidth, height, true)
}
