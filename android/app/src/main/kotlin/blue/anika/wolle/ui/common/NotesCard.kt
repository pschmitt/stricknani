package blue.anika.wolle.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private val NotesLightContainer = Color(0xFFFFF8E1)
private val NotesLightContent = Color(0xFF211A00)
private val NotesDarkContainer = Color(0xFF4A3F1B)
private val NotesDarkContent = Color(0xFFFFF8E1)

/** A warm, semantic Material 3 container for user-authored notes. */
@Composable
internal fun NotesCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    Card(
        modifier = modifier.fillMaxWidth().testTag("notes-card"),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                // Keep the note cue yellow even when Android's dynamic palette chooses a
                // different tertiary hue. These are paired container/content tones with ample
                // contrast in both appearances, following M3's semantic container pattern.
                containerColor = if (isLight) NotesLightContainer else NotesDarkContainer,
                contentColor = if (isLight) NotesLightContent else NotesDarkContent,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
