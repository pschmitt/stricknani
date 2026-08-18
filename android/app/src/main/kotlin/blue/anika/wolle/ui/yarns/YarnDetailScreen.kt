package blue.anika.wolle.ui.yarns

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.data.api.dto.YarnDto
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YarnDetailScreen(
    onBack: () -> Unit,
    onProjectClick: (Int) -> Unit,
    viewModel: YarnDetailViewModel = hiltViewModel(),
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
                title = { Text(if (state is YarnDetailUiState.Loaded) state.entity.name else "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state is YarnDetailUiState.Loaded) {
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
            is YarnDetailUiState.Loading ->
                Box(
                    Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            is YarnDetailUiState.NotFound ->
                Box(
                    Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Yarn not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            is YarnDetailUiState.Loaded ->
                YarnDetailContent(
                    detail = state.detail,
                    linkedProjects = state.linkedProjects,
                    resolveMediaUrl = viewModel::resolveMediaUrl,
                    onProjectClick = onProjectClick,
                    modifier = Modifier.padding(innerPadding),
                )
        }
    }
}

@Composable
private fun YarnDetailContent(
    detail: YarnDto,
    linkedProjects: List<LinkedProject>,
    resolveMediaUrl: (String?) -> String?,
    onProjectClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        if (detail.photos.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(detail.photos, key = { it.id }) { photo ->
                        AsyncImage(
                            model = resolveMediaUrl(photo.url),
                            contentDescription = photo.altText,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(160.dp).clip(RoundedCornerShape(16.dp)),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        detail.brand?.let { value -> item { DetailRow(label = "Brand", value = value) } }
        detail.colorway?.let { value -> item { DetailRow(label = "Colorway", value = value) } }
        detail.dyeLot?.let { value -> item { DetailRow(label = "Dye lot", value = value) } }
        detail.fiberContent?.let { value ->
            item { DetailRow(label = "Fiber content", value = value) }
        }
        detail.weightCategory?.let { value ->
            item { DetailRow(label = "Weight category", value = value) }
        }
        detail.recommendedNeedles?.let { value ->
            item { DetailRow(label = "Recommended needles", value = value) }
        }
        if (detail.weightGrams != null || detail.lengthMeters != null) {
            item {
                val amount =
                    listOfNotNull(
                            detail.weightGrams?.let { "${it}g" },
                            detail.lengthMeters?.let { "${it}m" },
                        )
                        .joinToString(" / ")
                DetailRow(label = "Amount", value = amount)
            }
        }

        if (linkedProjects.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text("Used in", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }
            items(linkedProjects, key = { it.id }) { project ->
                Card(
                    onClick = { onProjectClick(project.id) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Text(project.name, modifier = Modifier.padding(12.dp))
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
