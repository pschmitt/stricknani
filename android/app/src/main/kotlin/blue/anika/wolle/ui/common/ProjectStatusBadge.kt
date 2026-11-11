package blue.anika.wolle.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.ProjectStatus

/** The label of a project status, as on the web ("In progress", "Done"). */
@StringRes
fun projectStatusLabel(status: String): Int =
    if (status == ProjectStatus.DONE) R.string.project_status_done
    else R.string.project_status_in_progress

/** The status values in the order the editor and the list filter offer them. */
val PROJECT_STATUSES: List<String> = listOf(ProjectStatus.IN_PROGRESS, ProjectStatus.DONE)

/**
 * The small status pill of a project: a check and "Done", or plain "In progress". Library cards show
 * it for done projects only; the detail page shows both. Same rule on the web (`StatusBadge.svelte`).
 */
@Composable
fun ProjectStatusBadge(status: String, modifier: Modifier = Modifier) {
    val done = status == ProjectStatus.DONE
    val container =
        if (done) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh
    val content =
        if (done) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .testTag("project-status-badge")
                .background(container, RoundedCornerShape(percent = 50))
                .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        if (done) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            stringResource(projectStatusLabel(status)),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
        )
    }
}
