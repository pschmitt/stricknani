package blue.anika.wolle.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import blue.anika.wolle.data.account.AccountV2Api
import blue.anika.wolle.data.account.DetailCardOrderDto
import blue.anika.wolle.data.account.UpdateAccountRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The two detail-page families whose section order can be customized. */
enum class DetailCardDomain(val storageName: String) {
    PROJECT("project"),
    YARN("yarn"),
}

/** Stable IDs for the optional sections on a project detail page. */
object ProjectDetailCard {
    const val DETAILS = "details"
    const val MATERIALS = "materials"

    /** "Pattern": the description and the notes together, like the web. */
    const val DESCRIPTION = "description"
    const val STEPS = "steps"

    // Attachments sit fixed under the gallery and the source links are rows of the details card.
    val defaultOrder = listOf(DETAILS, MATERIALS, DESCRIPTION, STEPS)
}

/** Stable IDs for the sections on a yarn detail page. */
object YarnDetailCard {
    const val DETAILS = "details"
    const val DESCRIPTION = "description"
    const val USED_IN = "used_in"
    const val NOTES = "notes"

    // The source links are rows of the details card.
    // The web yarn page's order: details, description, notes, used in.
    val defaultOrder = listOf(DETAILS, DESCRIPTION, NOTES, USED_IN)
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

/** What one reconciliation does with the local order of a page. */
data class DetailCardOrderReconciled(
    /** The order to keep locally; null is the default order. */
    val local: List<String>?,
    /** The local order still has to reach the server. */
    val pending: Boolean,
)

/** The merge rules between the local cache and the per-user server setting. */
object DetailCardOrderSync {
    /**
     * The server wins, except for a local change the server has not seen yet ([pending]): that one
     * is kept and pushed. A server answer that equals the default order is stored as no order.
     */
    fun reconcile(
        domain: DetailCardDomain,
        local: List<String>?,
        pending: Boolean,
        server: List<String>?,
    ): DetailCardOrderReconciled {
        if (pending || server == null) return DetailCardOrderReconciled(local, pending)
        val sanitized = DetailCardOrder.sanitize(domain, server)
        val stored = sanitized.takeUnless { it == DetailCardOrder.defaults(domain) }
        return DetailCardOrderReconciled(stored, pending = false)
    }

    /** The `detail_card_order` body for the pages in [pending]; an empty list resets a page. */
    fun patch(
        pending: Set<DetailCardDomain>,
        project: List<String>?,
        yarn: List<String>?,
    ): DetailCardOrderDto =
        DetailCardOrderDto(
            project = if (DetailCardDomain.PROJECT in pending) project.orEmpty() else null,
            yarn = if (DetailCardDomain.YARN in pending) yarn.orEmpty() else null,
        )

    fun encode(order: List<String>): String = order.joinToString(",")

