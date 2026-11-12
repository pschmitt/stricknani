package blue.anika.wolle.ui.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AuditSubjectDto
import blue.anika.wolle.ui.admin.auditActionPresentation
import blue.anika.wolle.ui.common.UserKind
import blue.anika.wolle.ui.common.UserLabelSize
import blue.anika.wolle.ui.common.UserRef
import blue.anika.wolle.ui.common.UserSentence
import blue.anika.wolle.ui.common.subjectRef

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

/**
 * A sharing action's sentence ("Shared with Anna · Can view") with its person as a [UserLabel];
 * [line] comes from [sharingEventLine]. Words like [sharingHeadlineText].
 */
@Composable
fun SharingHeadline(
    line: SharingEventLine,
    subject: AuditSubjectDto?,
    modifier: Modifier = Modifier,
    size: UserLabelSize = UserLabelSize.Inline,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
) {
    val role =
        stringResource(
            if (line.role == "editor") R.string.sharing_role_editor
            else R.string.sharing_role_viewer
        )
    UserSentence(
        line.textRes,
        subjectRef(subject) ?: UserRef(UserKind.Deleted),
        role,
        modifier = modifier,
        size = size,
        style = style,
        color = color,
    )
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
