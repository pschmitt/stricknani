package blue.anika.wolle.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.settings.ThemeMode
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.SearchField
import blue.anika.wolle.ui.navigation.Route
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The command palette ("search everything"): a full-height bottom sheet with the search field
 * focused. Projects, yarns and categories come from Room (offline, as in the lists), the rest from
 * the navigation and settings definitions. Same groups, order, wording and ranking as the website
 * (`web/src/lib/components/CommandPalette.svelte`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteSheet(
    onDismiss: () -> Unit,
    onNavigate: (Route) -> Unit,
    viewModel: PaletteViewModel = hiltViewModel(),
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val isAdmin by viewModel.isAdmin.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current

    var query by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(emptySet<PaletteGroup>()) }
    var selected by remember { mutableIntStateOf(-1) }
    var groups by remember { mutableStateOf(emptyList<PaletteGroupResult>()) }
    val focus = remember { FocusRequester() }

    val url = remember(query) { PaletteMatcher.detectUrl(query) }
    val statics =
        remember(isAdmin, url, configuration) {
            PaletteEntries.static(isAdmin, url) { resources.getString(it) }
        }

    LaunchedEffect(query, data, statics, expanded) {
        if (query.isNotBlank()) delay(SEARCH_DEBOUNCE_MILLIS)
        groups =
            withContext(Dispatchers.Default) {
                PaletteResults.buildGroups(query, statics + data.all(), expanded, url)
            }
        selected = -1
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val flat = groups.flatMap { it.rows }
    val dark =
        when (themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> systemDark
        }

    fun run(entry: PaletteEntry) {
        onDismiss()
        when (val target = entry.target) {
            is PaletteTarget.Go -> onNavigate(target.route)
            PaletteTarget.ToggleDarkMode -> viewModel.setDarkMode(!dark)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxHeight().fillMaxWidth().imePadding().testTag("palette-sheet")) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.palette_placeholder),
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(focus)
                        .testTag("e2e-palette-field")
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (flat.isNotEmpty()) selected = (selected + 1) % flat.size
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (flat.isNotEmpty()) {
                                        selected = (selected - 1 + flat.size) % flat.size
                                    }
                                    true
                                }
                                Key.Enter -> {
                                    flat.getOrNull(max0(selected))?.let { run(it.entry) }
                                    true
                                }
                                else -> false
                            }
                        },
            )
            if (groups.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.palette_nothing_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.palette_nothing_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding().testTag("palette-list"),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    var index = 0
                    groups.forEach { group ->
                        val groupStart = index
                        item(key = "group-${group.group}") {
                            GroupHeader(
                                title = groupTitle(group.group, query.isBlank()),
                                hiddenCount = group.total - group.rows.size,
                                onShowMore = { expanded = expanded + group.group },
                            )
                        }
                        items(group.rows, key = { it.entry.id }) { row ->
                            val rowIndex = groupStart + group.rows.indexOf(row)
                            PaletteRowItem(
                                row = row,
                                selected = rowIndex == selected,
                                onClick = { run(row.entry) },
                            )
                        }
                        index += group.rows.size
                    }
                }
            }
        }
    }
}

private fun max0(value: Int): Int = if (value < 0) 0 else value

private const val SEARCH_DEBOUNCE_MILLIS = 80L

@Composable
private fun groupTitle(group: PaletteGroup, empty: Boolean): String =
    stringResource(
        when (group) {
            PaletteGroup.Actions -> R.string.palette_group_actions
            PaletteGroup.Projects ->
                if (empty) R.string.palette_group_recent_projects
                else R.string.palette_group_projects
            PaletteGroup.Yarns ->
                if (empty) R.string.palette_group_recent_yarns else R.string.palette_group_yarns
            PaletteGroup.Categories -> R.string.palette_group_categories
            PaletteGroup.Pages -> R.string.palette_group_pages
            PaletteGroup.Settings -> R.string.palette_group_settings
        }
    )

@Composable
private fun GroupHeader(title: String, hiddenCount: Int, onShowMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (hiddenCount > 0) {
            TextButton(onClick = onShowMore) {
                Text("${stringResource(R.string.palette_show_more)} ($hiddenCount)")
            }
        }
    }
}

@Composable
private fun PaletteRowItem(row: PaletteRow, selected: Boolean, onClick: () -> Unit) {
    val entry = row.entry
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("palette-row-${entry.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.previewUrl != null) {
                AsyncImage(
                    model = entry.previewUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    entry.icon.vector(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                highlighted(entry.title, row.ranges[0], highlight),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = entry.title },
            )
            entry.sub?.let {
                Text(
                    highlighted(it, row.ranges[1], highlight),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The text with the matched ranges marked. */
internal fun highlighted(
    text: String,
    ranges: List<PaletteMatcher.Range>?,
    background: androidx.compose.ui.graphics.Color,
): AnnotatedString {
    if (ranges.isNullOrEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var position = 0
        for (range in PaletteMatcher.mergeRanges(ranges)) {
            val start = range.start.coerceIn(position, text.length)
            val end = range.end.coerceIn(start, text.length)
            append(text.substring(position, start))
            withStyle(SpanStyle(background = background, fontWeight = FontWeight.ExtraBold)) {
                append(text.substring(start, end))
            }
            position = end
        }
        append(text.substring(position))
    }
}

private fun PaletteIcon.vector() =
    when (this) {
        PaletteIcon.Home -> PhosphorIcons.HouseLine
        PaletteIcon.Projects -> PhosphorIcons.Hoodie
        PaletteIcon.Yarns -> MdiIcons.YarnBall
        PaletteIcon.Create -> PhosphorIcons.Sparkle
        PaletteIcon.Add -> Icons.Default.Add
        PaletteIcon.Import -> PhosphorIcons.DownloadSimple
        PaletteIcon.Categories -> PhosphorIcons.Tag
        PaletteIcon.Gauge -> PhosphorIcons.Ruler
        PaletteIcon.Account -> PhosphorIcons.UserCircle
        PaletteIcon.Backup -> PhosphorIcons.FloppyDisk
        PaletteIcon.About -> PhosphorIcons.Info
        PaletteIcon.Admin -> PhosphorIcons.ShieldCheck
        PaletteIcon.Language -> PhosphorIcons.Translate
        PaletteIcon.Appearance,
        PaletteIcon.DarkMode -> PhosphorIcons.Palette
        PaletteIcon.Layout -> PhosphorIcons.Layout
        PaletteIcon.BottomBar -> PhosphorIcons.Compass
        PaletteIcon.DetailCards -> PhosphorIcons.SquaresFour
        PaletteIcon.Sync -> PhosphorIcons.CloudCheck
        PaletteIcon.Timer -> PhosphorIcons.Timer
        PaletteIcon.Notifications -> PhosphorIcons.Bell
    }
