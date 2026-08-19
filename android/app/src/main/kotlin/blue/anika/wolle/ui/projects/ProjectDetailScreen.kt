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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.AttachmentDto
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.ui.common.DestructiveDeleteDialog
import blue.anika.wolle.ui.common.DestructiveDeleteIcon
import blue.anika.wolle.ui.common.ImageViewerDialog
import blue.anika.wolle.ui.common.ImageViewerImage
import blue.anika.wolle.ui.common.MarkdownImageTransformer
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.NotesCard
import blue.anika.wolle.ui.common.RefreshFeedbackEffect
import blue.anika.wolle.ui.common.extractMarkdownImageReferences
import blue.anika.wolle.ui.common.normalizeMarkdownContent
import blue.anika.wolle.ui.common.shareUrl
import blue.anika.wolle.ui.theme.stricknaniMarkdownTypography
import coil3.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    onBack: () -> Unit,
    onYarnClick: (Int) -> Unit,
    onEditClick: (Int) -> Unit,
    onDeleted: () -> Unit,
    viewModel: ProjectDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshState by viewModel.refreshState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var attachmentToDelete by remember { mutableStateOf<AttachmentDto?>(null) }

    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    LaunchedEffect(deleted) { if (deleted) onDeleted() }

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
                        onDeleteAttachment = { attachmentToDelete = it },
                    )
            }
        }
    }

    if (showDeleteDialog && state is ProjectDetailUiState.Loaded) {
        DestructiveDeleteDialog(
            title = stringResource(R.string.project_editor_delete_dialog_title, state.entity.name),
            text = stringResource(R.string.project_editor_delete_dialog_text),
            onConfirm = {
                showDeleteDialog = false
                viewModel.delete()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    attachmentToDelete?.let { attachment ->
        DestructiveDeleteDialog(
            title =
                stringResource(
                    R.string.project_attachment_delete_dialog_title,
                    attachment.originalFilename,
                ),
            text = stringResource(R.string.project_attachment_delete_dialog_text),
            onConfirm = {
                attachmentToDelete = null
                viewModel.deleteAttachment(attachment.id)
            },
            onDismiss = { attachmentToDelete = null },
        )
    }
}

@Composable
private fun ProjectDetailContent(
    detail: ProjectDto,
    linkedYarns: List<LinkedYarn>,
    resolveMediaUrl: (String?) -> String?,
    onYarnClick: (Int) -> Unit,
    onDeleteAttachment: (AttachmentDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    val viewerImages = remember(detail, resolveMediaUrl) {
        projectViewerImages(detail, resolveMediaUrl)
    }
    val openViewerImage: (String) -> Unit = { url ->
        viewerIndex = viewerImages.indexOfFirst { image -> image.url == url }.takeIf { it >= 0 }
    }
    val stitchSampleImages =
        remember(detail.images) { detail.images.filter { it.isStitchSample && it.stepId == null } }

    val hasDetails =
        listOf(
                detail.category,
                detail.needles,
                detail.yarn,
                detail.otherMaterials,
            )
            .any { it != null } || detail.tags.isNotEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("e2e-project-detail"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (detail.images.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(detail.images, key = { _, image -> image.id }) { _, image ->
                        val url = resolveMediaUrl(image.url)
                        AsyncImage(
                            // SNA-36: reuse the already-remembered imageUrls instead of
                            // recomputing resolveMediaUrl per item.
                            model = url,
                            contentDescription = image.altText,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(160.dp).clip(RoundedCornerShape(16.dp)).clickable {
                                url?.let(openViewerImage)
                            },
                        )
                    }
                }
            }
        }

        if (detail.attachments.isNotEmpty()) {
            item {
                DetailSectionCard(
                    title = stringResource(R.string.project_detail_attachments_title)
                ) {
                    detail.attachments.forEachIndexed { index, attachment ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    attachment.originalFilename,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    attachment.contentType,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onDeleteAttachment(attachment) }) {
                                DestructiveDeleteIcon(
                                    contentDescription =
                                        stringResource(
                                            R.string.project_detail_delete_attachment_description
                                        )
                                )
                            }
                        }
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

        detail.stitchSample?.let { value ->
            item {
                DetailSectionCard(title = stringResource(R.string.common_field_stitch_sample)) {
                    ProjectMarkdown(
                        value = value,
                        resolveMediaUrl = resolveMediaUrl,
                        onImageClick = openViewerImage,
                    )
                    if (stitchSampleImages.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(
                                stitchSampleImages,
                                key = { _, image -> image.id },
                            ) { _, image ->
                                val imageIndex = detail.images.indexOfFirst { it.id == image.id }
                                AsyncImage(
                                    model = resolveMediaUrl(image.url),
                                    contentDescription = image.altText,
                                    contentScale = ContentScale.Crop,
                                    modifier =
                                        Modifier.size(160.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable {
                                        if (imageIndex >= 0) {
                                            resolveMediaUrl(image.url)?.let(openViewerImage)
                                        }
                                            },
                                )
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
                    ProjectMarkdown(
                        value = value,
                        resolveMediaUrl = resolveMediaUrl,
                        onImageClick = openViewerImage,
                    )
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
                                ProjectMarkdown(
                                    value = it,
                                    resolveMediaUrl = resolveMediaUrl,
                                    onImageClick = openViewerImage,
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
                NotesCard(title = stringResource(R.string.project_detail_notes_title)) {
                    ProjectMarkdown(
                        value = value,
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
private fun ProjectMarkdown(
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

private fun projectViewerImages(
    detail: ProjectDto,
    resolveMediaUrl: (String?) -> String?,
): List<ImageViewerImage> {
    val images = linkedMapOf<String, ImageViewerImage>()
    val stepsById = detail.steps.associateBy { it.id }
    detail.images.forEach { image ->
        val sourceLabel =
            when {
                image.isTitleImage -> "Project title image"
                image.isStitchSample && image.stepId == null -> "Stitch sample"
                image.stepId != null ->
                    stepsById[image.stepId]?.let { "Step ${it.stepNumber}: ${it.title}" }
                        ?: "Project step image"
                else -> "Project image"
            }
        resolveMediaUrl(image.url)?.let { url ->
            images.putIfAbsent(
                url,
                ImageViewerImage(
                    url = url,
                    title = detail.name,
                    sourceLabel = sourceLabel,
                    altText = image.altText,
                ),
            )
        }
    }
    val markdownContexts =
        buildList {
            detail.stitchSample?.let { add("Stitch sample" to it) }
            detail.description?.let { add("Project description" to it) }
            detail.steps.forEach { step ->
                step.description?.let { add("Step ${step.stepNumber}: ${step.title}" to it) }
            }
            detail.notes?.let { add("Notes" to it) }
        }
    markdownContexts.forEach { (context, content) ->
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
