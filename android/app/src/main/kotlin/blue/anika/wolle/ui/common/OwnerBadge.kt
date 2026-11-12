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
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.ACCESS_EDITOR

/**
 * Who owns a card's project or yarn: the shared [UserLabel] (avatar + name) on a small pill over
 * the card's picture; "Owned by Anika" for accessibility. The name shortens before the pill
 * outgrows the picture.
 */
@Composable
fun OwnerBadge(owner: OwnerBadgeData, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.owner_badge_description, owner.name)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.semantics { contentDescription = description }.testTag("owner-badge"),
    ) {
        UserLabel(
            ownerRef(owner),
            size = UserLabelSize.Inline,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 3.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
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
    val titleRes =
        if (project) R.string.owner_banner_project_title else R.string.owner_banner_yarn_title
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                UserSentence(
                    titleRes,
                    UserRef(UserKind.User, banner.ownerName, banner.avatarUrl),
                    size = UserLabelSize.Regular,
                    style = MaterialTheme.typography.titleSmall,
                )
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
                UserSentence(
                    R.string.sharing_shared_by_role,
                    UserRef(UserKind.User, banner.owner.name, banner.owner.avatarUrl),
                    stringResource(
                        if (banner.role == ACCESS_EDITOR) R.string.sharing_role_editor
                        else R.string.sharing_role_viewer
                    ),
                    size = UserLabelSize.Compact,
                    style = MaterialTheme.typography.bodySmall,
                )
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
