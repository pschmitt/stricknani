package blue.anika.wolle.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.ui.common.ImageViewerDialog
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.RefreshFeedbackEffect
import blue.anika.wolle.ui.common.shareUrl
import coil3.compose.AsyncImage
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    onBack: () -> Unit,
    onYarnClick: (Int) -> Unit,
    onEditClick: (Int) -> Unit,
    viewModel: ProjectDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshState by viewModel.refreshState.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    RefreshFeedbackEffect(refreshState, snackbarHostState, viewModel::dismissRefreshFeedback)

    val state = uiState
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state is ProjectDetailUiState.Loaded) state.entity.name else "")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    if (state is ProjectDetailUiState.Loaded) {
                        val context = LocalContext.current
                        var menuExpanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription =
                                    stringResource(
                                        R.string.project_detail_more_actions_description
                                    ),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (state.entity.isFavorite) R.string.common_unfavorite
                                            else R.string.common_favorite
                                        )
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector =
                                            if (state.entity.isFavorite) Icons.Filled.Favorite
                                            else Icons.Filled.FavoriteBorder,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.toggleFavorite()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_edit)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Edit, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onEditClick(state.entity.id)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_share)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Share, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.shareUrl()?.let { url -> context.shareUrl(url) }
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            when (state) {
                is ProjectDetailUiState.Loading ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().height(400.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                is ProjectDetailUiState.NotFound ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().height(400.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.project_detail_not_found),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                is ProjectDetailUiState.Loaded ->
                    ProjectDetailContent(
                        detail = state.detail,
                        linkedYarns = state.linkedYarns,
                        resolveMediaUrl = viewModel::resolveMediaUrl,
                        onYarnClick = onYarnClick,
                    )
            }
        }
    }
}

@Composable
private fun ProjectDetailContent(
    detail: ProjectDto,
    linkedYarns: List<LinkedYarn>,
    resolveMediaUrl: (String?) -> String?,
    onYarnClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    // map (not mapNotNull) - keeps indices aligned with detail.images/viewerIndex even if a
    // url somehow fails to resolve.
    val imageUrls = remember(detail.images) { detail.images.map { resolveMediaUrl(it.url) ?: "" } }

    val hasDetails =
        listOf(
                detail.category,
                detail.needles,
                detail.stitchSample,
                detail.yarn,
                detail.otherMaterials,
            )
            .any { it != null } || detail.tags.isNotEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (detail.images.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(detail.images, key = { _, image -> image.id }) { index, _ ->
                        AsyncImage(
                            // SNA-36: reuse the already-remembered imageUrls instead of
                            // recomputing resolveMediaUrl per item.
                            model = imageUrls[index],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier.size(160.dp).clip(RoundedCornerShape(16.dp)).clickable {
                                    viewerIndex = index
                                },
                        )
                    }
                }
            }
        }

        if (hasDetails) {
            item {
                DetailSectionCard {
                    detail.category?.let {
                        DetailRow(
                            label = stringResource(R.string.common_field_category),
                            value = it,
                        )
                    }
                    detail.needles?.let {
                        DetailRow(label = stringResource(R.string.common_field_needles), value = it)
                    }
                    detail.stitchSample?.let {
                        DetailRow(
                            label = stringResource(R.string.common_field_stitch_sample),
                            value = it,
                        )
                    }
                    detail.yarn?.let {
                        DetailRow(
                            label = stringResource(R.string.project_detail_field_yarn),
                            value = it,
                        )
                    }
                    detail.otherMaterials?.let {
                        DetailRow(
                            label = stringResource(R.string.common_field_other_materials),
                            value = it,
                        )
                    }
                    if (detail.tags.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            detail.tags.forEach { tag ->
                                AssistChip(onClick = {}, label = { Text(tag) })
                            }
                        }
                    }
                }
            }
        }

        if (linkedYarns.isNotEmpty()) {
            item {
                DetailSectionCard(
                    title = stringResource(R.string.project_detail_linked_yarns_title)
                ) {
                    linkedYarns.forEachIndexed { index, yarn ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        LinkedEntityRow(
                            name = yarn.name,
                            previewUrl = yarn.previewUrl?.let(resolveMediaUrl),
                            fallbackIcon = MdiIcons.Sheep,
                            onClick = { onYarnClick(yarn.id) },
                        )
                    }
                }
            }
        }

        detail.description?.let { value ->
            item {
                DetailSectionCard(
                    title = stringResource(R.string.project_detail_description_title)
                ) {
                    Markdown(content = value, imageTransformer = Coil3ImageTransformerImpl)
                }
            }
        }

        if (detail.steps.isNotEmpty()) {
            item {
                DetailSectionCard(title = stringResource(R.string.project_detail_steps_title)) {
                    // SNA-36: avoid re-sorting on every recomposition of this item scope.
                    val sortedSteps =
                        remember(detail.steps) { detail.steps.sortedBy { it.stepNumber } }
                    sortedSteps.forEachIndexed { index, step ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Column {
                            Text(
                                "${step.stepNumber}. ${step.title}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            step.description?.let {
                                Markdown(
                                    content = it,
                                    imageTransformer = Coil3ImageTransformerImpl,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        detail.notes?.let { value ->
            item {
                DetailSectionCard(title = stringResource(R.string.project_detail_notes_title)) {
                    Markdown(content = value, imageTransformer = Coil3ImageTransformerImpl)
                }
            }
        }
    }

    viewerIndex?.let { index ->
        ImageViewerDialog(
            imageUrls = imageUrls,
            initialIndex = index,
            onDismiss = { viewerIndex = null },
        )
    }
}

/** A rounded, elevated grouping card - matches Settings' `SettingsGroupCard` visual language. */
@Composable
internal fun DetailSectionCard(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            title?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            content()
        }
    }
}

/**
 * A tappable row with a thumbnail-or-fallback-icon leading avatar, used for linked-entity lists.
 */
@Composable
internal fun LinkedEntityRow(
    name: String,
    previewUrl: String?,
    fallbackIcon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier.size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (previewUrl != null) {
                AsyncImage(
                    model = previewUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        fallbackIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(name)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
