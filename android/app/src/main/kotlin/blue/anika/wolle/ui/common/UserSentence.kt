package blue.anika.wolle.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/*
 * A translated sentence with people in it ("Owner: %1$s", "by %1$s as %2$s"): every [UserRef]
 * argument renders as a [UserLabel], everything else stays text. The web has the same
 * (`UserSentence.svelte`).
 */

/** A piece of a sentence: [text], or the [user] to show as a [UserLabel]. */
sealed interface SentencePart {
    data class Text(val text: String) : SentencePart

    data class Person(val user: UserRef) : SentencePart
}

private const val MARK = '\u0001'

/** The stand-in that [stringResource] formats in place of the person at [index]. */
internal fun userPlaceholder(index: Int): String = "$MARK$index$MARK"

/**
 * Splits a formatted sentence at the placeholders of [userPlaceholder] so each person can be shown
 * with avatar and name. A placeholder without a person stays out of the text.
 */
fun sentenceParts(formatted: String, users: List<UserRef>): List<SentencePart> {
    val parts = mutableListOf<SentencePart>()
    var rest = formatted
    while (rest.isNotEmpty()) {
        val start = rest.indexOf(MARK)
        val end = if (start < 0) -1 else rest.indexOf(MARK, start + 1)
        if (start < 0 || end < 0) {
            parts += SentencePart.Text(rest)
            break
        }
        if (start > 0) parts += SentencePart.Text(rest.substring(0, start))
        rest.substring(start + 1, end).toIntOrNull()?.let { index ->
            users.getOrNull(index)?.let { parts += SentencePart.Person(it) }
        }
        rest = rest.substring(end + 1)
    }
    return parts
}

/**
 * [format] is a string resource whose arguments are [args]; each [UserRef] among them renders as a
 * [UserLabel] of [size], any other argument is formatted into the text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserSentence(
    @StringRes format: Int,
    vararg args: Any,
    modifier: Modifier = Modifier,
    size: UserLabelSize = UserLabelSize.Inline,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
) {
    val users = args.filterIsInstance<UserRef>()
    var next = 0
    val formatArgs = args.map { if (it is UserRef) userPlaceholder(next++) else it }.toTypedArray()
    val parts = sentenceParts(stringResource(format, *formatArgs), users)
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEach { part ->
            when (part) {
                is SentencePart.Text -> Text(part.text, style = style, color = color)
                is SentencePart.Person ->
                    UserLabel(part.user, size = size, style = style, color = color)
            }
        }
    }
}
