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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import blue.anika.wolle.data.api.v2.ImportJobDto
import blue.anika.wolle.data.imports.ImportTarget
import blue.anika.wolle.ui.common.EmptyState
import blue.anika.wolle.ui.common.LibraryCardData
import blue.anika.wolle.ui.common.LibraryCollection
import blue.anika.wolle.ui.common.LibraryDomain
import blue.anika.wolle.ui.common.LocalOwnerContext
import blue.anika.wolle.ui.common.MineFilterChip
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.RefreshFeedbackEffect
import blue.anika.wolle.ui.common.SearchField
import blue.anika.wolle.ui.common.StricknaniSnackbarHost
import blue.anika.wolle.ui.common.isWideLibraryLayout
import blue.anika.wolle.ui.common.libraryChips
import blue.anika.wolle.ui.common.ownerBadge
import blue.anika.wolle.ui.common.rememberEveryoneFilter
import blue.anika.wolle.ui.imports.WaitingForReview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsListScreen(
    onProjectClick: (Int) -> Unit,
    onAddProjectClick: () -> Unit,
    /** Opens the new-project editor on an import draft that waits for review. */
    onOpenDraft: (ImportJobDto) -> Unit = {},
    viewModel: ProjectsListViewModel = hiltViewModel(),
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.favoritesOnly.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshState by viewModel.refreshState.collectAsStateWithLifecycle()
    var showAddCategoryDialog by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val wide = isWideLibraryLayout()

    RefreshFeedbackEffect(refreshState, snackbarHostState, viewModel::dismissRefreshFeedback)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.projects_list_title)) },
                actions = {
                    // Managing categories is not a filter, so it lives here instead of as a "+"
                    // chip at the end of the filter row.
                    var menuExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription =
                                stringResource(R.string.projects_list_more_actions_description),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.projects_list_add_category_description)
                                )
                            },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                showAddCategoryDialog = true
                            },
                        )
                    }
                },
            )
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
                        FilterChip(
                            selected = selectedCategory == null,
                            onClick = { viewModel.onCategorySelected(null) },
                            label = { Text(stringResource(R.string.projects_list_category_all)) },
                        )
                    }
                    item(key = "favorites-only") {
                        FilterChip(
                            selected = favoritesOnly,
                            onClick = { viewModel.onFavoritesOnlyChange(!favoritesOnly) },
                            label = { Text(stringResource(R.string.yarns_list_favorites_only)) },
                        )
                    }
                    if (everyoneFilter.available) {
                        item(key = "mine-only") { MineFilterChip(everyoneFilter, selectEveryone) }
                    }
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
                        (everyoneFilter.available && !everyoneFilter.enabled)
                if (projects.isEmpty()) {
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

    if (showAddCategoryDialog) {
        AddCategoryDialog(
            onConfirm = { name ->
                viewModel.addCategory(name)
                showAddCategoryDialog = false
            },
            onDismiss = { showAddCategoryDialog = false },
        )
    }
}

@Composable
private fun AddCategoryDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.projects_list_add_category_dialog_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = {
                    Text(stringResource(R.string.projects_list_add_category_name_placeholder))
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text(stringResource(R.string.common_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
