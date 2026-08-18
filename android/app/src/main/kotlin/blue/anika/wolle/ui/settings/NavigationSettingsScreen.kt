package blue.anika.wolle.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.data.settings.NavbarItemPreference
import blue.anika.wolle.ui.navigation.NavbarCustomization
import blue.anika.wolle.ui.navigation.TopLevelDestination

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NavigationSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel) {
    val navbarItems by viewModel.navbarItems.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SettingsCategory.Navigation.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsGroupCard(title = "Bottom bar", icon = Icons.Filled.ViewCarousel) {
                    Text(
                        text =
                            "Choose which destinations appear in the bottom bar, and in what order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    NavbarItemsEditor(
                        items = navbarItems,
                        onMove = viewModel::moveNavbarItem,
                        onVisibilityChange = viewModel::setNavbarItemVisible,
                    )
                }
            }
        }
    }
}

@Composable
private fun NavbarItemsEditor(
    items: List<NavbarItemPreference>,
    onMove: (index: Int, delta: Int) -> Unit,
    onVisibilityChange: (id: String, visible: Boolean) -> Unit,
) {
    val destinations = NavbarCustomization.toDestinations(items)
    Column {
        items.forEachIndexed { index, item ->
            val destination = destinations.getOrNull(index) ?: return@forEachIndexed
            val isSettings = destination == TopLevelDestination.SETTINGS
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = item.visible,
                    onCheckedChange = { checked -> onVisibilityChange(item.id, checked) },
                    enabled = !isSettings,
                )
                Icon(
                    destination.icon,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = destination.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onMove(index, -1) }, enabled = index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = { onMove(index, 1) }, enabled = index < items.lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                }
            }
        }
    }
}
