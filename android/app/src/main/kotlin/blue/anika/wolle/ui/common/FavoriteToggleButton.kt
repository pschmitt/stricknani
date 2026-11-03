package blue.anika.wolle.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import blue.anika.wolle.R

/**
 * Heart toggle shared by the project and yarn lists. Marking a favourite gives a short pop and a
 * colour fade, so the tap visibly registered; unmarking only fades. Durations follow the system
 * animator scale, so "Remove animations" turns both off.
 */
@Composable
fun FavoriteToggleButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by
        animateColorAsState(
            targetValue =
                if (isFavorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            animationSpec = tween(durationMillis = 180),
            label = "favorite-tint",
        )
    val scale = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(isFavorite) }
    LaunchedEffect(isFavorite) {
        // Only a change made while the row is on screen pops; the first composition stays still.
        if (isFavorite && !previous) {
            scale.snapTo(0.7f)
            scale.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
        previous = isFavorite
    }
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription =
                stringResource(
                    if (isFavorite) R.string.common_unfavorite else R.string.common_favorite
                ),
            tint = tint,
            modifier =
                Modifier.graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
        )
    }
}
