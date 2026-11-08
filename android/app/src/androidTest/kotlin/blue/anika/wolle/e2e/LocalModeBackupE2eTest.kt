package blue.anika.wolle.e2e

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import blue.anika.wolle.data.api.ProjectsApi
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.export.DataExportWorker
import blue.anika.wolle.data.export.DataRestoreWorker
import blue.anika.wolle.data.export.ExportFormat
import blue.anika.wolle.localserver.LocalServerNative
import blue.anika.wolle.sync.RustReplayTestEntryPoint
import blue.anika.wolle.ui.onboarding.OnboardingViewModel
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Local-only mode (T130, `docs/local-only.md`) end to end: onboarding "Only on this phone" starts
 * the server inside the app, the app's API writes to it, and a data download and a restore through
 * the app's own workers round-trip a project. Needs a build with the server library
 * (`android/local-server-libs`); skipped without one.
 */
@RunWith(AndroidJUnit4::class)
class LocalModeBackupE2eTest : StricknaniE2eTest() {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val graph: RustReplayTestEntryPoint
        get() = EntryPointAccessors.fromApplication(context, RustReplayTestEntryPoint::class.java)

    @Test
    fun onlyOnThisPhoneExportsAndRestoresAProject() {
        assumeTrue("this build has no in-app server library", LocalServerNative.available)
        composeRule.waitUntil(120_000) {
            composeRule.onAllNodesWithTag("onboarding-local").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule
            .onNodeWithTag("onboarding-local")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        try {
            waitForText("Projects", timeoutMillis = 120_000)
        } catch (failure: AssertionError) {
            // Only the local-mode tag and crashes: no request logs (they could carry the token).
            // One line: CI's failure summary keeps only the first line of a message.
            val screen =
                composeRule
                    .onAllNodes(hasText("", substring = true), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                    .joinToString(" | ") { it.text }
            val log = localModeLog().lines().filter { it.isNotBlank() }.joinToString(" ⏎ ")
            throw AssertionError("Local mode did not open. Screen: [$screen] Log: [$log]", failure)
        }

        val name = "Local backup ${UUID.randomUUID().toString().take(8)}"
        val api = graph.projectsApi()
        val workDir = File(context.cacheDir, "local-backup-e2e").apply { deleteRecursively() }
        workDir.mkdirs()
        try {
            // A photo too: the server stores media through a hard link elsewhere, which Android
            // forbids in app storage (the in-app server falls back to a no-overwrite rename).
            runBlocking {
                val project = api.createProject(ProjectWriteRequest(name = name))
                api.uploadTitleImage(project.id, pngPart())
            }
            assertEquals(1, runBlocking { named(api, name) })

            val archive = File(workDir, "local.tar.gz")
            val export =
                runWorker(
                    OneTimeWorkRequestBuilder<DataExportWorker>()
                        .setInputData(
                            workDataOf(
                                DataExportWorker.KEY_URI to Uri.fromFile(archive).toString(),
                                DataExportWorker.KEY_NAME to archive.name,
                                DataExportWorker.KEY_FORMAT to ExportFormat.TarGz.name,
                            )
                        )
                        .build()
                )
            assertEquals(
                "export failed: ${export.outputData.getString(DataExportWorker.KEY_FAILURE)}",
                WorkInfo.State.SUCCEEDED,
                export.state,
            )
            val bytes = archive.readBytes()
            assertTrue("no gzip archive", bytes.size > 2 && bytes[0] == 0x1f.toByte())

            val restore =
                runWorker(
                    OneTimeWorkRequestBuilder<DataRestoreWorker>()
                        .setInputData(
                            workDataOf(
                                DataRestoreWorker.KEY_URI to Uri.fromFile(archive).toString(),
                                DataRestoreWorker.KEY_TOTAL to archive.length(),
                            )
                        )
                        .build()
                )
            assertEquals("restore failed", WorkInfo.State.SUCCEEDED, restore.state)
            // A restore adds copies: the project is there twice now, both with their photo.
            assertEquals(2, runBlocking { named(api, name) })
            runBlocking {
                val copies = api.listProjects(page = 1).items.filter { it.name == name }
                copies.forEach { copy ->
                    assertTrue(
                        "project ${copy.id} lost its photo",
                        api.getProject(copy.id).images.any { it.isTitleImage },
                    )
                }
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** A small, valid PNG (a gradient), so the server's image pipeline really decodes it. */
    private fun pngPart(): MultipartBody.Part {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (x in 0 until 64) for (y in 0 until 64) bitmap.setPixel(
            x,
            y,
            Color.rgb(x * 4, y * 4, 128),
        )
        val bytes =
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return MultipartBody.Part.createFormData(
            "file",
            "cover.png",
            bytes.toByteArray().toRequestBody("image/png".toMediaType()),
        )
    }

    private fun localModeLog(): String {
        val command = "logcat -d -t 200 -s ${OnboardingViewModel.LOCAL_LOG_TAG}:V AndroidRuntime:E"
        val output =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use {
            it.readText().takeLast(4_000)
        }
    }

    private suspend fun named(api: ProjectsApi, name: String): Int {
        var count = 0
        var page = 1
        while (page <= MAX_PAGES) {
            val result = api.listProjects(page = page)
            count += result.items.count { it.name == name }
            if (!result.hasMore) return count
            page++
        }
        error("Project list exceeded $MAX_PAGES pages")
    }

    private fun runWorker(request: OneTimeWorkRequest): WorkInfo {
        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(request).result.get(WORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val deadline = SystemClock.elapsedRealtime() + WORK_TIMEOUT_SECONDS * 1_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val info =
                requireNotNull(workManager.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS)) {
                    "Worker disappeared before it finished"
                }
            if (info.state.isFinished) return info
            SystemClock.sleep(500)
        }
        error("Worker did not finish within ${WORK_TIMEOUT_SECONDS}s")
    }

    private companion object {
        const val WORK_TIMEOUT_SECONDS = 240L
        const val MAX_PAGES = 50
    }
}
