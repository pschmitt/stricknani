package blue.anika.wolle.sync

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import blue.anika.wolle.data.api.ProjectsApi
import blue.anika.wolle.data.api.YarnsApi
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.StepWriteRequest
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import blue.anika.wolle.data.db.AppDatabase
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.dao.ProjectDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.dao.YarnDao
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.SyncStateEntity
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException

/** Runs the app's Room repositories and Hilt worker against an opt-in disposable Rust fixture. */
@RunWith(AndroidJUnit4::class)
class RustBackendReplayIntegrationTest {
    @Test
    fun queuedProjectAndYarnWritesReplaySyncDeleteAndResolveConflicts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixtureFile = File(context.filesDir, FIXTURE_FILENAME)
        assumeTrue("Rust replay fixture was not provisioned", fixtureFile.isFile)

        val fixture =
            try {
                JSONObject(fixtureFile.readText())
            } finally {
                fixtureFile.delete()
            }
        val baseUrl = fixture.getString("url").trim().trimEnd('/')
        val email = fixture.getString("email")
        val password = fixture.getString("password")
        require(baseUrl.isNotBlank() && email.isNotBlank() && password.isNotBlank())

        val graph =
            EntryPointAccessors.fromApplication(
                context,
                RustReplayTestEntryPoint::class.java,
            )
        val settings = graph.settingsRepository()
        val originalCredentials = settings.credentials.value
        val http = OkHttpClient.Builder().followRedirects(false).build()
        val nonce = UUID.randomUUID().toString().take(12)
        val projectPrefix = "Rust replay $nonce project"
        val yarnPrefix = "Rust replay $nonce yarn"
        var browserSession: BrowserSession? = null
        val projectIds = mutableSetOf<Int>()
        val yarnIds = mutableSetOf<Int>()
        val temporaryProjectIds = mutableSetOf<Int>()
        val temporaryYarnIds = mutableSetOf<Int>()
        val syncStateDao = graph.syncStateDao()
        val originalProjectCursor = syncStateDao.getCursor(PROJECT_SYNC_TYPE)
        val originalYarnCursor = syncStateDao.getCursor(YARN_SYNC_TYPE)

