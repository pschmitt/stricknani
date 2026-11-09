package blue.anika.wolle.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.ACCESS_EDITOR

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
    val project = banner.kind == OwnedKind.Project
    val title =
        stringResource(
            if (project) R.string.owner_banner_project_title else R.string.owner_banner_yarn_title,
            banner.ownerName,
        )
    val detail =
        stringResource(
            if (project) R.string.owner_banner_project_detail else R.string.owner_banner_yarn_detail
        )
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier =
            modifier.fillMaxWidth().testTag("owner-banner").semantics(mergeDescendants = true) {},
    ) {
        // The owner's picture, whose item it is, and that changes are saved there and audited
        // (like the web's banner).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            AccountAvatar(
                url = LocalOwnerContext.current.resolveUrl(banner.avatarUrl),
                email = banner.ownerName,
                size = 36.dp,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/**
 * The banner under the top bar of a project or yarn that is not the user's own: "Shared by Anna ·
 * Can view" with the owner's avatar for a share, "Visible through a shared project" for a derived
 * yarn, the audit-log note for an administrator.
 */
@Composable
fun ItemBannerView(banner: ItemBanner, modifier: Modifier = Modifier) {
    when (banner) {
        is ItemBanner.Owned -> OwnerBanner(banner.banner, modifier)
        is ItemBanner.Derived ->
            BannerSurface(modifier) {
                Text(
                    stringResource(R.string.sharing_derived_banner),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        is ItemBanner.SharedBy ->
            BannerSurface(modifier) {
                val resolve = LocalOwnerContext.current.resolveUrl
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AccountAvatar(
                        url = resolve(banner.owner.avatarUrl),
                        email = banner.owner.name,
                        size = 24.dp,
                    )
                    Text(
                        stringResource(
                            R.string.sharing_shared_by_role,
                            banner.owner.name,
                            stringResource(
                                if (banner.role == ACCESS_EDITOR) R.string.sharing_role_editor
                                else R.string.sharing_role_viewer
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
    }
}

/** Neutral surface (not the blue accent, error or tertiary) for the sharing banners. */
@Composable
private fun BannerSurface(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().testTag("owner-banner"),
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { content() }
    }
}
