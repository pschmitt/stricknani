package blue.anika.wolle.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R

/** The owner's avatar (initial as the fallback) on a card; "Owned by Anika" for accessibility. */
@Composable
fun OwnerBadge(owner: OwnerBadgeData, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val context = LocalOwnerContext.current
    val description = stringResource(R.string.owner_badge_description, owner.name)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        modifier = modifier.semantics { contentDescription = description }.testTag("owner-badge"),
    ) {
        AccountAvatar(
            url = context.resolveUrl(owner.avatarUrl),
            email = owner.name,
            size = size,
            modifier = Modifier.padding(1.dp),
        )
    }
}

/**
 * A compact blue banner on the screens of someone else's project or yarn: whose it is, and that
 * changes are stored there and recorded in the audit log.
 */
@Composable
fun OwnerBanner(banner: OwnerBannerData, modifier: Modifier = Modifier) {
    val text =
        stringResource(
            if (banner.kind == OwnedKind.Project) R.string.owner_banner_project
            else R.string.owner_banner_yarn,
            banner.ownerName,
        )
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth().testTag("owner-banner"),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
