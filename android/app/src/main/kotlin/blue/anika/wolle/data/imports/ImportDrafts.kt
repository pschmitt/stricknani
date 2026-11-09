package blue.anika.wolle.data.imports

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.v2.ImportDraftRequest
import blue.anika.wolle.data.api.v2.ImportJobDto
import blue.anika.wolle.data.api.v2.ImportsV2Api
import blue.anika.wolle.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/** What a link is imported as; the draft API's `target`. */
enum class ImportTarget(val wire: String) {
    Project("project"),
    Yarn("yarn"),
}

/** How often the server's draft is asked while the user is looking at it. */
const val DRAFT_POLL_MILLIS = 2_000L

/** How often the "waiting for review" cards ask when nothing is running. */
private const val IDLE_POLL_MILLIS = 30_000L

/** The part of the draft API a [LinkImportSession] needs. */
interface ImportDraftSource {
    suspend fun start(url: String, target: ImportTarget): ImportJobDto

    suspend fun get(id: String): ImportJobDto

    suspend fun delete(id: String)

    /** The user looked at this draft: no notification for it later. */
    fun seen(draft: ImportJobDto)
}

/**
 * The imports the server runs for this user (`/api/v2/imports/drafts`): the server fetches and
 * reads the link, keeps the result until the user saves or dismisses it, and runs on while the app
 * is closed. The app starts a draft, polls it, and shows the waiting ones as cards; a finished one
 * that nobody looked at becomes a notification ([draftsToNotify]).
 */
@Singleton
class ImportDrafts
@Inject
constructor(
    private val api: ImportsV2Api,
    private val capabilities: ApiCapabilities,
    private val settings: SettingsRepository,
    private val workManager: WorkManager,
    @ApplicationContext context: Context,
) : ImportDraftSource {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _drafts = MutableStateFlow<List<ImportJobDto>>(emptyList())

    /** The drafts as last fetched, newest first. */
    val drafts: StateFlow<List<ImportJobDto>> = _drafts.asStateFlow()

    /** Starts the import of [url] on the server and returns its (running) draft. */
    override suspend fun start(url: String, target: ImportTarget): ImportJobDto {
        capabilities.resolve()
        val draft =
            api.createDraft(
                    ImportDraftRequest(
                        url = url,
                        useAi = capabilities.current.importsAi,
                        target = target.wire,
                        locale = Locale.getDefault().language.takeIf { it.isNotBlank() },
                    )
                )
                .data
        _drafts.value = listOf(draft) + _drafts.value.filter { it.id != draft.id }
        schedulePoll()
        return draft
    }

    /**
     * Looks again in [POLL_WORK_DELAY_SECONDS] seconds ([ImportDraftsWorker]): the app may be in
     * the background by then, and this is what tells the user that the import is ready.
     */
    fun schedulePoll() {
        workManager.enqueueUniqueWork(
            POLL_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ImportDraftsWorker>()
                .setInitialDelay(POLL_WORK_DELAY_SECONDS, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build(),
        )
    }

    /**
     * Posts a notification for each draft that finished since it was last shown or announced,
     * unless the app is on screen. Returns whether a draft is still running, so the caller looks
     * again.
     */
    suspend fun announceFinished(notifier: ImportNotifier, appVisible: Boolean): Boolean {
        val news = takeNewlyFinished()
        if (!appVisible) news.forEach { notifier.finished(it) }
        return _drafts.value.any { it.isRunning }
    }

    /** One draft, with its `preview` once it is ready. */
    override suspend fun get(id: String): ImportJobDto = api.getDraft(id).data

    /** Dismisses a draft; it disappears from the cards at once. */
    override suspend fun delete(id: String) {
        val before = _drafts.value
        _drafts.value = before.filter { it.id != id }
        try {
            api.deleteDraft(id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: retrofit2.HttpException) {
            // Already gone is as good as deleted.
            if (failure.code() != 404) {
                _drafts.value = before
                throw failure
            }
        } catch (failure: Exception) {
            _drafts.value = before
            throw failure
        }
    }

    /** The drafts of this user, fetched now. Empty without a server that has the draft API. */
    private suspend fun fetch(): List<ImportJobDto> {
        if (settings.isLocalMode || !settings.isConfigured.value || !capabilities.useV2()) {
            return emptyList()
        }
        return api.listDrafts().data
    }

    /**
     * Keeps [drafts] current while collected: asks right away, then every two seconds while one is
     * running and rarely otherwise. What the app shows is also what the user has seen, so finished
     * drafts are marked seen and never turn into a notification.
     */
    fun watch(): Flow<List<ImportJobDto>> = flow {
        while (true) {
            val list =
                try {
                    fetch().also {
                        _drafts.value = it
                        markSeen(it, complete = true)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    _drafts.value
                }
            emit(list)
            delay(if (list.any { it.isRunning }) DRAFT_POLL_MILLIS else IDLE_POLL_MILLIS)
        }
    }

    override fun seen(draft: ImportJobDto) = markSeen(listOf(draft))

    fun markSeen(drafts: List<ImportJobDto>, complete: Boolean = false) {
        val finished = drafts.filter { !it.isRunning }.map { it.id }
        val seen = seenIds()
        // A complete list also tells which ids are gone for good.
        val all = if (complete) drafts.map { it.id }.toSet() else null
        val next = (seen + finished).filter { all == null || it in all }.toSet()
        if (next != seen) prefs.edit().putStringSet(KEY_SEEN, next).apply()
    }

    /**
     * Drafts that finished since the app last showed or announced them, for the notification. The
     * first call only records what exists: old drafts are no news.
     */
    suspend fun takeNewlyFinished(): List<ImportJobDto> {
        val list = fetch()
        _drafts.value = list
        if (!prefs.getBoolean(KEY_BASELINED, false)) {
            prefs.edit().putBoolean(KEY_BASELINED, true).apply()
            markSeen(list, complete = true)
            return emptyList()
        }
        val news = draftsToNotify(list, seenIds())
        markSeen(list, complete = true)
        return news
    }

    private fun seenIds(): Set<String> = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()

    private companion object {
        const val PREFS_NAME = "import_drafts"
        const val KEY_SEEN = "seen"
        const val KEY_BASELINED = "baselined"
        const val POLL_WORK_NAME = "import-drafts-poll"
        const val POLL_WORK_DELAY_SECONDS = 20L
    }
}

/** The drafts that are done ([ImportJobDto.isRunning] false) and not yet in [seen]. */
fun draftsToNotify(drafts: List<ImportJobDto>, seen: Set<String>): List<ImportJobDto> =
    drafts.filter {
        !it.isRunning && it.id !in seen
    }