        try {
            browserSession = loginForRevocableSession(http, baseUrl, email, password)
            settings.save(baseUrl, browserSession.token)

            // Repository mutations are local-only. Pointing the app at an unreachable URL during
            // this phase makes any accidental eager network write fail immediately.
            settings.save(OFFLINE_URL, browserSession.token)
            val yarnRepository = graph.yarnRepository()
            val projectRepository = graph.projectRepository()
            val pendingDao = graph.pendingMutationDao()
            val projectDao = graph.projectDao()
            val yarnDao = graph.yarnDao()

            val tempYarnId =
                yarnRepository.createYarn(
                    YarnWriteRequest(name = "$yarnPrefix created", brand = "Replay fixture")
                )
            temporaryYarnIds += tempYarnId
            yarnRepository.updateYarn(
                tempYarnId,
                YarnWriteRequest(name = "$yarnPrefix updated", brand = "Replay fixture"),
            )

            val tempProjectId =
                projectRepository.createProject(
                    ProjectWriteRequest(
                        name = "$projectPrefix created",
                        steps = listOf(StepWriteRequest(title = "Queued cast on")),
                    )
                )
            temporaryProjectIds += tempProjectId
            projectRepository.updateProject(
                tempProjectId,
                ProjectWriteRequest(
                    name = "$projectPrefix updated",
                    steps = listOf(StepWriteRequest(title = "Replayed cast on")),
                ),
            )

            assertTrue(tempYarnId < 0)
            assertTrue(tempProjectId < 0)
            assertEquals("$yarnPrefix updated", yarnDao.getById(tempYarnId)?.name)
            assertEquals("$projectPrefix updated", projectDao.getById(tempProjectId)?.name)
            val queuedBeforeReplay = pendingDao.getAll()
            assertTrue(queuedBeforeReplay.any { it.entityType == MutationEntityType.YARN })
            assertTrue(queuedBeforeReplay.any { it.entityType == MutationEntityType.PROJECT })

            settings.save(baseUrl, browserSession.token)
            assertEquals(WorkInfo.State.SUCCEEDED, runReplay(context))

            assertNull(projectDao.getById(tempProjectId))
            assertNull(yarnDao.getById(tempYarnId))
            assertTrue(pendingDao.getAll().isEmpty())
            val project =
                projectRepository.observeAll().first().single {
                    it.name == "$projectPrefix updated"
                }
            val yarn =
                yarnRepository.observeAll().first().single { it.name == "$yarnPrefix updated" }
            projectIds += project.id
            yarnIds += yarn.id
            assertTrue(project.id > 0)
            assertTrue(yarn.id > 0)
            assertEquals(
                "Replayed cast on",
                projectRepository.decodeDetail(project).steps.single().title,
            )
            assertEquals("$projectPrefix updated", graph.projectsApi().getProject(project.id).name)
            assertEquals("$yarnPrefix updated", graph.yarnsApi().getYarn(yarn.id).name)

            // Pull the Rust server's current records through the app's normal delta-sync paths.
            projectRepository.sync()
            yarnRepository.sync()
            assertNotNull(projectDao.getById(project.id))
            assertNotNull(yarnDao.getById(yarn.id))

            // A server edit after the cached snapshot makes the queued app update conflict. The
            // worker adopts the current Rust DTO and marks the outbox item resolved.
            val conflictTempId =
                projectRepository.createProject(
                    ProjectWriteRequest(name = "$projectPrefix conflict-created")
                )
            temporaryProjectIds += conflictTempId
            assertEquals(WorkInfo.State.SUCCEEDED, runReplay(context))
            val conflictProject =
                projectRepository.observeAll().first().single {
                    it.name == "$projectPrefix conflict-created"
                }
            projectIds += conflictProject.id
            val remoteName = "$projectPrefix remote-edit"
            graph
                .projectsApi()
                .updateProject(
                    conflictProject.id,
                    ProjectWriteRequest(name = remoteName),
                )
            projectRepository.updateProject(
                conflictProject.id,
                ProjectWriteRequest(name = "$projectPrefix stale-local-edit"),
            )
            assertEquals(WorkInfo.State.SUCCEEDED, runReplay(context))

            val adopted = projectRepository.decodeDetail(projectDao.getById(conflictProject.id)!!)
            assertEquals(remoteName, adopted.name)
            assertEquals(remoteName, graph.projectsApi().getProject(conflictProject.id).name)
            val resolved =
                pendingDao.observeFailed().first().single {
                    it.entityType == MutationEntityType.PROJECT &&
                        it.localId == conflictProject.id &&
                        it.isConflict
                }
            assertFalse(resolved.lastErrorMessage.isNullOrBlank())
            assertEquals(WorkInfo.State.SUCCEEDED, runReplay(context))
            assertEquals(remoteName, graph.projectsApi().getProject(conflictProject.id).name)

            projectRepository.deleteProject(project.id)
            yarnRepository.deleteYarn(yarn.id)
            assertNull(projectDao.getById(project.id))
            assertNull(yarnDao.getById(yarn.id))
            assertEquals(WorkInfo.State.SUCCEEDED, runReplay(context))
            assertEquals(
                404,
                expectHttpFailure { graph.projectsApi().getProject(project.id) }.code(),
            )
            assertEquals(404, expectHttpFailure { graph.yarnsApi().getYarn(yarn.id) }.code())
            assertTrue(pendingDao.getAll().isEmpty())
        } finally {
            var cleanupFailed = false
            try {
                if (browserSession != null) {
                    try {
                        settings.save(baseUrl, browserSession.token)
                        cleanupRemote(
                            graph.projectsApi(),
                            graph.yarnsApi(),
                            projectPrefix,
                            yarnPrefix,
                            projectIds,
                            yarnIds,
                        )
                    } catch (_: Exception) {
                        cleanupFailed = true
                    }
                    try {
                        logout(http, baseUrl, browserSession)
                    } catch (_: Exception) {
                        cleanupFailed = true
                    }
                }
                try {
                    cleanupLocal(
                        graph.projectRepository(),
                        graph.yarnRepository(),
                        graph.projectDao(),
                        graph.yarnDao(),
                        graph.pendingMutationDao(),
                        projectPrefix,
                        yarnPrefix,
                        projectIds + temporaryProjectIds,
                        yarnIds + temporaryYarnIds,
                    )
                } catch (_: Exception) {
                    cleanupFailed = true
                }
                try {
                    restoreSyncCursor(
                        graph.appDatabase(),
                        syncStateDao,
                        PROJECT_SYNC_TYPE,
                        originalProjectCursor,
                    )
                    restoreSyncCursor(
                        graph.appDatabase(),
                        syncStateDao,
                        YARN_SYNC_TYPE,
                        originalYarnCursor,
                    )
                } catch (_: Exception) {
                    cleanupFailed = true
                }
            } finally {
                try {
                    if (originalCredentials.isValid) {
                        settings.save(originalCredentials.serverUrl, originalCredentials.apiToken)
                    } else {
                        settings.signOut()
                    }
                } finally {
                    fixtureFile.delete()
                }
            }
            if (cleanupFailed) error("Rust replay fixture cleanup failed")
        }

