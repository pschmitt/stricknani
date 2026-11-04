package blue.anika.wolle.data.uploads

import android.content.Context
import android.net.Uri

/** FileProvider authority of the camera "Take photo" files (see `capture_paths.xml`). */
fun captureAuthority(context: Context): String = "${context.packageName}.captures"

/**
 * Deletes the camera temp file behind [uri] once its bytes are safely copied or uploaded. Any other
 * URI (a gallery pick) is left alone; FileProvider's delete removes the file itself.
 */
fun Context.deleteCapturedPhoto(uri: Uri) {
    if (uri.authority == captureAuthority(this)) {
        runCatching { contentResolver.delete(uri, null, null) }
    }
}
