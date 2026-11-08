package blue.anika.wolle.data.imports

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import blue.anika.wolle.data.repository.ImportedProjectPreview
import blue.anika.wolle.data.repository.ImportedYarnPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/** What a link is imported as. */
enum class ImportKind {
    Project,
    Yarn,
}

/** The import of one link, kept until the user saves or dismisses its review. */
@Serializable
sealed interface PendingImport {
    val url: String

    @Serializable
    @SerialName("running")
    data class Running(override val url: String) : PendingImport

    @Serializable
    @SerialName("project")
    data class ProjectReady(override val url: String, val preview: ImportedProjectPreview) :
        PendingImport

    @Serializable
    @SerialName("yarn")
    data class YarnReady(override val url: String, val preview: ImportedYarnPreview) : PendingImport

    /**
     * Enough of the failure to classify it as the dialog does: a network error ([offline]), or the
     * server's HTTP [status] and error [body] (which carries the `code`).
     */
    @Serializable
    @SerialName("failed")
    data class Failed(
        override val url: String,
        val offline: Boolean = false,
        val status: Int? = null,
        val body: String? = null,
    ) : PendingImport {
        /** The failure again, as the importer raised it, for the dialog's classifier. */
        fun toThrowable(): Throwable =
            when {
                offline -> IOException("import failed offline")
                status != null ->
                    HttpException(
                        Response.error<Any>(
                            status,
                            body.orEmpty().toResponseBody("application/json".toMediaType()),
                        )
                    )
                else -> IllegalStateException("import failed")
            }

        companion object {
            fun of(url: String, failure: Throwable): Failed =
                when (failure) {
                    is IOException -> Failed(url, offline = true)
                    is HttpException ->
                        Failed(
                            url,
                            status = failure.code(),
                            body =
                                runCatching { failure.response()?.errorBody()?.string() }
                                    .getOrNull(),
                        )
                    else -> Failed(url)
                }
        }
    }
}

/**
 * Runs link imports as expedited background work ([ImportWorker]) instead of in a screen: an AI
 * import takes up to a few minutes, and an app in the background is frozen or killed meanwhile. The
 * result is stored in a file until the user saves or dismisses the review, so it survives the app
 * being closed; [ImportNotifier] tells about it when nobody is looking at the import dialog.
 */
@Singleton
class BackgroundImports
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val states = ImportKind.entries.associateWith { MutableStateFlow(read(it)) }
    private val watchers = ImportKind.entries.associateWith { AtomicInteger(0) }

    fun state(kind: ImportKind): StateFlow<PendingImport?> = states.getValue(kind).asStateFlow()

    /** Whether a screen is waiting for this import right now (then no notification is needed). */
    fun isWatched(kind: ImportKind): Boolean = watchers.getValue(kind).get() > 0

    /**
     * Imports [url] in the background and waits for the result; joins the import of the same link
     * that is already running or finished, so a screen that comes back picks it up.
     */
    suspend fun run(kind: ImportKind, url: String): PendingImport {
        val current = states.getValue(kind).value
        if (current?.url != url || current is PendingImport.Failed) start(kind, url)
        watchers.getValue(kind).incrementAndGet()
        try {
            val work = workManager.getWorkInfosForUniqueWorkFlow(uniqueName(kind))
            return combine(states.getValue(kind), work) { state, infos ->
                    val finished = infos.lastOrNull()?.state?.isFinished == true
                    when {
                        state == null || state.url != url -> PendingImport.Failed(url)
                        state !is PendingImport.Running -> state
                        // The work ended without a result (cancelled by the system, say).
                        finished && infos.isNotEmpty() -> PendingImport.Failed(url)
                        else -> null
                    }
                }
                .first { it != null }!!
        } finally {
            watchers.getValue(kind).decrementAndGet()
        }
    }

    private fun start(kind: ImportKind, url: String) {
        update(kind, PendingImport.Running(url))
        val work =
            OneTimeWorkRequestBuilder<ImportWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(
                    workDataOf(ImportWorker.KEY_KIND to kind.name, ImportWorker.KEY_URL to url)
                )
                .build()
        workManager.enqueueUniqueWork(uniqueName(kind), ExistingWorkPolicy.REPLACE, work)
    }

    /**
     * Stores the result of [url], unless the user started another import or cancelled meanwhile.
     */
    fun complete(kind: ImportKind, result: PendingImport): Boolean {
        val current = states.getValue(kind).value
        if (current !is PendingImport.Running || current.url != result.url) return false
        update(kind, result)
        return true
    }

    /** The review was saved or dismissed. */
    fun clear(kind: ImportKind) = update(kind, null)

    /** Stops a running import and forgets it. */
    fun cancel(kind: ImportKind) {
        workManager.cancelUniqueWork(uniqueName(kind))
        clear(kind)
    }

    private fun update(kind: ImportKind, value: PendingImport?) {
        states.getValue(kind).value = value
        val file = file(kind)
        runCatching {
            if (value == null) file.delete()
            else {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(PendingImport.serializer(), value))
            }
        }
    }

    private fun read(kind: ImportKind): PendingImport? =
        runCatching {
                file(kind)
                    .takeIf { it.isFile }
                    ?.readText()
                    ?.let {
                        json.decodeFromString(PendingImport.serializer(), it)
                    }
            }
            .getOrNull()

    private fun file(kind: ImportKind) =
        File(context.filesDir, "imports/pending-${kind.name.lowercase()}.json")

    private fun uniqueName(kind: ImportKind) = "link-import-${kind.name.lowercase()}"
}
