package blue.anika.wolle.ui.yarns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.EmptyState
import blue.anika.wolle.ui.common.EveryoneFilterChips
import blue.anika.wolle.ui.common.LibraryCardData
import blue.anika.wolle.ui.common.LibraryCollection
import blue.anika.wolle.ui.common.LibraryDomain
import blue.anika.wolle.ui.common.LocalOwnerContext
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.RefreshFeedbackEffect
import blue.anika.wolle.ui.common.SearchField
import blue.anika.wolle.ui.common.StricknaniSnackbarHost
import blue.anika.wolle.ui.common.isWideLibraryLayout
import blue.anika.wolle.ui.common.ownerBadge
import blue.anika.wolle.ui.common.yarnChips
import blue.anika.wolle.ui.common.yarnMetaLine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YarnsListScreen(
    onYarnClick: (Int) -> Unit,
    onAddYarnClick: () -> Unit,
    /** Shown as a back arrow when Yarns is not a visible tab (opened from Home's stash card). */
    onBack: (() -> Unit)? = null,
    viewModel: YarnsListViewModel = hiltViewModel(),
) {
    val yarns by viewModel.yarns.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.favoritesOnly.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshState by viewModel.refreshState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val importAvailability by viewModel.importAvailability.collectAsStateWithLifecycle()
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val wide = isWideLibraryLayout()

    RefreshFeedbackEffect(refreshState, snackbarHostState, viewModel::dismissRefreshFeedback)

    Scaffold(
        // Same title bar as the project list, so both libraries start the same way.
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.yarns_list_title)) },
                actions = {
                    IconButton(
                        modifier = Modifier.testTag("yarn-import-open"),
                        onClick = { showImportDialog = true },
                    ) {
                        Icon(
                            PhosphorIcons.DownloadSimple,
                            contentDescription =
                                stringResource(R.string.yarns_list_import_description),
                        )
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { StricknaniSnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddYarnClick,
                modifier = Modifier.testTag("e2e-new-yarn"),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.yarns_list_new_yarn_fab)) },
                expanded =
                    !(if (wide) gridState.canScrollBackward else listState.canScrollBackward),
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
                EveryoneFilterChips(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
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
                                icon = MdiIcons.YarnBall,
                                title = stringResource(R.string.yarns_list_empty_title),
                                subtitle = stringResource(R.string.projects_list_empty_subtitle),
                                modifier = Modifier.fillMaxWidth().height(400.dp),
                            )
                        }
                    }
                } else {
                    val detailsToAdd = stringResource(R.string.library_card_details_to_add)
                    LibraryCollection(
                        items = yarns,
                        key = { it.id },
                        toCard = { yarn ->
                            val me = LocalOwnerContext.current.currentUserId
                            remember(yarn, detailsToAdd, me) {
                                LibraryCardData(
                                    domain = LibraryDomain.Yarn,
                                    title = yarn.name,
                                    meta = yarnMetaLine(yarn.brand, yarn.colorway, detailsToAdd),
                                    updatedAt = yarn.updatedAt,
                                    imageUrl = viewModel.previewUrl(yarn),
                                    isFavorite = yarn.isFavorite,
                                    chips = yarnChips(yarn.weightCategory),
                                    owner = yarn.ownerBadge(me),
                                )
                            }
                        },
                        onClick = { onYarnClick(it.id) },
                        onFavoriteClick = viewModel::toggleFavorite,
                        listState = listState,
                        gridState = gridState,
                        modifier = Modifier.testTag("e2e-yarns-list"),
                    )
                }
            }
        }
    }

    YarnImportDialog(
        visible = showImportDialog,
        state = importState,
        availability = importAvailability,
        onStart = viewModel::startImport,
        onConfirm = viewModel::confirmImport,
        onCancel = {
            showImportDialog = false
            viewModel.cancelImport()
        },
        onRetry = viewModel::retryImport,
        thumbnailUrl = viewModel::importThumbnailUrl,
    )
}
