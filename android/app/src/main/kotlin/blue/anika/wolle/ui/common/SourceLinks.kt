package blue.anika.wolle.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * One external source link of a project or yarn (the original page or its archived copy), like the
 * web's source links: a round icon, an underlined label and "↗", opening the browser. Only http(s)
 * links are shown; anything else is not a link we open.
 */
@Composable
fun SourceLinkRow(
    icon: ImageVector,
    label: String,
    url: String,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = label) {
                    runCatching { uriHandler.openUri(url) }
                }
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        Text(
            "$label ↗",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        )
        if (trailing != null) {
            // The rest of the line: status and action, after the label.
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = trailing,
            )
        }
    }
}

/**
 * The source rows of a project's or yarn's details card (like the web): the original link, the
 * archived copy when there is one, and the owner's archive action. Nothing is shown without a link.
 */
@Composable
fun ColumnScope.SourceRows(
    entity: ArchiveEntity,
    id: Int,
    link: String?,
    linkArchive: String?,
    openLabel: String,
    archiveLabel: String,
    canArchive: Boolean,
    onArchived: () -> Unit,
) {
    val original = safeExternalUrl(link)
    if (original != null) {
        SourceLinkRow(PhosphorIcons.Notebook, openLabel, original)
    }
    val archived = safeExternalUrl(linkArchive)
    if (canArchive && original != null) {
        // The control shows the archived copy itself, with its action on the same line.
        ArchiveControl(
            entity,
            id,
            original,
            onArchived,
            archivedUrl = archived,
            archiveLabel = archiveLabel,
        )
    } else if (archived != null) {
        SourceLinkRow(PhosphorIcons.ClockCounterClockwise, archiveLabel, archived)
    }
}

/** The link if it is an absolute http(s) URL, else null (mirrors the web's safeExternalUrl). */
fun safeExternalUrl(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
    return trimmed.takeIf { (scheme == "http" || scheme == "https") && trimmed.length > 8 }
}
