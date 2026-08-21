package blue.anika.wolle.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

/** The two detail-page families whose section order can be customized. */
enum class DetailCardDomain(val storageName: String) {
    PROJECT("project"),
    YARN("yarn"),
}

/** Stable IDs for the optional sections on a project detail page. */
object ProjectDetailCard {
    const val ATTACHMENTS = "attachments"
    const val DETAILS = "details"
    const val STITCH_SAMPLE = "stitch_sample"
    const val LINKED_YARNS = "linked_yarns"
    const val DESCRIPTION = "description"
    const val STEPS = "steps"
    const val NOTES = "notes"

    val defaultOrder =
        listOf(ATTACHMENTS, DETAILS, STITCH_SAMPLE, LINKED_YARNS, DESCRIPTION, STEPS, NOTES)
}

/** Stable IDs for the sections on a yarn detail page. */
object YarnDetailCard {
    const val DETAILS = "details"
    const val DESCRIPTION = "description"
    const val USED_IN = "used_in"
    const val NOTES = "notes"

    val defaultOrder = listOf(DETAILS, DESCRIPTION, USED_IN, NOTES)
}

/** Pure ordering rules shared by detail screens, Settings, and unit tests. */
object DetailCardOrder {
    fun defaults(domain: DetailCardDomain): List<String> =
        when (domain) {
            DetailCardDomain.PROJECT -> ProjectDetailCard.defaultOrder
            DetailCardDomain.YARN -> YarnDetailCard.defaultOrder
        }

    /** Drops unknown/duplicate entries and appends new cards in their default position. */
    fun sanitize(domain: DetailCardDomain, saved: List<String>?): List<String> {
        val defaults = defaults(domain)
        val known = defaults.toSet()
        return buildList {
            saved.orEmpty().filter { it in known }.distinct().forEach(::add)
            defaults.filterNot { it in this }.forEach(::add)
        }
    }

    /** Retains the saved order for cards that are currently present on a detail page. */
    fun visible(
        domain: DetailCardDomain,
        saved: List<String>?,
        available: List<String>,
    ): List<String> {
        val availableSet = available.toSet()
        return sanitize(domain, saved).filter { it in availableSet } +
            available.filterNot { it in sanitize(domain, saved) }
    }

    /** Updates only currently visible cards, retaining the order of optional cards not present. */
    fun withVisibleOrder(
        domain: DetailCardDomain,
        saved: List<String>?,
        visibleOrder: List<String>,
    ): List<String> {
        val visibleSet = visibleOrder.toSet()
        val current = sanitize(domain, saved)
        val firstVisibleIndex = current.indexOfFirst { it in visibleSet }.coerceAtLeast(0)
        val rest = current.filterNot { it in visibleSet }.toMutableList()
        rest.addAll(firstVisibleIndex.coerceAtMost(rest.size), visibleOrder)
        return rest
    }

    fun move(order: List<String>, fromIndex: Int, toIndex: Int): List<String> {
        if (fromIndex !in order.indices || toIndex !in order.indices || fromIndex == toIndex) {
            return order
        }
        return order.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }
}

/** DataStore-backed local preference for detail-card ordering. */
@Singleton
class DetailCardOrderRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {

    val projectOrder = orderFlow(DetailCardDomain.PROJECT)
    val yarnOrder = orderFlow(DetailCardDomain.YARN)

    suspend fun setOrder(domain: DetailCardDomain, order: List<String>) {
        dataStore.edit { preferences -> preferences[key(domain)] = order.joinToString(",") }
    }

    suspend fun reset(domain: DetailCardDomain) {
        dataStore.edit { preferences -> preferences.remove(key(domain)) }
    }

    private fun orderFlow(domain: DetailCardDomain) =
        dataStore.data.map { preferences ->
            val saved =
                preferences[key(domain)]?.split(",")?.filter(String::isNotBlank)?.takeIf {
                    it.isNotEmpty()
                }
            DetailCardOrder.sanitize(domain, saved)
        }

    private fun key(domain: DetailCardDomain) =
        stringPreferencesKey("detail_card_order_${domain.storageName}")
}
