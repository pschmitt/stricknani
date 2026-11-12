package blue.anika.wolle.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AuditSubjectDto
import blue.anika.wolle.ui.admin.auditActionPresentation

/*
 * How an audit action reads, shared by the project/yarn history and Home's activity so both agree
 * ("Timer started", "Restored from trash", "Shared with Anna - Can view").
 */

/** The label of an action; [system] marks the automatic purge. */
@Composable
fun actionLabelText(action: String, system: Boolean = false): String {
    val presentation = auditActionPresentation(action, system)
    return presentation.labelRes?.let { stringResource(it) } ?: presentation.fallbackLabel
}

/** The sentence of a sharing action with its person, or null for any other action. */
@Composable
fun sharingHeadlineText(action: String, subject: AuditSubjectDto?): String? {
    val line = sharingEventLine(action, subject) ?: return null
    val person = line.name ?: stringResource(R.string.history_deleted_account)
    val role =
        stringResource(
            if (line.role == "editor") R.string.sharing_role_editor
            else R.string.sharing_role_viewer
        )
    return stringResource(line.textRes, person, role)
}
