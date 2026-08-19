package blue.anika.wolle.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.db.entity.CategoryEntity
import blue.anika.wolle.ui.common.EmptyState
import blue.anika.wolle.ui.common.RefreshFeedbackEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(viewModel: CategoriesViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val refreshState by viewModel.refreshState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    RefreshFeedbackEffect(
        state = refreshState,
        snackbarHostState = snackbarHostState,
        onConsumed = viewModel::dismissRefreshFeedback,
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.categories_title)) }) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier =
                Modifier.fillMaxSize().padding(innerPadding).testTag("e2e-categories-screen"),
        ) {
            CategoriesContent(content = uiState.content, onRetry = viewModel::refresh)
        }
    }
}

@Composable
fun CategoriesContent(
    content: CategoriesContentState,
    onRetry: () -> Unit,
) {
    when (content) {
        CategoriesContentState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize().testTag("categories-loading"),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        is CategoriesContentState.Data -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("e2e-categories-list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(content.categories, key = { it.id }) { category -> CategoryCard(category) }
            }
        }
        CategoriesContentState.Empty -> {
            Box(
                modifier = Modifier.fillMaxSize().testTag("categories-empty"),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = Icons.Filled.Category,
                    title = stringResource(R.string.categories_empty_title),
                    subtitle = stringResource(R.string.categories_empty_subtitle),
                )
            }
        }
        CategoriesContentState.Offline -> {
            CategoriesStatus(
                icon = Icons.Filled.CloudOff,
                title = stringResource(R.string.categories_offline_title),
                message = stringResource(R.string.categories_offline_subtitle),
                retryLabel = stringResource(R.string.categories_retry),
                onRetry = onRetry,
                testTag = "categories-offline",
            )
        }
        CategoriesContentState.Error -> {
            CategoriesStatus(
                icon = Icons.Filled.Label,
                title = stringResource(R.string.categories_error_title),
                message = stringResource(R.string.categories_error_subtitle),
                retryLabel = stringResource(R.string.categories_retry),
                onRetry = onRetry,
                testTag = "categories-error",
            )
        }
    }
}

@Composable
private fun CategoryCard(category: CategoryEntity) {
    Card(modifier = Modifier.fillMaxWidth().testTag("category-${category.id}")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Label,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = category.name,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun CategoriesStatus(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
    testTag: String,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 20.dp)) { Text(retryLabel) }
    }
}
