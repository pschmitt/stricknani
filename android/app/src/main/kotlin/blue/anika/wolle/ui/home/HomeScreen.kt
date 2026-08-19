package blue.anika.wolle.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.EmptyState
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.RefreshFeedbackEffect
import blue.anika.wolle.ui.common.RequestNotificationPermissionEffect
import blue.anika.wolle.ui.common.SyncIssueBanner
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onProjectClick: (Int) -> Unit,
    onYarnClick: (Int) -> Unit,
    onGaugeClick: () -> Unit,
    onOpenSyncIssues: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val favoriteProjects by viewModel.favoriteProjects.collectAsStateWithLifecycle()
    val favoriteYarns by viewModel.favoriteYarns.collectAsStateWithLifecycle()
    val recentProjects by viewModel.recentProjects.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshState by viewModel.refreshState.collectAsStateWithLifecycle()
    val lastSyncedMillis by viewModel.lastSyncedMillis.collectAsStateWithLifecycle()
    val pendingChangesCount by viewModel.pendingChangesCount.collectAsStateWithLifecycle()
    val hasSyncFailures by viewModel.hasSyncFailures.collectAsStateWithLifecycle()
    val failedMutations by viewModel.failedMutations.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    RequestNotificationPermissionEffect()

    RefreshFeedbackEffect(refreshState, snackbarHostState, viewModel::dismissRefreshFeedback)

    val isEmpty = favoriteProjects.isEmpty() && favoriteYarns.isEmpty() && recentProjects.isEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onGaugeClick) {
                        Icon(
                            Icons.Filled.Calculate,
                            contentDescription =
                                stringResource(R.string.home_gauge_calculator_description),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("e2e-home-refresh"),
        ) {
            if (isEmpty) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        HomeSyncStatusCard(
                            isRefreshing = isRefreshing,
                            lastSyncedMillis = lastSyncedMillis,
                            pendingChangesCount = pendingChangesCount,
                            hasSyncFailures = hasSyncFailures,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    item {
                        SyncIssueBanner(
                            issues = failedMutations,
                            onRetry = viewModel::retryFailedMutations,
                            onOpenDetails = onOpenSyncIssues,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    item {
                        Box(Modifier.fillMaxWidth().height(480.dp)) {
                            EmptyState(
                                icon = Icons.Filled.Home,
                                title = stringResource(R.string.home_welcome_title),
                                subtitle = stringResource(R.string.home_welcome_subtitle),
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 16.dp),
                ) {
                    item {
                        HomeSyncStatusCard(
                            isRefreshing = isRefreshing,
                            lastSyncedMillis = lastSyncedMillis,
                            pendingChangesCount = pendingChangesCount,
                            hasSyncFailures = hasSyncFailures,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    item {
                        SyncIssueBanner(
                            issues = failedMutations,
                            onRetry = viewModel::retryFailedMutations,
                            onOpenDetails = onOpenSyncIssues,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                    if (favoriteProjects.isNotEmpty()) {
                        item {
                            HomeSection(
                                title = stringResource(R.string.home_favorite_projects_title)
                            ) {
                                items(favoriteProjects, key = { "fp-${it.id}" }) { project ->
                                    HomeCard(
                                        title = project.name,
                                        previewUrl = viewModel.previewUrl(project.previewUrl),
                                        fallbackIcon = Icons.Filled.Folder,
                                        onClick = { onProjectClick(project.id) },
                                    )
                                }
                            }
                        }
                    }
                    if (favoriteYarns.isNotEmpty()) {
                        item {
                            HomeSection(
                                title = stringResource(R.string.home_favorite_yarns_title)
                            ) {
                                items(favoriteYarns, key = { "fy-${it.id}" }) { yarn ->
                                    HomeCard(
                                        title = yarn.name,
                                        previewUrl = viewModel.previewUrl(yarn.previewUrl),
                                        fallbackIcon = MdiIcons.Sheep,
                                        onClick = { onYarnClick(yarn.id) },
                                    )
                                }
                            }
                        }
                    }
                    if (recentProjects.isNotEmpty()) {
                        item {
                            HomeSection(
                                title = stringResource(R.string.home_recent_projects_title)
                            ) {
                                items(recentProjects, key = { "rp-${it.id}" }) { project ->
                                    HomeCard(
                                        title = project.name,
                                        previewUrl = viewModel.previewUrl(project.previewUrl),
                                        fallbackIcon = Icons.Filled.Folder,
                                        onClick = { onProjectClick(project.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSection(title: String, content: LazyListScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun HomeCard(
    title: String,
    previewUrl: String?,
    fallbackIcon: ImageVector,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.width(120.dp)) {
        Box(
            modifier =
                Modifier.size(120.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onClick)
        ) {
            if (previewUrl != null) {
                AsyncImage(
                    model = previewUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(fallbackIcon, contentDescription = null)
                }
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
