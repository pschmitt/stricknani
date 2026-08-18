package blue.anika.wolle.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.ui.common.ImageViewerDialog
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    onBack: () -> Unit,
    onYarnClick: (Int) -> Unit,
    onEditClick: (Int) -> Unit,
    viewModel: ProjectDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state is ProjectDetailUiState.Loaded) {
                        IconButton(onClick = { onEditClick(state.entity.id) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit project")
                        }
                        IconButton(onClick = viewModel::toggleFavorite) {
                            Icon(
                                imageVector =
                                    if (state.entity.isFavorite) Icons.Filled.Favorite
                                    else Icons.Filled.FavoriteBorder,
                                contentDescription = "Toggle favorite",
                                tint =
                                    if (state.entity.isFavorite) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when (state) {
            is ProjectDetailUiState.Loading ->
                Box(
                    Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            is ProjectDetailUiState.NotFound ->
                Box(
                    Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Project not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            is ProjectDetailUiState.Loaded ->
                ProjectDetailContent(
                    detail = state.detail,
                    linkedYarns = state.linkedYarns,
                    resolveMediaUrl = viewModel::resolveMediaUrl,
                    onYarnClick = onYarnClick,
                    modifier = Modifier.padding(innerPadding),
                )
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

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        if (detail.images.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(detail.images, key = { _, image -> image.id }) { index, image ->
                        AsyncImage(
                            model = resolveMediaUrl(image.url),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier.size(160.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { viewerIndex = index },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        detail.category?.let { category ->
            item { DetailRow(label = "Category", value = category) }
        }
        detail.needles?.let { value -> item { DetailRow(label = "Needles", value = value) } }
        detail.stitchSample?.let { value ->
            item { DetailRow(label = "Stitch sample", value = value) }
        }
        detail.yarn?.let { value -> item { DetailRow(label = "Yarn", value = value) } }
        detail.otherMaterials?.let { value ->
            item { DetailRow(label = "Other materials", value = value) }
        }

        if (detail.tags.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    detail.tags.forEach { tag -> AssistChip(onClick = {}, label = { Text(tag) }) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        if (linkedYarns.isNotEmpty()) {
            item {
                Text("Linked yarns", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }
            items(linkedYarns, key = { it.id }) { yarn ->
                Card(
                    onClick = { onYarnClick(yarn.id) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Text(yarn.name, modifier = Modifier.padding(12.dp))
                }
            }
        }

        detail.description?.let { value ->
            item {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text("Description", style = MaterialTheme.typography.titleMedium)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (detail.steps.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text("Steps", style = MaterialTheme.typography.titleMedium)
            }
            items(detail.steps.sortedBy { it.stepNumber }, key = { it.id }) { step ->
                Column(Modifier.padding(top = 12.dp)) {
                    Text(
                        "${step.stepNumber}. ${step.title}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    step.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        detail.notes?.let { value ->
            item {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text("Notes", style = MaterialTheme.typography.titleMedium)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
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
