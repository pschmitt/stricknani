package blue.anika.wolle.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import blue.anika.wolle.R

internal data class Library(val name: String, val license: String, val url: String)

// Keep this list in sync with the libraries used by the app's runtime dependencies
// (gradle/libs.versions.toml).
internal val LIBRARIES =
    listOf(
        Library("AndroidX", "Apache License 2.0", "https://github.com/androidx/androidx"),
        Library(
            "Jetpack Compose",
            "Apache License 2.0",
            "https://github.com/androidx/androidx/tree/androidx-main/compose",
        ),
        Library(
            "Material 3",
            "Apache License 2.0",
            "https://github.com/androidx/androidx/tree/androidx-main/compose/material3",
        ),
        Library("Coil", "Apache License 2.0", "https://github.com/coil-kt/coil"),
        Library("Hilt", "Apache License 2.0", "https://github.com/google/dagger"),
        Library("Kotlin", "Apache License 2.0", "https://github.com/JetBrains/kotlin"),
        Library(
            "kotlinx.coroutines",
            "Apache License 2.0",
            "https://github.com/Kotlin/kotlinx.coroutines",
        ),
        Library(
            "kotlinx.datetime",
            "Apache License 2.0",
            "https://github.com/Kotlin/kotlinx-datetime",
        ),
        Library(
            "kotlinx.serialization",
            "Apache License 2.0",
            "https://github.com/Kotlin/kotlinx.serialization",
        ),
        Library(
            "Multiplatform Markdown Renderer",
            "Apache License 2.0",
            "https://github.com/mikepenz/multiplatform-markdown-renderer",
        ),
        Library("OkHttp", "Apache License 2.0", "https://github.com/square/okhttp"),
        Library("Retrofit", "Apache License 2.0", "https://github.com/square/retrofit"),
        Library(
            "Room",
            "Apache License 2.0",
            "https://github.com/androidx/androidx/tree/androidx-main/room",
        ),
        Library(
            "WorkManager",
            "Apache License 2.0",
            "https://github.com/androidx/androidx/tree/androidx-main/work",
        ),
        Library(
            "CameraX",
            "Apache License 2.0",
            "https://github.com/androidx/androidx/tree/androidx-main/camera",
        ),
        Library("ZXing", "Apache License 2.0", "https://github.com/zxing/zxing"),
        Library("Timber", "Apache License 2.0", "https://github.com/JakeWharton/timber"),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrariesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.libraries_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_nav_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SettingsGroupCard(
                    title = stringResource(R.string.libraries_open_source_title),
                    icon = Icons.AutoMirrored.Filled.LibraryBooks,
                ) {
                    LIBRARIES.forEach { library ->
                        LibraryRow(context = context, library = library)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(context: Context, library: Library) {
    SettingsListItem(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button) {
                    ContextCompat.startActivity(
                        context,
                        Intent(Intent.ACTION_VIEW, library.url.toUri()),
                        null,
                    )
                }
                .semantics { role = Role.Button },
        leadingContent = {
            Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null)
        },
        headlineContent = { Text(library.name) },
        supportingContent = { Text(library.license) },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription =
                    stringResource(R.string.libraries_open_content_description, library.name),
            )
        },
    )
}
