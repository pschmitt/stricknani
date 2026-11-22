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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R

@Composable
fun WashingInstructionsCard(value: String, onValueChange: ((String) -> Unit)? = null) {
    val label = stringResource(R.string.washing_instructions)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.secondaryContainer.copy(alpha = 0.28f),
        contentColor = colors.onSurface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, colors.secondary.copy(alpha = 0.22f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(shape = CircleShape, color = colors.secondaryContainer) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            PhosphorIcons.Laundry,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = colors.onSecondaryContainer,
                        )
                    }
                }
                Text(
                    label,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (onValueChange == null) {
                Text(value, style = MaterialTheme.typography.bodyLarge)
            } else {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                    minLines = 3,
                )
            }
        }
    }
}
