package blue.anika.wolle.ui.common

import android.content.Context
import android.content.Intent

/** Opens the system share sheet with [url] as plain text (SNA-17). */
fun Context.shareUrl(url: String) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
    startActivity(Intent.createChooser(intent, null))
}
