package blue.anika.wolle.ui.common

/** Window width from which the library lists switch from compact rows to a card grid. */
const val LIBRARY_GRID_MIN_WIDTH_DP = 600

/** The most chips a library card shows; the rest is on the detail screen. */
const val LIBRARY_MAX_CHIPS = 3

/** The domain a library card belongs to; picks the placeholder tone and icon. */
enum class LibraryDomain {
    Project,
    Yarn,
}

/** Meta line of a yarn card: "brand · colourway", or [placeholder] when both are empty. */
fun yarnMetaLine(brand: String?, colorway: String?, placeholder: String): String =
    listOfNotNull(brand, colorway)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
        .ifEmpty { placeholder }

/** Up to [LIBRARY_MAX_CHIPS] non-blank, distinct chip labels. */
fun libraryChips(labels: List<String>): List<String> =
    labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(LIBRARY_MAX_CHIPS)

/** The chip of a yarn card: its weight category, if any. */
fun yarnChips(weightCategory: String?): List<String> = libraryChips(listOfNotNull(weightCategory))

/** Everything a library card or row shows, domain-agnostic. */
data class LibraryCardData(
    val domain: LibraryDomain,
    val title: String,
    val meta: String?,
    val updatedAt: Long,
    val imageUrl: String?,
    val isFavorite: Boolean,
    val chips: List<String>,
)