    fun decode(raw: String?): List<String>? =
        raw?.split(",")?.filter(String::isNotBlank)?.takeIf { it.isNotEmpty() }
}

/**
 * Detail-card ordering: a per-user server setting (`PATCH /account {detail_card_order}`) shared
 * with the web app, cached in DataStore so the detail screens work offline. A change is stored
 * locally and marked pending first, then pushed; while it is pending (offline, or the push failed)
 * it survives a sync, afterwards the server's value replaces the cache.
 */
@Singleton
class DetailCardOrderRepository
@Inject
constructor(
    private val dataStore: DataStore<Preferences>,
    private val accountApi: AccountV2Api,
) {
    private val syncMutex = Mutex()

    val projectOrder = orderFlow(DetailCardDomain.PROJECT)
    val yarnOrder = orderFlow(DetailCardDomain.YARN)

    suspend fun setOrder(domain: DetailCardDomain, order: List<String>) {
        syncMutex.withLock { adoptLocalOrdersOnce() }
        dataStore.edit { preferences ->
            preferences[key(domain)] = DetailCardOrderSync.encode(order)
            markPending(preferences, domain, true)
        }
        pushPending()
    }

    suspend fun reset(domain: DetailCardDomain) {
        syncMutex.withLock { adoptLocalOrdersOnce() }
        dataStore.edit { preferences ->
            preferences.remove(key(domain))
            markPending(preferences, domain, true)
        }
        pushPending()
    }

    /** Pushes pending changes, then adopts the server's order for every other page. */
    suspend fun sync() {
        syncMutex.withLock {
            adoptLocalOrdersOnce()
            pushPendingLocked()
            pullLocked()
        }
    }

    private suspend fun pushPending() {
        syncMutex.withLock { pushPendingLocked() }
    }

    /**
     * Before the first sync of this install the local orders are the only copy: send them instead
     * of overwriting them with the server's default.
     */
    private suspend fun adoptLocalOrdersOnce() {
        dataStore.edit { preferences ->
            if (preferences[ADOPTED_KEY] == true) return@edit
            DetailCardDomain.entries.forEach { domain ->
                if (preferences[key(domain)] != null) markPending(preferences, domain, true)
            }
            preferences[ADOPTED_KEY] = true
        }
    }

    private suspend fun pushPendingLocked() {
        val before = dataStore.data.first()
        val pending = pendingDomains(before)
        if (pending.isEmpty()) return
        val project = DetailCardOrderSync.decode(before[key(DetailCardDomain.PROJECT)])
        val yarn = DetailCardOrderSync.decode(before[key(DetailCardDomain.YARN)])
        try {
            accountApi.updateAccount(
                UpdateAccountRequest(
                    detailCardOrder = DetailCardOrderSync.patch(pending, project, yarn)
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Offline, signed out or an older server: the change stays pending for the next sync.
            return
        }
        dataStore.edit { preferences ->
            val sent = mapOf(DetailCardDomain.PROJECT to project, DetailCardDomain.YARN to yarn)
            pending.forEach { domain ->
                // A newer change made while the request was in flight stays pending.
                val now = DetailCardOrderSync.decode(preferences[key(domain)])
                if (now == sent[domain]) markPending(preferences, domain, false)
            }
        }
    }

    private suspend fun pullLocked() {
        val server =
            try {
                accountApi.account().data.detailCardOrder
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            } ?: return
        dataStore.edit { preferences ->
            val pending = pendingDomains(preferences)
            DetailCardDomain.entries.forEach { domain ->
                val serverOrder =
                    when (domain) {
                        DetailCardDomain.PROJECT -> server.project
                        DetailCardDomain.YARN -> server.yarn
                    }
                val merged =
                    DetailCardOrderSync.reconcile(
                        domain,
                        DetailCardOrderSync.decode(preferences[key(domain)]),
                        domain in pending,
                        serverOrder,
                    )
                if (merged.local == null) {
                    preferences.remove(key(domain))
                } else {
                    preferences[key(domain)] = DetailCardOrderSync.encode(merged.local)
                }
            }
        }
    }

    private fun pendingDomains(preferences: Preferences): Set<DetailCardDomain> {
        val names = preferences[PENDING_KEY]?.split(",").orEmpty().toSet()
        return DetailCardDomain.entries.filter { it.storageName in names }.toSet()
    }

    private fun markPending(
        preferences: MutablePreferences,
        domain: DetailCardDomain,
        pending: Boolean,
    ) {
        val names = pendingDomains(preferences).map { it.storageName }.toMutableSet()
        if (pending) names.add(domain.storageName) else names.remove(domain.storageName)
        if (names.isEmpty()) {
            preferences.remove(PENDING_KEY)
        } else {
            preferences[PENDING_KEY] = names.sorted().joinToString(",")
        }
    }

    private fun orderFlow(domain: DetailCardDomain) =
        dataStore.data.map { preferences ->
            DetailCardOrder.sanitize(domain, DetailCardOrderSync.decode(preferences[key(domain)]))
        }

    private fun key(domain: DetailCardDomain) =
        stringPreferencesKey("detail_card_order_${domain.storageName}")

    private companion object {
        val PENDING_KEY = stringPreferencesKey("detail_card_order_pending")
        val ADOPTED_KEY = booleanPreferencesKey("detail_card_order_adopted")
    }
}
