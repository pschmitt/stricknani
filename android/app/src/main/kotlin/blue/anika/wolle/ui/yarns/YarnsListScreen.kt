package blue.anika.wolle.ui.yarns

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.ui.common.EmptyState
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.RefreshFeedbackEffect
import blue.anika.wolle.ui.common.SearchField
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YarnsListScreen(
    onYarnClick: (Int) -> Unit,
    onAddYarnClick: () -> Unit,
    viewModel: YarnsListViewModel = hiltViewModel(),
) {
    val yarns by viewModel.yarns.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.favoritesOnly.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshState by viewModel.refreshState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    RefreshFeedbackEffect(refreshState, snackbarHostState, viewModel::dismissRefreshFeedback)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddYarnClick,
                modifier = Modifier.testTag("e2e-new-yarn"),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.yarns_list_new_yarn_fab)) },
                expanded = !listState.canScrollBackward,
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            Column(Modifier.fillMaxSize()) {
                SearchField(
                    value = searchQuery,
                    onValueChange = viewModel::onSearchQueryChange,
                    placeholder = stringResource(R.string.yarns_list_search_placeholder),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                    FilterChip(
                        selected = favoritesOnly,
                        onClick = { viewModel.onFavoritesOnlyChange(!favoritesOnly) },
                        label = { Text(stringResource(R.string.yarns_list_favorites_only)) },
                    )
                }
                Spacer(Modifier.height(8.dp))

                if (yarns.isEmpty()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("e2e-yarns-list"),
                    ) {
                        item {
                            EmptyState(
                                icon = MdiIcons.Sheep,
                                title = stringResource(R.string.yarns_list_empty_title),
                                subtitle = stringResource(R.string.projects_list_empty_subtitle),
                                modifier = Modifier.fillMaxWidth().height(400.dp),
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.testTag("e2e-yarns-list"),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(yarns, key = { it.id }) { yarn ->
                            YarnListCard(
                                yarn = yarn,
                                previewUrl = viewModel.previewUrl(yarn),
                                onClick = { onYarnClick(yarn.id) },
                                onFavoriteClick = { viewModel.toggleFavorite(yarn) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YarnListCard(
    yarn: YarnEntity,
    previewUrl: String?,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))) {
                if (previewUrl != null) {
                    AsyncImage(
                        model = previewUrl,
                        contentDescription = yarn.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MdiIcons.Sheep, contentDescription = null)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = yarn.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(yarn.brand, yarn.colorway).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector =
                        if (yarn.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription =
                        stringResource(
                            if (yarn.isFavorite) R.string.common_unfavorite
                            else R.string.common_favorite
                        ),
                    tint =
                        if (yarn.isFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
