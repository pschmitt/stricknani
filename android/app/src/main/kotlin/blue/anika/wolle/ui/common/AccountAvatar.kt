package blue.anika.wolle.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import blue.anika.wolle.ui.admin.initialOf
import coil3.compose.SubcomposeAsyncImage

/**
 * The signed-in user's round profile picture. [url] is an absolute media URL (see
 * `MediaUrlResolver`); Coil loads it through the app's authenticated media client. While it loads,
 * if it fails, or when there is no picture, a circle shows the first letter of [email] on the
 * primary container colour, or the Phosphor user-circle glyph when not even the email is known.
 * Never a broken image.
 */
@Composable
fun AccountAvatar(url: String?, email: String?, size: Dp, modifier: Modifier = Modifier) {
    val fallback: @Composable () -> Unit = {
        if (email.isNullOrBlank()) {
            Icon(
                PhosphorIcons.UserCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(size),
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Text(
                    initialOf(email),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = (size.value * 0.5f).sp,
                )
            }
        }
    }
    Box(modifier = modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        if (url == null) {
            fallback()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
                loading = { fallback() },
                error = { fallback() },
            )
        }
    }
}
