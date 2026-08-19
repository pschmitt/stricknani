package blue.anika.wolle.ui.yarns

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.primaryFirst
import blue.anika.wolle.data.settings.DetailCardDomain
import blue.anika.wolle.data.settings.DetailCardOrder
import blue.anika.wolle.data.settings.YarnDetailCard
import blue.anika.wolle.ui.common.DestructiveDeleteDialog
import blue.anika.wolle.ui.common.DestructiveDeleteIcon
import blue.anika.wolle.ui.common.DetailCardReorderHint
import blue.anika.wolle.ui.common.ImageViewerDialog
import blue.anika.wolle.ui.common.ImageViewerImage
import blue.anika.wolle.ui.common.MarkdownImageTransformer
import blue.anika.wolle.ui.common.NotesCard
import blue.anika.wolle.ui.common.ReorderableDetailCard
import blue.anika.wolle.ui.common.RefreshFeedbackEffect
import blue.anika.wolle.ui.common.rememberDetailCardReorderState
import blue.anika.wolle.ui.common.extractMarkdownImageReferences
import blue.anika.wolle.ui.common.normalizeMarkdownContent
import blue.anika.wolle.ui.common.shareUrl
import blue.anika.wolle.ui.theme.stricknaniMarkdownTypography
import coil3.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YarnDetailScreen(
    onBack: () -> Unit,
    onProjectClick: (Int) -> Unit,
    onEditClick: (Int) -> Unit,
    onDeleted: () -> Unit,
    viewModel: YarnDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshState by viewModel.refreshState.collectAsStateWithLifecycle()
    val cardOrder by
        viewModel.cardOrder.collectAsStateWithLifecycle(
            initialValue = DetailCardOrder.defaults(DetailCardDomain.YARN)
        )
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    LaunchedEffect(deleted) { if (deleted) onDeleted() }

    RefreshFeedbackEffect(refreshState, snackbarHostState, viewModel::dismissRefreshFeedback)

    val state = uiState
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (state is YarnDetailUiState.Loaded) state.entity.name else "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    if (state is YarnDetailUiState.Loaded) {
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
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_delete)) },
                                leadingIcon = { DestructiveDeleteIcon(contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showDeleteDialog = true
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
                is YarnDetailUiState.Loading ->
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
                is YarnDetailUiState.NotFound ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().height(400.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.yarn_detail_not_found),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                is YarnDetailUiState.Loaded ->
                    YarnDetailContent(
                        detail = state.detail,
                        linkedProjects = state.linkedProjects,
                        cardOrder = cardOrder,
                        onCardOrderChanged = viewModel::saveCardOrder,
                        resolveMediaUrl = viewModel::resolveMediaUrl,
                        onProjectClick = onProjectClick,
                    )
            }
        }
    }

    if (showDeleteDialog && state is YarnDetailUiState.Loaded) {
        DestructiveDeleteDialog(
            title = stringResource(R.string.yarn_editor_delete_dialog_title, state.entity.name),
            text = stringResource(R.string.project_editor_delete_dialog_text),
            onConfirm = {
                showDeleteDialog = false
                viewModel.delete()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
private fun YarnDetailContent(
    detail: YarnDto,
    linkedProjects: List<LinkedProject>,
    cardOrder: List<String>,
    onCardOrderChanged: (List<String>) -> Unit,
    resolveMediaUrl: (String?) -> String?,
    onProjectClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    val viewerImages = remember(detail, resolveMediaUrl) { yarnViewerImages(detail, resolveMediaUrl) }
    val openViewerImage: (String) -> Unit = { url ->
        viewerIndex = viewerImages.indexOfFirst { image -> image.url == url }.takeIf { it >= 0 }
    }
    val photos = remember(detail.photos) { detail.photos.primaryFirst() }
    val availableCardKeys =
        buildList {
            if (
                listOf(
                        detail.brand,
                        detail.colorway,
                        detail.dyeLot,
                        detail.fiberContent,
                        detail.weightCategory,
                        detail.recommendedNeedles,
                        detail.weightGrams,
                        detail.lengthMeters,
                    )
                    .any { it != null }
            ) {
                add(YarnDetailCard.DETAILS)
            }
            if (detail.description != null) add(YarnDetailCard.DESCRIPTION)
            if (linkedProjects.isNotEmpty()) add(YarnDetailCard.USED_IN)
            if (detail.notes != null) add(YarnDetailCard.NOTES)
        }
    val savedVisibleOrder =
        remember(cardOrder, availableCardKeys) {
            DetailCardOrder.visible(DetailCardDomain.YARN, cardOrder, availableCardKeys)
        }
    var orderedCardKeys by remember(savedVisibleOrder) { mutableStateOf(savedVisibleOrder) }
    LaunchedEffect(savedVisibleOrder) { orderedCardKeys = savedVisibleOrder }
    val reorderState = rememberDetailCardReorderState()
    val listState = remember { LazyListState() }
    val itemIndexOffset = (if (photos.isNotEmpty()) 1 else 0) + 1
    val moveCard: (Int, Int) -> Unit = { fromIndex, toIndex ->
        val next = DetailCardOrder.move(orderedCardKeys, fromIndex, toIndex)
        if (next != orderedCardKeys) {
            orderedCardKeys = next
            onCardOrderChanged(
                DetailCardOrder.withVisibleOrder(DetailCardDomain.YARN, cardOrder, next)
            )
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("e2e-yarn-detail"),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (photos.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(photos, key = { _, photo -> photo.id }) { _, photo ->
                        val url = resolveMediaUrl(photo.url)
                        AsyncImage(
                            // SNA-36: reuse the already-remembered photoUrls instead of
                            // recomputing resolveMediaUrl per item.
                            model = url,
                            contentDescription = photo.altText,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(160.dp).clip(RoundedCornerShape(16.dp)).clickable {
                                url?.let(openViewerImage)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        item { DetailCardReorderHint(reorderState, onDone = reorderState::finish) }

        items(orderedCardKeys, key = { it }) { cardKey ->
            val cardIndex = orderedCardKeys.indexOf(cardKey)
            when (cardKey) {
                YarnDetailCard.DETAILS ->
                    ReorderableDetailCard(
                        title = stringResource(R.string.yarn_detail_details_title),
                        cardKey = cardKey,
                        cardIndex = cardIndex,
                        listState = listState,
                        orderedKeys = orderedCardKeys,
                        itemIndexOffset = itemIndexOffset,
                        reorderState = reorderState,
                        onMove = moveCard,
                    ) {
                        detail.brand?.let {
                            DetailRow(stringResource(R.string.yarn_detail_field_brand), it)
                        }
                        detail.colorway?.let {
                            DetailRow(stringResource(R.string.yarn_detail_field_colorway), it)
                        }
                        detail.dyeLot?.let {
                            DetailRow(stringResource(R.string.yarn_detail_field_dye_lot), it)
                        }
                        detail.fiberContent?.let {
                            DetailRow(stringResource(R.string.yarn_detail_field_fiber_content), it)
                        }
                        detail.weightCategory?.let {
                            DetailRow(stringResource(R.string.yarn_detail_field_weight_category), it)
                        }
                        detail.recommendedNeedles?.let {
                            DetailRow(
                                stringResource(R.string.yarn_detail_field_recommended_needles),
                                it,
                            )
                        }
                        if (detail.weightGrams != null || detail.lengthMeters != null) {
                            val amount =
                                listOfNotNull(
                                        detail.weightGrams?.let { "${it}g" },
                                        detail.lengthMeters?.let { "${it}m" },
                                    )
                                    .joinToString(" / ")
                            DetailRow(stringResource(R.string.yarn_detail_field_amount), amount)
                        }
                    }
                YarnDetailCard.DESCRIPTION ->
                    ReorderableDetailCard(
                        title = stringResource(R.string.project_detail_description_title),
                        cardKey = cardKey,
                        cardIndex = cardIndex,
                        listState = listState,
                        orderedKeys = orderedCardKeys,
                        itemIndexOffset = itemIndexOffset,
                        reorderState = reorderState,
                        onMove = moveCard,
                    ) {
                        YarnMarkdown(
                            value = detail.description.orEmpty(),
                            resolveMediaUrl = resolveMediaUrl,
                            onImageClick = openViewerImage,
                        )
                    }
                YarnDetailCard.USED_IN ->
                    ReorderableDetailCard(
                        title = stringResource(R.string.yarn_detail_used_in_title),
                        cardKey = cardKey,
                        cardIndex = cardIndex,
                        listState = listState,
                        orderedKeys = orderedCardKeys,
                        itemIndexOffset = itemIndexOffset,
                        reorderState = reorderState,
                        onMove = moveCard,
                    ) {
                        linkedProjects.forEachIndexed { index, project ->
                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Card(onClick = { onProjectClick(project.id) }) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(
                                        modifier =
                                            Modifier.size(48.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        if (project.previewUrl != null) {
                                            AsyncImage(
                                                model = resolveMediaUrl(project.previewUrl),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Filled.Folder,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                    Text(project.name)
                                }
                            }
                        }
                    }
                YarnDetailCard.NOTES ->
                    ReorderableDetailCard(
                        title = stringResource(R.string.project_detail_notes_title),
                        cardKey = cardKey,
                        cardIndex = cardIndex,
                        listState = listState,
                        orderedKeys = orderedCardKeys,
                        itemIndexOffset = itemIndexOffset,
                        reorderState = reorderState,
                        onMove = moveCard,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ) {
                        YarnMarkdown(
                            value = detail.notes.orEmpty(),
                            resolveMediaUrl = resolveMediaUrl,
                            onImageClick = openViewerImage,
                        )
                    }
            }
        }
    }

    viewerIndex?.let { index ->
        ImageViewerDialog(
            images = viewerImages,
            initialIndex = index,
            onDismiss = { viewerIndex = null },
        )
    }
}

@Composable
private fun YarnMarkdown(
    value: String,
    resolveMediaUrl: (String?) -> String?,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = remember(value) { normalizeMarkdownContent(value) }
    val references = remember(content) { extractMarkdownImageReferences(content) }
    val imageTransformer =
        remember(resolveMediaUrl, references, onImageClick) {
            MarkdownImageTransformer(
                resolveUrl = resolveMediaUrl,
                onImageClick = onImageClick,
                imageReferences = references,
            )
        }
    Markdown(
        content = content,
        typography = stricknaniMarkdownTypography(),
        imageTransformer = imageTransformer,
        modifier = modifier,
    )
}

private fun yarnViewerImages(
    detail: YarnDto,
    resolveMediaUrl: (String?) -> String?,
): List<ImageViewerImage> {
    val images = linkedMapOf<String, ImageViewerImage>()
    detail.photos.forEach { photo ->
        resolveMediaUrl(photo.url)?.let { url ->
            images.putIfAbsent(
                url,
                ImageViewerImage(
                    url = url,
                    title = detail.name,
                    sourceLabel = if (photo.isPrimary) "Primary yarn photo" else "Yarn photo",
                    altText = photo.altText,
                ),
            )
        }
    }
    val markdownContents =
        buildList {
            detail.description?.let { add("Yarn description" to it) }
            detail.notes?.let { add("Notes" to it) }
        }
    markdownContents.forEach { (context, content) ->
        extractMarkdownImageReferences(normalizeMarkdownContent(content)).forEach { reference ->
            resolveMediaUrl(reference.source)?.let { url ->
                images.putIfAbsent(
                    url,
                    ImageViewerImage(
                        url = url,
                        title = detail.name,
                        sourceLabel = context,
                        altText = reference.altText,
                    ),
                )
            }
        }
    }
    return images.values.toList()
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
