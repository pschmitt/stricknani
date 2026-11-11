package blue.anika.wolle.ui.palette

import blue.anika.wolle.ui.navigation.Route

/** The groups, in the order shown (the same on the website). */
enum class PaletteGroup {
    Actions,
    Projects,
    Yarns,
    Categories,
    Pages,
    Settings,
}

enum class PaletteIcon {
    Home,
    Projects,
    Yarns,
    Create,
    Add,
    Import,
    Categories,
    Gauge,
    Trash,
    Account,
    Backup,
    About,
    Admin,
    Language,
    Appearance,
    Layout,
    BottomBar,
    DetailCards,
    Sync,
    Timer,
    Notifications,
    DarkMode,
}

/** What choosing a row does. */
sealed interface PaletteTarget {
    data class Go(val route: Route) : PaletteTarget

    data object ToggleDarkMode : PaletteTarget
}

/** One thing the palette can open or run. */
data class PaletteEntry(
    val id: String,
    val group: PaletteGroup,
    val title: String,
    val sub: String? = null,
    /** Extra words that find the entry (any language). */
    val keywords: List<String> = emptyList(),
    val icon: PaletteIcon,
    val target: PaletteTarget,
    val previewUrl: String? = null,
)

data class PaletteRow(
    val entry: PaletteEntry,
    val score: Double,
    /** Highlight ranges for the title (0), the line below (1) and the keywords (2). */
    val ranges: Map<Int, List<PaletteMatcher.Range>>,
)

data class PaletteGroupResult(
    val group: PaletteGroup,
    /** All rows that matched; [rows] holds the first few. */
    val total: Int,
    val rows: List<PaletteRow>,
)

object PaletteResults {
    /** Rows per group when nothing is typed (recent items and the main pages). */
    private val emptyLimits =
        mapOf(
            PaletteGroup.Actions to 3,
            PaletteGroup.Projects to 5,
            PaletteGroup.Yarns to 5,
            PaletteGroup.Pages to 8,
        )

    private const val IMPORT_PREFIX = "action:import"

    fun scoreRow(words: List<String>, entry: PaletteEntry): PaletteRow? {
        val hit =
            PaletteMatcher.scoreEntry(
                words,
                listOf(
                    PaletteMatcher.Field(entry.title, 1.0),
                    PaletteMatcher.Field(entry.sub.orEmpty(), 0.6),
                    PaletteMatcher.Field(entry.keywords.joinToString(" "), 0.5),
                ),
            ) ?: return null
        return PaletteRow(entry, hit.score, hit.ranges)
    }

    /**
     * The groups to show: matching rows ranked in each group, the groups in the fixed order, at
     * most [PaletteMatcher.GROUP_LIMIT] rows each unless expanded. [url] is a typed or pasted link:
     * only its import actions are listed then.
     */
    fun buildGroups(
        query: String,
        entries: List<PaletteEntry>,
        expanded: Set<PaletteGroup>,
        url: String?,
    ): List<PaletteGroupResult> {
        val words = PaletteMatcher.queryWords(query)
        val empty = words.isEmpty()
        val byGroup = HashMap<PaletteGroup, MutableList<PaletteRow>>()
        entries.forEachIndexed { order, entry ->
            if (empty && entry.group !in emptyLimits) return@forEachIndexed
            val row =
                if (url != null) {
                    if (entry.id.startsWith(IMPORT_PREFIX)) {
                        PaletteRow(entry, 100000.0 - order, emptyMap())
                    } else null
                } else scoreRow(words, entry)
            if (row != null) byGroup.getOrPut(entry.group) { ArrayList() }.add(row)
        }
        return PaletteGroup.entries.mapNotNull { group ->
            val rows = byGroup[group]
            if (rows.isNullOrEmpty()) return@mapNotNull null
            // A stable sort keeps the original order among equal scores.
            val ranked = rows.sortedByDescending { it.score }
            val limit =
                when {
                    empty -> emptyLimits[group] ?: PaletteMatcher.GROUP_LIMIT
                    group in expanded -> Int.MAX_VALUE
                    else -> PaletteMatcher.GROUP_LIMIT
                }
            PaletteGroupResult(group, ranked.size, ranked.take(limit))
        }
    }
}
