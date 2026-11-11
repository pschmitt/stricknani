package blue.anika.wolle.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.ProjectStatus
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** True when the window is wide enough (tablet, unfolded) for the card grid. */
@Composable
fun isWideLibraryLayout(): Boolean =
    LocalConfiguration.current.screenWidthDp >= LIBRARY_GRID_MIN_WIDTH_DP

/**
 * The list body shared by Projects and Yarns: a card grid on wide windows, compact rows on phones.
 * Both lists are hoisted by the caller so the extended FAB can follow their scroll position.
 */
@Composable
fun <T> LibraryCollection(
    items: List<T>,
    key: (T) -> Any,
    toCard: @Composable (T) -> LibraryCardData,
    onClick: (T) -> Unit,
    onFavoriteClick: (T) -> Unit,
    listState: LazyListState,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    if (isWideLibraryLayout()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(260.dp),
            state = gridState,
            modifier = modifier,
            contentPadding = PaddingValues(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            gridItems(items, key = key) { item ->
                LibraryCard(
                    data = toCard(item),
                    onClick = { onClick(item) },
                    onFavoriteClick = { onFavoriteClick(item) },
                )
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = key) { item ->
                LibraryRow(
                    data = toCard(item),
                    onClick = { onClick(item) },
                    onFavoriteClick = { onFavoriteClick(item) },
                )
            }
        }
    }
}

/** Wide-screen card: 4:3 cover, heart on top, title, meta, updated date and chips. */
@Composable
fun LibraryCard(
    data: LibraryCardData,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val still = animationsDisabled()
    val lift by
        animateFloatAsState(
            if (pressed && !still) 2f else 0f,
            tween(120),
            label = "library-card-lift",
        )
    val zoom by
        animateFloatAsState(
            if (pressed && !still) 1.03f else 1f,
            tween(200),
            label = "library-card-zoom",
        )
    Card(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border =
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier = modifier.fillMaxWidth().graphicsLayer { translationY = -lift.dp.toPx() },
    ) {
        Box {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RectTop)) {
                LibraryCover(
                    data = data,
                    iconSize = 56.dp,
                    modifier =
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                        },
                )
            }
            data.owner?.let { owner ->
                OwnerBadge(owner, modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
            }
            if (data.favoriteToggleable) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)),
                    contentAlignment = Alignment.Center,
                ) {
                    FavoriteToggleButton(
                        isFavorite = data.isFavorite,
                        onClick = onFavoriteClick,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = data.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            data.meta?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = updatedLabel(data.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            data.trashDaysLeft?.let { TrashBadge(it, Modifier.padding(top = 2.dp)) }
            if (data.status == ProjectStatus.DONE) {
                ProjectStatusBadge(ProjectStatus.DONE, Modifier.padding(top = 2.dp))
            }
            if (data.chips.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    data.chips.forEach { LibraryChip(it) }
                }
            }
        }
    }
}

/** Phone row: 112dp 4:3 thumbnail, a title of up to two lines, meta, updated date and the heart. */
@Composable
fun LibraryRow(
    data: LibraryCardData,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border =
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 112.dp, height = 84.dp)) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))) {
                    LibraryCover(data = data, iconSize = 36.dp, modifier = Modifier.fillMaxSize())
                }
                data.owner?.let { owner ->
                    OwnerBadge(
                        owner,
                        size = 24.dp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = data.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                data.meta?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = updatedLabel(data.updatedAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                data.trashDaysLeft?.let { TrashBadge(it, Modifier.padding(top = 4.dp)) }
                if (data.status == ProjectStatus.DONE) {
                    ProjectStatusBadge(ProjectStatus.DONE, Modifier.padding(top = 4.dp))
                }
            }
            if (data.favoriteToggleable) {
                FavoriteToggleButton(isFavorite = data.isFavorite, onClick = onFavoriteClick)
            }
        }
    }
}

private val RectTop = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

/** The photo, or a soft gradient in the domain tone with a large domain icon. */
@Composable
private fun LibraryCover(
    data: LibraryCardData,
    iconSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
) {
    val tones = domainTones(data.domain)
    Box(modifier.background(Brush.linearGradient(listOf(tones.first, tones.second)))) {
        if (data.imageUrl != null) {
            AsyncImage(
                model = data.imageUrl,
                contentDescription = data.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector =
                    if (data.domain == LibraryDomain.Project) PhosphorIcons.Hoodie
                    else MdiIcons.YarnBall,
                contentDescription = null,
                tint = tones.third,
                modifier = Modifier.align(Alignment.Center).size(iconSize),
            )
        }
    }
}

/** Gradient start, gradient end and icon colour: warm tan for projects, teal for yarns. */
@Composable
private fun domainTones(domain: LibraryDomain): Triple<Color, Color, Color> {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return when (domain) {
        LibraryDomain.Project ->
            if (dark) Triple(Color(0xFF4A3A26), Color(0xFF30261A), Color(0xFFD9B77E))
            else Triple(Color(0xFFF3DDB5), Color(0xFFE6C48C), Color(0xFF8A6428))
        LibraryDomain.Yarn ->
            if (dark) Triple(Color(0xFF1F4443), Color(0xFF16302F), Color(0xFF7CC4BF))
            else Triple(Color(0xFFC6E8E4), Color(0xFF9CD3CD), Color(0xFF1F6F69))
    }
}

@Composable
private fun LibraryChip(label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun updatedLabel(millis: Long): String =
    stringResource(R.string.library_card_updated, formatUpdatedDate(millis))

internal fun formatUpdatedDate(millis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** "In trash · 12 days left": the small badge of a trashed item in a library list. */
@Composable
private fun TrashBadge(daysLeft: Int, modifier: Modifier = Modifier) {
    Text(
        text = pluralStringResource(R.plurals.library_card_in_trash, daysLeft, daysLeft),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .testTag("trash-badge")
                .background(
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    RoundedCornerShape(percent = 50),
                )
                .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}