        assertFalse(fixtureFile.exists())
    }

    private suspend fun runReplay(context: Context): WorkInfo.State {
        val request = OneTimeWorkRequestBuilder<WriteReplayWorker>().build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(request).result.get(WORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val deadline = SystemClock.elapsedRealtime() + WORK_TIMEOUT_SECONDS * 1_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val info =
                requireNotNull(workManager.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS)) {
                    "Write replay work disappeared before it finished"
                }
            if (info.state.isFinished) return info.state
            SystemClock.sleep(WORK_POLL_MILLIS)
        }
        error("Write replay did not finish before the timeout")
    }

    private fun loginForRevocableSession(
        client: OkHttpClient,
        baseUrl: String,
        email: String,
        password: String,
    ): BrowserSession {
        val loginUrl = "$baseUrl/login"
        val csrfCookie =
            client.newCall(Request.Builder().url(loginUrl).get().build()).execute().use { response
                ->
                assertEquals(200, response.code)
                response
                    .headers("Set-Cookie")
                    .firstOrNull { it.substringBefore('=').endsWith("stricknani_login_csrf") }
                    ?.substringBefore(';') ?: error("Rust login omitted its CSRF cookie")
            }
        val csrfToken = csrfCookie.substringAfter('=')
        val sessionCookie =
            client
                .newCall(
                    Request.Builder()
                        .url(loginUrl)
                        .header("Cookie", csrfCookie)
                        .post(
                            FormBody.Builder()
                                .add("email", email)
                                .add("password", password)
                                .add("csrf", csrfToken)
                                .build()
                        )
                        .build()
                )
                .execute()
                .use { response ->
                    assertTrue(response.code in 300..399)
                    response
                        .headers("Set-Cookie")
                        .firstOrNull { it.substringBefore('=').endsWith("stricknani_session") }
                        ?.substringBefore(';') ?: error("Rust login omitted its session cookie")
                }
        val token = sessionCookie.substringAfter('=')
        return BrowserSession(sessionCookie, token, csrfFor(token))
    }

    private fun logout(client: OkHttpClient, baseUrl: String, session: BrowserSession) {
        client
            .newCall(
                Request.Builder()
                    .url("$baseUrl/logout")
                    .header("Cookie", session.cookie)
                    .post(FormBody.Builder().add("csrf", session.csrf).build())
                    .build()
            )
            .execute()
            .use { response -> assertTrue(response.code in 300..399) }
    }

    private fun csrfFor(token: String): String {
        val value = "stricknani browser csrf v1\u0000$token"
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private suspend fun cleanupRemote(
        projectsApi: ProjectsApi,
        yarnsApi: YarnsApi,
        projectPrefix: String,
        yarnPrefix: String,
        knownProjectIds: Set<Int>,
        knownYarnIds: Set<Int>,
    ) {
        val projectIds = knownProjectIds.toMutableSet()
        var projectPage = 1
        var projectPagesTruncated = false
        while (true) {
            val page = projectsApi.listProjects(page = projectPage)
            projectIds += page.items.filter { it.name.startsWith(projectPrefix) }.map { it.id }
            if (!page.hasMore) break
            if (projectPage >= MAX_CLEANUP_PAGES) {
                projectPagesTruncated = true
                break
            }
            projectPage++
        }
        projectIds.forEach { id -> deleteProjectIfPresent(projectsApi, id) }

        val yarnIds = knownYarnIds.toMutableSet()
        var yarnPage = 1
        var yarnPagesTruncated = false
        while (true) {
            val page = yarnsApi.listYarns(page = yarnPage)
            yarnIds += page.items.filter { it.name.startsWith(yarnPrefix) }.map { it.id }
            if (!page.hasMore) break
            if (yarnPage >= MAX_CLEANUP_PAGES) {
                yarnPagesTruncated = true
                break
            }
            yarnPage++
        }
        yarnIds.forEach { id -> deleteYarnIfPresent(yarnsApi, id) }
        check(!projectPagesTruncated && !yarnPagesTruncated) {
            "Rust replay cleanup exceeded its bounded listing pages"
        }
    }

    private suspend fun deleteProjectIfPresent(projectsApi: ProjectsApi, id: Int) {
        try {
            projectsApi.deleteProject(id)
        } catch (failure: HttpException) {
            if (failure.code() != 404) throw failure
        }
    }

    private suspend fun deleteYarnIfPresent(yarnsApi: YarnsApi, id: Int) {
        try {
            yarnsApi.deleteYarn(id)
        } catch (failure: HttpException) {
            if (failure.code() != 404) throw failure
        }
    }

    private suspend fun cleanupLocal(
        projectRepository: ProjectRepository,
        yarnRepository: YarnRepository,
        projectDao: ProjectDao,
        yarnDao: YarnDao,
        pendingDao: PendingMutationDao,
        projectPrefix: String,
        yarnPrefix: String,
        knownProjectIds: Set<Int>,
        knownYarnIds: Set<Int>,
    ) {
        val projectIds =
            (knownProjectIds +
                    projectRepository
                        .observeAll()
                        .first()
                        .filter { it.name.startsWith(projectPrefix) }
                        .map { it.id })
                .toSet()
        val yarnIds =
            (knownYarnIds +
                    yarnRepository
                        .observeAll()
                        .first()
                        .filter { it.name.startsWith(yarnPrefix) }
                        .map { it.id })
                .toSet()
        projectDao.deleteByIds(projectIds.toList())
        yarnDao.deleteByIds(yarnIds.toList())
        projectIds.forEach { pendingDao.deleteResolvedConflicts(MutationEntityType.PROJECT, it) }
        yarnIds.forEach { pendingDao.deleteResolvedConflicts(MutationEntityType.YARN, it) }
        pendingDao
            .getAll()
            .filter {
                (it.entityType == MutationEntityType.PROJECT && it.localId in projectIds) ||
                    (it.entityType == MutationEntityType.YARN && it.localId in yarnIds)
            }
            .forEach { pendingDao.delete(it.id) }
    }

    private suspend fun restoreSyncCursor(
        database: AppDatabase,
        syncStateDao: SyncStateDao,
        entityType: String,
        cursor: String?,
    ) {
        if (cursor != null) {
            syncStateDao.setCursor(SyncStateEntity(entityType, cursor))
        } else {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM sync_state WHERE entityType = ?",
                arrayOf<Any?>(entityType),
            )
        }
    }

    private suspend fun expectHttpFailure(block: suspend () -> Any): HttpException =
        try {
            block()
            error("Expected Rust API to return HTTP 404")
        } catch (failure: HttpException) {
            failure
        }

    private data class BrowserSession(val cookie: String, val token: String, val csrf: String)

    private companion object {
        const val FIXTURE_FILENAME = "rust-replay-fixture.json"
        const val OFFLINE_URL = "http://127.0.0.1:1"
        const val WORK_TIMEOUT_SECONDS = 90L
        const val WORK_POLL_MILLIS = 100L
        const val MAX_CLEANUP_PAGES = 100
        const val PROJECT_SYNC_TYPE = "project"
        const val YARN_SYNC_TYPE = "yarn"
    }
}
