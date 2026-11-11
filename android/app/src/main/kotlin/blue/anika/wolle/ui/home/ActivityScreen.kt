package blue.anika.wolle.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R

/**
 * The full activity timeline: Home's "Recent activity" rows, paged with a "Show more" button. Rows
 * open the same project or yarn as on Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    onBack: () -> Unit,
    onProjectClick: (Int) -> Unit,
    onYarnClick: (Int) -> Unit,
    onTrashClick: () -> Unit = {},
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activity_page_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_nav_back),
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = viewModel::reload,
            modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("activity-screen"),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ActivitySection(
                    state = state,
                    previewUrl = viewModel::previewUrl,
                    onProjectClick = onProjectClick,
                    onYarnClick = onYarnClick,
                    onTrashClick = onTrashClick,
                    onShowMore = viewModel::loadMore,
                    onRetry = viewModel::reload,
                    showTitle = false,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp).navigationBarsPadding(),
                )
            }
        }
    }
}
