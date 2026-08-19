package blue.anika.wolle.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.EmptyState
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.SearchField
import coil3.compose.AsyncImage

@Composable
fun SearchScreen(
    onProjectClick: (Int) -> Unit,
    onYarnClick: (Int) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()

    Scaffold { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            SearchField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                placeholder = stringResource(R.string.search_placeholder),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )

            when {
                query.isBlank() ->
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = stringResource(R.string.search_empty_title),
                        subtitle = stringResource(R.string.search_empty_subtitle),
                    )
                results.isEmpty() ->
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = stringResource(R.string.search_no_matches_title),
                        subtitle = stringResource(R.string.search_no_matches_subtitle),
                    )
                else ->
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(
                            results,
                            key = {
                                when (it) {
                                    is SearchResult.ProjectResult -> "p-${it.entity.id}"
                                    is SearchResult.YarnResult -> "y-${it.entity.id}"
                                }
                            },
                        ) { result ->
                            when (result) {
                                is SearchResult.ProjectResult ->
                                    SearchResultCard(
                                        title = result.entity.name,
                                        subtitle = result.entity.category,
                                        previewUrl = viewModel.previewUrl(result.entity.previewUrl),
                                        fallbackIcon = Icons.Filled.Folder,
                                        onClick = { onProjectClick(result.entity.id) },
                                    )
                                is SearchResult.YarnResult ->
                                    SearchResultCard(
                                        title = result.entity.name,
                                        subtitle =
                                            listOfNotNull(
                                                    result.entity.brand,
                                                    result.entity.colorway,
                                                )
                                                .joinToString(" · ")
                                                .ifBlank { null },
                                        previewUrl = viewModel.previewUrl(result.entity.previewUrl),
                                        fallbackIcon = MdiIcons.Sheep,
                                        onClick = { onYarnClick(result.entity.id) },
                                    )
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    title: String,
    subtitle: String?,
    previewUrl: String?,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))) {
                if (previewUrl != null) {
                    AsyncImage(
                        model = previewUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(fallbackIcon, contentDescription = null)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
