package blue.anika.wolle.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.util.DateTimeUtils
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.history.joinNatural
import coil3.compose.AsyncImage

/**
 * Home's "Recent activity": a compact list of the newest edits to the user's projects and yarns.
 * Online-only, so it degrades to a one-line note without a connection and is not shown at all when
 * the server has no such feed.
 */
@Composable
fun ActivitySection(
    state: ActivityUiState,
    previewUrl: (String?) -> String?,
    onProjectClick: (Int) -> Unit,
    onYarnClick: (Int) -> Unit,
    onShowMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.phase == ActivityPhase.Hidden) return
    Column(modifier) {
        SectionTitle(
            title = stringResource(R.string.activity_title),
            icon = PhosphorIcons.ClockCounterClockwise,
            accent = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        )
        Card(
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
        ) {
            when {
                state.phase == ActivityPhase.Unavailable ->
                    Note(stringResource(R.string.activity_unavailable))
                state.phase == ActivityPhase.Ready && state.items.isEmpty() ->
                    Note(stringResource(R.string.activity_empty))
                state.phase == ActivityPhase.Ready ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        state.items.forEach { item ->
                            val row = remember(item) { activityRow(item) }
                            ActivityRow(
                                row = row,
                                previewUrl = previewUrl(row.thumbnailUrl),
                                onClick = {
                                    when (val target = row.target) {
                                        is ActivityTarget.Project -> onProjectClick(target.id)
                                        is ActivityTarget.Yarn -> onYarnClick(target.id)
                                        null -> Unit
                                    }
                                },
                            )
                        }
                        if (state.moreFailed) {
                            Text(
                                stringResource(R.string.activity_more_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        if (state.hasMore) {
                            TextButton(
                                onClick = onShowMore,
                                enabled = !state.loadingMore,
                                modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp),
                            ) {
                                Text(
                                    stringResource(
                                        if (state.loadingMore) R.string.history_loading
                                        else R.string.activity_show_more
                                    )
                                )
                            }
                        }
                    }
                else -> SkeletonRows()
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

/** Placeholder rows while the first page loads. */
@Composable
private fun SkeletonRows() {
    val block = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Column(Modifier.padding(vertical = 8.dp)) {
        repeat(3) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(block))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.fillMaxWidth(0.7f)
                            .size(width = 200.dp, height = 14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(block)
                    )
                    Box(
                        Modifier.size(width = 90.dp, height = 10.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(block)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(row: ActivityRowModel, previewUrl: String?, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val sentence = activitySentence(row)
    val changes =
        if (row.count > 1)
            pluralStringResource(R.plurals.activity_changes_count, row.count, row.count)
        else null
    val headline = buildAnnotatedString {
        append(sentence.text)
        if (changes != null) {
            withStyle(SpanStyle(color = muted)) { append(" · $changes") }
        }
    }
    val fields = fieldsText(row)
    val time = relativeTime(row.createdAt)
    val deletedLabel = stringResource(R.string.activity_deleted_label)
    val meta = if (row.deleted) "$time · $deletedLabel" else time
    val tappable = !row.deleted
    val alpha = if (row.deleted) 0.6f else 1f
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (tappable) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ActivityThumb(
            previewUrl = previewUrl,
            fallback = if (row.isYarn) MdiIcons.YarnBall else PhosphorIcons.Hoodie,
            dimmed = row.deleted,
        )
        Column(Modifier.weight(1f)) {
            Text(
                headline,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (fields != null) {
                Text(
                    fields,
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(meta, style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

@Composable
private fun ActivityThumb(previewUrl: String?, fallback: ImageVector, dimmed: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.secondaryContainer.copy(alpha = if (dimmed) 0.5f else 1f)),
        contentAlignment = Alignment.Center,
    ) {
        if (previewUrl != null) {
            AsyncImage(
                model = previewUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (dimmed) 0.5f else 1f,
                modifier = Modifier.fillMaxWidth().size(44.dp),
            )
        } else {
            Icon(
                fallback,
                contentDescription = null,
                tint = colors.onSecondaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** "You updated **Winter socks**": the sentence with the resource name in bold. */
@Composable
private fun activitySentence(row: ActivityRowModel): AnnotatedString {
    val actor =
        when {
            row.actorIsSelf -> stringResource(R.string.activity_actor_you)
            row.actorName != null -> stringResource(R.string.activity_actor_named, row.actorName)
            else -> stringResource(R.string.activity_actor_someone)
        }
    val name =
        row.entityName
            ?: stringResource(
                if (row.isYarn) R.string.activity_unnamed_yarn
                else R.string.activity_unnamed_project
            )
    val template =
        when (row.verb) {
            ActivityVerb.Created -> R.string.activity_created
            ActivityVerb.Updated -> R.string.activity_updated
            ActivityVerb.Deleted -> R.string.activity_deleted
            ActivityVerb.Uploaded -> R.string.activity_uploaded
            ActivityVerb.Removed -> R.string.activity_removed
            ActivityVerb.Changed -> R.string.activity_changed
        }
    val text = stringResource(template, actor, name)
    val range = nameRange(text, name)
    return buildAnnotatedString {
        append(text)
        if (range != null) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), range.first, range.last + 1)
        }
    }
}

/** "Changed name and needles", or null when no changed field is known. */
@Composable
private fun fieldsText(row: ActivityRowModel): String? {
    if (row.fieldNounRes.isEmpty()) return null
    val nouns = row.fieldNounRes.map { stringResource(it) }
    val shown =
        if (row.moreFields > 0) nouns + stringResource(R.string.history_n_more, row.moreFields)
        else nouns
    return stringResource(
        R.string.history_changed_fields,
        joinNatural(shown, stringResource(R.string.history_list_and)),
    )
}

@Composable
private fun relativeTime(createdAt: String): String {
    val millis = runCatching { DateTimeUtils.parseToEpochMillis(createdAt) }.getOrNull()
    return if (millis == null) createdAt
    else
        DateUtils.getRelativeTimeSpanString(
                millis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            )
            .toString()
}
