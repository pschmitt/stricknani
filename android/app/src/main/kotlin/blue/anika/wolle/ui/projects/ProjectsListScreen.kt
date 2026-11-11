package blue.anika.wolle.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.api.v2.ImportJobDto
import blue.anika.wolle.data.imports.ImportTarget
import blue.anika.wolle.ui.common.AllFilterChip
import blue.anika.wolle.ui.common.EmptyState
import blue.anika.wolle.ui.common.LibraryCardData
import blue.anika.wolle.ui.common.LibraryCollection
import blue.anika.wolle.ui.common.LibraryDomain
import blue.anika.wolle.ui.common.LocalOwnerContext
import blue.anika.wolle.ui.common.PROJECT_STATUSES
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.RefreshFeedbackEffect
import blue.anika.wolle.ui.common.SearchField
import blue.anika.wolle.ui.common.StricknaniSnackbarHost
import blue.anika.wolle.ui.common.isWideLibraryLayout
import blue.anika.wolle.ui.common.libraryChips
import blue.anika.wolle.ui.common.ownerBadge
import blue.anika.wolle.ui.common.ownerBadgeOf
import blue.anika.wolle.ui.common.ownerFilterItems
import blue.anika.wolle.ui.common.projectStatusLabel
import blue.anika.wolle.ui.common.rememberEveryoneFilter
import blue.anika.wolle.ui.common.trashDaysLeft
import blue.anika.wolle.ui.common.trashedUpdatedAt
import blue.anika.wolle.ui.imports.WaitingForReview
import blue.anika.wolle.ui.trash.TrashedListBody

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsListScreen(
    onProjectClick: (Int) -> Unit,
    onAddProjectClick: () -> Unit,
    /** Opens the new-project editor on an import draft that waits for review. */
    onOpenDraft: (ImportJobDto) -> Unit = {},
    /** Preselects the owner filter: `me`, `shared` or a user id (the `?owner=` of the web page). */
    initialOwner: String? = null,
    /** Preselects the status filter: `in_progress` or `done` (the `?status=` of the web page). */
    initialStatus: String? = null,
    /** Preselects the Trash filter: `only` (the `?trashed=only` of the web page). */
    initialTrashed: String? = null,
    /** Opens a trashed project's read-only page (the Trash filter's cards). */
    onTrashedProjectClick: (Int) -> Unit = {},
    viewModel: ProjectsListViewModel = hiltViewModel(),
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.favoritesOnly.collectAsStateWithLifecycle()
    val statusFilter by viewModel.statusFilter.collectAsStateWithLifecycle()
    val ownerChoice by viewModel.ownerChoice.collectAsStateWithLifecycle()
    val trashedOnly by viewModel.trashedOnly.collectAsStateWithLifecycle()
    val trashedProjects by viewModel.trashedProjects.collectAsStateWithLifecycle()
    val owners by viewModel.owners.collectAsStateWithLifecycle()
    val currentUserId = LocalOwnerContext.current.currentUserId
    LaunchedEffect(currentUserId) { viewModel.setCurrentUserId(currentUserId) }
    LaunchedEffect(initialOwner, initialStatus, initialTrashed) {
        viewModel.applyInitialFilters(
            owner = initialOwner,
            status = initialStatus,
            trashed = initialTrashed,
        )
    }
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshState by viewModel.refreshState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val wide = isWideLibraryLayout()

    RefreshFeedbackEffect(refreshState, snackbarHostState, viewModel::dismissRefreshFeedback)

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.projects_list_title)) })
        },
        snackbarHost = { StricknaniSnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddProjectClick,
                modifier = Modifier.testTag("e2e-new-project"),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.project_new_label)) },
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
                    placeholder = stringResource(R.string.projects_list_search_placeholder),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )

                val (everyoneFilter, selectEveryone) = rememberEveryoneFilter()
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.testTag("projects-list-category-filters"),
                ) {
                    item {
                        AllFilterChip(
                            selected =
                                selectedCategory == null &&
                                    selectedTag == null &&
                                    !favoritesOnly &&
                                    statusFilter == null &&
                                    ownerChoice == null &&
                                    !trashedOnly &&
                                    !everyoneFilter.mineSelected,
                            onClick = {
                                viewModel.clearFilters()
                                if (everyoneFilter.mineSelected) selectEveryone(true)
                            },
                        )
                    }
                    item(key = "favorites-only") {
                        FilterChip(
                            selected = favoritesOnly,
                            onClick = { viewModel.onFavoritesOnlyChange(!favoritesOnly) },
                            label = { Text(stringResource(R.string.yarns_list_favorites_only)) },
                        )
                    }
                    items(PROJECT_STATUSES, key = { "status:$it" }) { status ->
                        FilterChip(
                            selected = statusFilter == status,
                            onClick = { viewModel.onStatusSelected(status) },
                            label = { Text(stringResource(projectStatusLabel(status))) },
                            modifier = Modifier.testTag("projects-list-status-$status"),
                        )
                    }
                    ownerFilterItems(
                        choice = ownerChoice,
                        owners = owners,
                        onSelect = viewModel::onOwnerSelected,
                        testTagPrefix = "projects-list",
                        trashedOnly = trashedOnly,
                        onTrashToggle = { viewModel.onTrashedOnlyChange(!trashedOnly) },
                    )
                    items(categories, key = { it.id }) { category ->
                        FilterChip(
                            selected = selectedCategory == category.name,
                            onClick = {
                                viewModel.onCategorySelected(
                                    if (selectedCategory == category.name) null else category.name
                                )
                            },
                            label = { Text(category.name) },
                        )
                    }
                    // Tags share the row with categories instead of a labelled second row; the
                    // tag icon tells them apart.
                    items(tags, key = { "tag:$it" }) { tag ->
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = { viewModel.onTagSelected(tag) },
                            label = { Text(tag) },
                            leadingIcon = {
                                Icon(
                                    PhosphorIcons.Tag,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (viewModel.importsOffered) {
                    WaitingForReview(
                        target = ImportTarget.Project,
                        onOpen = onOpenDraft,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
                val filtered =
                    searchQuery.isNotBlank() ||
                        selectedCategory != null ||
                        selectedTag != null ||
                        favoritesOnly ||
                        statusFilter != null ||
                        ownerChoice != null ||
                        trashedOnly ||
                        (everyoneFilter.available && !everyoneFilter.enabled)
                val emptyList: @Composable () -> Unit = {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("e2e-projects-list"),
                    ) {
                        item {
                            EmptyState(
                                icon = PhosphorIcons.Hoodie,
                                title =
                                    stringResource(
                                        if (filtered) R.string.projects_list_empty_filtered_title
                                        else R.string.projects_list_empty_title
                                    ),
                                subtitle =
                                    stringResource(
                                        if (filtered) R.string.projects_list_empty_filtered_subtitle
                                        else R.string.projects_list_empty_subtitle
                                    ),
                                modifier = Modifier.fillMaxWidth().height(400.dp),
                            )
                        }
                    }
                }
                if (trashedOnly) {
                    TrashedListBody(
                        state = trashedProjects,
                        onRetry = viewModel::retryTrash,
                        onLeave = { viewModel.onTrashedOnlyChange(false) },
                    ) { trashed ->
                        if (trashed.isEmpty()) {
                            emptyList()
                        } else {
                            LibraryCollection(
                                items = trashed,
                                key = { it.id },
                                toCard = { project ->
                                    val me = LocalOwnerContext.current.currentUserId
                                    remember(project, me) {
                                        LibraryCardData(
                                            domain = LibraryDomain.Project,
                                            title = project.name,
                                            meta = project.category,
                                            updatedAt = trashedUpdatedAt(project.updatedAt),
                                            imageUrl = viewModel.previewUrl(project),
                                            isFavorite = project.isFavorite,
                                            chips = libraryChips(project.tags),
                                            status = project.status,
                                            trashDaysLeft =
                                                trashDaysLeft(
                                                    project.trashedAt,
                                                    project.purgeAt,
                                                    System.currentTimeMillis(),
                                                ),
                                            owner =
                                                ownerBadgeOf(
                                                    project.owner?.id,
                                                    project.owner?.name,
                                                    project.owner?.avatarUrl,
                                                    me,
                                                ),
                                            // Trashed items are read-only: no heart to toggle.
                                            favoriteToggleable = false,
                                        )
                                    }
                                },
                                onClick = { onTrashedProjectClick(it.id) },
                                onFavoriteClick = {},
                                listState = listState,
                                gridState = gridState,
                                modifier = Modifier.testTag("e2e-projects-list"),
                            )
                        }
                    }
                } else if (projects.isEmpty()) {
                    emptyList()
                } else {
                    LibraryCollection(
                        items = projects,
                        key = { it.id },
                        toCard = { project ->
                            val tags = viewModel.tagsOf(project)
                            val me = LocalOwnerContext.current.currentUserId
                            remember(project, tags, me) {
                                LibraryCardData(
                                    domain = LibraryDomain.Project,
                                    title = project.name,
                                    meta = project.category,
                                    updatedAt = project.updatedAt,
                                    imageUrl = viewModel.previewUrl(project),
                                    isFavorite = project.isFavorite,
                                    chips = libraryChips(tags),
                                    status = project.status,
                                    owner = project.ownerBadge(me),
                                )
                            }
                        },
                        onClick = { onProjectClick(it.id) },
                        onFavoriteClick = viewModel::toggleFavorite,
                        listState = listState,
                        gridState = gridState,
                        modifier = Modifier.testTag("e2e-projects-list"),
                    )
                }
            }
        }
    }
}
