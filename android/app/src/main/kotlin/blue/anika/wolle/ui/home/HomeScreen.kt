package blue.anika.wolle.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.ui.common.EmptyState
import blue.anika.wolle.ui.common.RequestNotificationPermissionEffect
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onProjectClick: (Int) -> Unit,
    onYarnClick: (Int) -> Unit,
    onGaugeClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val favoriteProjects by viewModel.favoriteProjects.collectAsStateWithLifecycle()
    val favoriteYarns by viewModel.favoriteYarns.collectAsStateWithLifecycle()
    val recentProjects by viewModel.recentProjects.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    RequestNotificationPermissionEffect()

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    val isEmpty = favoriteProjects.isEmpty() && favoriteYarns.isEmpty() && recentProjects.isEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Stricknani") },
                actions = {
                    IconButton(onClick = onGaugeClick) {
                        Icon(Icons.Filled.Calculate, contentDescription = "Gauge calculator")
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
            if (isEmpty) {
                EmptyState(
                    icon = Icons.Filled.Home,
                    title = "Welcome to Stricknani",
                    subtitle = "Pull to refresh to sync your projects and yarn stash.",
                )
            } else {
                Column(Modifier.fillMaxSize().padding(vertical = 16.dp)) {
                    if (favoriteProjects.isNotEmpty()) {
                        HomeSection(title = "Favorite projects") {
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
                    if (favoriteYarns.isNotEmpty()) {
                        HomeSection(title = "Favorite yarns") {
                            items(favoriteYarns, key = { "fy-${it.id}" }) { yarn ->
                                HomeCard(
                                    title = yarn.name,
                                    previewUrl = viewModel.previewUrl(yarn.previewUrl),
                                    fallbackIcon = Icons.Filled.Palette,
                                    onClick = { onYarnClick(yarn.id) },
                                )
                            }
                        }
                    }
                    if (recentProjects.isNotEmpty()) {
                        HomeSection(title = "Recently updated projects") {
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

@Composable
private fun HomeSection(title: String, content: LazyListScope.() -> Unit) {
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
