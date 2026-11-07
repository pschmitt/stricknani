package blue.anika.wolle.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * The project or yarn name as the first item of a detail screen: large and wrapping, so a long name
 * is never cut off like in the narrow top bar (like the web's page heading).
 */
@Composable
fun DetailTitle(name: String, modifier: Modifier = Modifier) {
    Text(
        text = name,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * One-line, ellipsized title for the top bar that fades in once the [DetailTitle] (item 0 of
 * [listState]) has scrolled out of view. Hidden from accessibility: the heading in the content
 * already carries the name.
 */
@Composable
fun ScrolledBarTitle(name: String, listState: LazyListState) {
    val titleScrolledAway by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val instant = animationsDisabled()
    AnimatedVisibility(
        visible = titleScrolledAway && name.isNotEmpty(),
        enter = if (instant) EnterTransition.None else fadeIn(),
        exit = if (instant) ExitTransition.None else fadeOut(),
    ) {
        Text(
            text = name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}
