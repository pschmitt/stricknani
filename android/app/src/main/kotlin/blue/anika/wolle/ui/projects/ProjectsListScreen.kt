package blue.anika.wolle.ui.projects

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.ui.common.EmptyState
import blue.anika.wolle.ui.common.RefreshFeedbackEffect
import blue.anika.wolle.ui.common.SearchField
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsListScreen(
    onProjectClick: (Int) -> Unit,
    onAddProjectClick: () -> Unit,
    viewModel: ProjectsListViewModel = hiltViewModel(),
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshState by viewModel.refreshState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    var showAddCategoryDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    RefreshFeedbackEffect(refreshState, snackbarHostState, viewModel::dismissRefreshFeedback)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.projects_list_title)) },
                actions = {
                    IconButton(
                        modifier = Modifier.testTag("project-import-open"),
                        onClick = { showImportDialog = true },
                    ) {
                        Icon(
                            Icons.Filled.FileDownload,
                            contentDescription =
                                stringResource(R.string.projects_list_import_description),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddProjectClick,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.project_new_label)) },
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
                    placeholder = stringResource(R.string.projects_list_search_placeholder),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )

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
                    item {
                        FilterChip(
                            selected = false,
                            onClick = { showAddCategoryDialog = true },
                            label = {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription =
                                        stringResource(
                                            R.string.projects_list_add_category_description
                                        ),
                                )
                            },
                        )
                    }
                }
                if (tags.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.projects_list_tags_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.testTag("projects-list-tag-filters"),
                    ) {
                        items(tags, key = { it }) { tag ->
                            FilterChip(
                                selected = selectedTag == tag,
                                onClick = { viewModel.onTagSelected(tag) },
                                label = { Text(tag) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (projects.isEmpty()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("e2e-projects-list"),
                    ) {
                        item {
                            EmptyState(
                                icon = Icons.Filled.Folder,
                                title = stringResource(R.string.projects_list_empty_title),
                                subtitle = stringResource(R.string.projects_list_empty_subtitle),
                                modifier = Modifier.fillMaxWidth().height(400.dp),
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.testTag("e2e-projects-list"),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(projects, key = { it.id }) { project ->
                            ProjectListCard(
                                project = project,
                                previewUrl = viewModel.previewUrl(project),
                                onClick = { onProjectClick(project.id) },
                                onFavoriteClick = { viewModel.toggleFavorite(project) },
                            )
                        }
                    }
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

    ProjectImportDialog(
        visible = showImportDialog,
        state = importState,
        onStart = viewModel::startImport,
        onConfirm = viewModel::confirmImport,
        onCancel = {
            showImportDialog = false
            viewModel.cancelImport()
        },
        onRetry = viewModel::retryImport,
    )
}

@Composable
private fun ProjectListCard(
    project: ProjectEntity,
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
                        contentDescription = project.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (project.category != null) {
                    Text(
                        text = project.category,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector =
                        if (project.isFavorite) Icons.Filled.Favorite
                        else Icons.Filled.FavoriteBorder,
                    contentDescription =
                        stringResource(
                            if (project.isFavorite) R.string.common_unfavorite
                            else R.string.common_favorite
                        ),
                    tint =
                        if (project.isFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
