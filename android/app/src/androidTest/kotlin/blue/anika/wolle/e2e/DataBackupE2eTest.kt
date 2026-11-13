package blue.anika.wolle.e2e

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText as hasTextMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import blue.anika.wolle.data.api.ProjectsApi
import blue.anika.wolle.data.api.YarnsApi
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.StepWriteRequest
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import blue.anika.wolle.data.export.DataExportWorker
import blue.anika.wolle.data.export.DataRestoreWorker
import blue.anika.wolle.data.export.ExportFormat
import blue.anika.wolle.data.export.RestoreFailure
import blue.anika.wolle.sync.RustReplayTestEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Data export and restore (`docs/data-export.md`) against the disposable Rust fixture.
 *
 * Export and restore run through the app's own workers ([DataExportWorker], [DataRestoreWorker]),
 * so the repositories, the streaming code and the Retrofit APIs are the production ones. The system
 * file picker is not driven (unreliable on CI emulators): the workers get `file:` Uris in the app's
 * cache directory instead of a Storage Access Framework Uri.
 *
 * The "item shared with me" part of the export contract needs a second account. The workflow seeds
 * one (`seed --peer-token-file`, an ordinary user registered through the fixture's open sign-up)
 * and passes its PAT as the `e2e_peer_token` instrumentation argument. The peer is driven with
 * plain `HttpURLConnection` calls, because the app's graph only holds the main user's token.
 * [sharedProjectIsExportedWithItsAccessAndRestoredAsOwnCopy] is skipped (JUnit assumption) when the
 * argument is absent, so local runs without a peer still pass.
 */
@RunWith(AndroidJUnit4::class)
class DataBackupE2eTest : StricknaniE2eTest() {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val graph: RustReplayTestEntryPoint
        get() = EntryPointAccessors.fromApplication(context, RustReplayTestEntryPoint::class.java)

    @Test
    fun exportAsTarGzAndZipThenRestoreAddsDuplicates() {
        connectToFixture()
        val nonce = UUID.randomUUID().toString().take(8)
        val projectName = "Backup e2e $nonce project"
        val yarnName = "Backup e2e $nonce yarn"
        val stepTitle = "Backup e2e $nonce cast on"
        val workDir = freshWorkDir()
        val projectsApi = graph.projectsApi()
        val yarnsApi = graph.yarnsApi()

        try {
            runBlocking {
                // 1. Seed through the API: a yarn with a photo, a project linked to it with a
                // step, a title photo and a step photo.
                val yarn = yarnsApi.createYarn(YarnWriteRequest(name = yarnName, brand = "Backup"))
                yarnsApi.uploadPhoto(yarn.id, imagePart("yarn.png"))
                val project =
                    projectsApi.createProject(
                        ProjectWriteRequest(
                            name = projectName,
                            description = "Created by the data backup E2E test",
                            yarnIds = listOf(yarn.id),
                            steps = listOf(StepWriteRequest(title = stepTitle)),
                        )
                    )
                projectsApi.uploadTitleImage(project.id, imagePart("title.png"))
                val stepId = projectsApi.getProject(project.id).steps.single().id
                projectsApi.uploadStepImage(project.id, stepId, imagePart("step.png"))
                val projectsBefore = serverProjects(projectsApi).size
                val yarnsBefore = serverYarns(yarnsApi).size

                // 2. Export through the app's worker, both container formats.
                val tarFile = File(workDir, "export.tar.gz")
                exportThroughWorker(tarFile, ExportFormat.TarGz)
                val tarBytes = tarFile.readBytes()
                assertTrue("tar.gz export is empty", tarBytes.isNotEmpty())
                assertEquals("gzip magic 0", 0x1f.toByte(), tarBytes[0])
                assertEquals("gzip magic 1", 0x8b.toByte(), tarBytes[1])
                val tar = readTarGz(tarFile)

                val zipFile = File(workDir, "export.zip")
                exportThroughWorker(zipFile, ExportFormat.Zip)
                val zipBytes = zipFile.readBytes()
                assertTrue("zip export is empty", zipBytes.isNotEmpty())
                assertEquals("zip magic P", 'P'.code.toByte(), zipBytes[0])
                assertEquals("zip magic K", 'K'.code.toByte(), zipBytes[1])
                val zip = readZip(zipFile)

                val manifests = mutableListOf<JSONObject>()
                for ((label, archive) in listOf("tar.gz" to tar, "zip" to zip)) {
                    assertTrue(
                        "$label: manifest.json is not the first entry: ${archive.names.first()}",
                        archive.names.first() == "${archive.topDir}/manifest.json",
                    )
                    assertTrue(
                        "$label: unexpected top-level directory ${archive.topDir}",
                        Regex("stricknani-export-\\d{4}-\\d{2}-\\d{2}").matches(archive.topDir),
                    )
                    val manifest = archive.json("manifest.json") as JSONObject
                    assertEquals(label, "stricknani-export", manifest.getString("format"))
                    assertEquals(label, 1, manifest.getInt("format_version"))
                    val counts = manifest.getJSONObject("counts")
                    val projects = archive.json("projects.json") as JSONArray
                    val yarns = archive.json("yarns.json") as JSONArray
                    val categories = archive.json("categories.json") as JSONArray
                    assertEquals("$label projects", projectsBefore, counts.getInt("projects"))
                    assertEquals("$label yarns", yarnsBefore, counts.getInt("yarns"))
                    assertEquals(
                        "$label projects.json",
                        counts.getInt("projects"),
                        projects.length(),
                    )
                    assertEquals("$label yarns.json", counts.getInt("yarns"), yarns.length())
                    assertEquals(
                        "$label categories.json",
                        counts.getInt("categories"),
                        categories.length(),
                    )
                    assertEquals(
                        "$label media entries match counts.media_files",
                        counts.getInt("media_files"),
                        archive.names.count { it.startsWith("${archive.topDir}/media/") },
                    )
                    // The seeded fixture has 2 photos and this test adds 3 more.
                    assertTrue(
                        "$label: too few media files: ${counts.getInt("media_files")}",
                        counts.getInt("media_files") >= 5,
                    )
                    val exported = projects.objects().single { it.getString("name") == projectName }
                    assertEquals(label, 1, exported.getJSONArray("images").length())
                    val exportedSteps = exported.getJSONArray("steps")
                    assertEquals(
                        label,
                        stepTitle,
                        exportedSteps.getJSONObject(0).getString("title"),
                    )
                    assertEquals(
                        label,
                        1,
                        exportedSteps.getJSONObject(0).getJSONArray("images").length(),
                    )
                    val exportedYarn = yarns.objects().single { it.getString("name") == yarnName }
                    assertEquals(label, 1, exportedYarn.getJSONArray("photos").length())
                    manifests += manifest
                }
                assertEquals(
                    "tar.gz and zip carry the same counts",
                    manifests[0].getJSONObject("counts").toString(),
                    manifests[1].getJSONObject("counts").toString(),
                )
                val manifest = manifests[0]
                val counts = manifest.getJSONObject("counts")

                // 3. Restore the tar.gz through the app's worker.
                val restore = restoreThroughWorker(tarFile)
                assertEquals(
                    "restore failed: ${restore.outputData.getString(DataRestoreWorker.KEY_FAILURE)}",
                    WorkInfo.State.SUCCEEDED,
                    restore.state,
                )
                val out = restore.outputData
                assertEquals(
                    counts.getInt("projects"),
                    out.getInt(DataRestoreWorker.KEY_PROJECTS, -1),
                )
                assertEquals(counts.getInt("yarns"), out.getInt(DataRestoreWorker.KEY_YARNS, -1))
                assertEquals(
                    counts.getInt("media_files"),
                    out.getInt(DataRestoreWorker.KEY_MEDIA, -1),
                )
                assertEquals(0, out.getInt(DataRestoreWorker.KEY_SKIPPED, -1))

                // The server now holds every item twice.
                assertEquals(projectsBefore * 2, serverProjects(projectsApi).size)
                assertEquals(yarnsBefore * 2, serverYarns(yarnsApi).size)

                // The app's sync (the worker also kicks one off) brings the copies into Room.
                awaitCount("two cached projects named $projectName") {
                    graph.projectRepository().sync()
                    graph.projectRepository().observeAll().first().count {
                        it.name == projectName
                    } == 2
                }
                awaitCount("two cached yarns named $yarnName") {
                    graph.yarnRepository().sync()
                    graph.yarnRepository().observeAll().first().count { it.name == yarnName } == 2
                }
                val restoredProject =
                    graph.projectRepository().observeAll().first().single {
                        it.name == projectName && it.id != project.id
                    }
                val restoredYarn =
                    graph.yarnRepository().observeAll().first().single {
                        it.name == yarnName && it.id != yarn.id
                    }
                val restoredDetail = projectsApi.getProject(restoredProject.id)
                assertEquals(listOf(restoredYarn.id), restoredDetail.yarnIds)
                assertEquals(stepTitle, restoredDetail.steps.single().title)
                // `images` lists the title and the step photos; the copy must have exactly
                // what the original has (here 2: one title photo, one step photo).
                val originalDetail = projectsApi.getProject(project.id)
                assertEquals(2, originalDetail.images.size)
                assertEquals(originalDetail.images.size, restoredDetail.images.size)
                assertEquals(
                    yarnsApi.getYarn(yarn.id).photos.size,
                    yarnsApi.getYarn(restoredYarn.id).photos.size,
                )
                assertEquals(1, yarnsApi.getYarn(restoredYarn.id).photos.size)

                // The Projects screen lists the original and the copy.
                composeRule.onNodeWithText("Projects").performClick()
                waitForText("Search")
                composeRule.onNode(hasSetTextAction()).performTextInput(nonce)
                composeRule.waitUntil(60_000) {
                    composeRule.onAllNodesWithText(projectName).fetchSemanticsNodes().size >= 2
                }
            }
        } finally {
            runBlocking { cleanup(projectsApi, yarnsApi, projectName, yarnName) }
            workDir.deleteRecursively()
        }
    }

    @Test
    fun sharedProjectIsExportedWithItsAccessAndRestoredAsOwnCopy() {
        val peerToken =
            InstrumentationRegistry.getArguments().getString("e2e_peer_token")?.takeIf {
                it.isNotBlank()
            }
        assumeTrue("e2e_peer_token not provided: shared-item assertions skipped", peerToken != null)
        connectToFixture()
        val nonce = UUID.randomUUID().toString().take(8)
        val projectName = "Backup e2e $nonce shared project"
        val workDir = freshWorkDir()
        val projectsApi = graph.projectsApi()
        val main = FixtureHttp(baseUrl, token)
        val peer = FixtureHttp(baseUrl, checkNotNull(peerToken))
        var sharedId = -1

        try {
            runBlocking {
                val mainId = main.get("/api/v2/meta").getJSONObject("user").getInt("id")
                val peerId = peer.get("/api/v2/meta").getJSONObject("user").getInt("id")
                assertTrue("the peer must be a different account", mainId != peerId)

                // 1. The peer creates a project with a photo and shares it as viewer with the main
                // user.
                sharedId =
                    peer
                        .send(
                            "POST",
                            "/api/v2/projects",
                            JSONObject()
                                .put("name", projectName)
                                .put("description", "Shared by the peer account"),
                        )
                        .getInt("id")
                peer.uploadPng("/api/v2/projects/$sharedId/images/title", "shared.png")
                peer.send(
                    "POST",
                    "/api/v2/projects/$sharedId/shares",
                    JSONObject().put("user_id", mainId).put("role", "viewer"),
                )
                val sharedAccess = main.get("/api/v2/projects/$sharedId")
                assertEquals("viewer", sharedAccess.getJSONObject("access").getString("role"))

                // 2. Both archive formats carry the shared project with the caller's role, its
                // owner and its photo.
                val tarFile = File(workDir, "export.tar.gz")
                exportThroughWorker(tarFile, ExportFormat.TarGz)
                val zipFile = File(workDir, "export.zip")
                exportThroughWorker(zipFile, ExportFormat.Zip)
                for ((label, archive) in
                    listOf("tar.gz" to readTarGz(tarFile), "zip" to readZip(zipFile))) {
                    val projects = archive.json("projects.json") as JSONArray
                    val exported = projects.objects().single { it.getString("name") == projectName }
                    assertEquals(
                        "$label: role of the shared project",
                        "viewer",
                        exported.getJSONObject("access").getString("role"),
                    )
                    assertEquals(
                        "$label: owner of the shared project",
                        peerId,
                        exported.getJSONObject("owner").getInt("id"),
                    )
                    assertEquals(label, 1, exported.getJSONArray("images").length())
                }

                // 3. Restore: the copy belongs to the restoring (main) user.
                val restore = restoreThroughWorker(tarFile)
                assertEquals(
                    "restore failed: ${restore.outputData.getString(DataRestoreWorker.KEY_FAILURE)}",
                    WorkInfo.State.SUCCEEDED,
                    restore.state,
                )
                assertEquals(0, restore.outputData.getInt(DataRestoreWorker.KEY_SKIPPED, -1))
                val copyId =
                    serverProjects(projectsApi)
                        .filter { it.second == projectName && it.first != sharedId }
                        .map { it.first }
                        .single()
                val copy = main.get("/api/v2/projects/$copyId")
                assertEquals(mainId, copy.getJSONObject("owner").getInt("id"))
                assertEquals("owner", copy.getJSONObject("access").getString("role"))
                assertEquals(1, copy.getJSONArray("images").length())
                assertEquals(
                    "the restored copy has no shares",
                    0,
                    main.get("/api/v2/projects/$copyId/shares").getJSONArray("shares").length(),
                )

                // 4. The peer's original is unchanged: still the peer's, still one share.
                val original = peer.get("/api/v2/projects/$sharedId")
                assertEquals(peerId, original.getJSONObject("owner").getInt("id"))
                assertEquals("owner", original.getJSONObject("access").getString("role"))
                assertEquals(1, original.getJSONArray("images").length())
                val shares = peer.get("/api/v2/projects/$sharedId/shares").getJSONArray("shares")
                assertEquals(1, shares.length())
                assertEquals(mainId, shares.getJSONObject(0).getJSONObject("user").getInt("id"))
                assertEquals("viewer", shares.getJSONObject(0).getString("role"))
                assertEquals(
                    "viewer",
                    main
                        .get("/api/v2/projects/$sharedId")
                        .getJSONObject("access")
                        .getString("role"),
                )
            }
        } finally {
            runBlocking {
                runCatching {
                    if (sharedId > 0) peer.send("DELETE", "/api/v2/projects/$sharedId", null)
                }
                runCatching {
                    serverProjects(projectsApi)
                        .filter { it.second == projectName }
                        .forEach { runCatching { projectsApi.deleteProject(it.first) } }
                    graph.projectRepository().sync()
                }
            }
            workDir.deleteRecursively()
        }
    }

    @Test
    fun restoreOfARandomFileIsRejectedAndChangesNothing() {
        connectToFixture()
        val workDir = freshWorkDir()
        try {
            runBlocking {
                val projectsApi = graph.projectsApi()
                val yarnsApi = graph.yarnsApi()
                val projectsBefore = serverProjects(projectsApi).size
                val yarnsBefore = serverYarns(yarnsApi).size

                val junk = File(workDir, "not-an-archive.bin")
                junk.writeBytes(
                    ByteArray(4096).also {
                        SecureRandom().nextBytes(it)
                        // Neither gzip (1f 8b) nor zip (PK) magic.
                        it[0] = 0
                    }
                )
                val result = restoreThroughWorker(junk)
                assertEquals(WorkInfo.State.FAILED, result.state)
                assertEquals(
                    RestoreFailure.InvalidArchive.name,
                    result.outputData.getString(DataRestoreWorker.KEY_FAILURE),
                )
                assertEquals(projectsBefore, serverProjects(projectsApi).size)
                assertEquals(yarnsBefore, serverYarns(yarnsApi).size)
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun backupsScreenOffersDataExportAndRestore() {
        connectToFixture()
        composeRule.onNodeWithText("More").performClick()
        waitForText("Settings")
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTextMatcher("Backups"))
        composeRule.onNodeWithText("Backups").performClick()

        waitForText("Your data")
        composeRule.onNodeWithText("Archive type").assertExists()
        composeRule.onNodeWithTag("data-export-format").assertTextEquals(".tar.gz")
        composeRule.onNodeWithTag("data-export-format").performClick()
        composeRule.onNodeWithText(".zip").performClick()
        composeRule.onNodeWithTag("data-export-format").assertTextEquals(".zip")
        composeRule.onNodeWithTag("data-export-button").assertTextEquals("Download")

        scrollBackupsTo("Restore from a file")
        composeRule.onNodeWithText("Restore from a file").assertExists()
        scrollBackupsTo("Choose file")
        composeRule.onNodeWithTag("data-restore-button").assertTextEquals("Choose file")

        // The older app-backup card keeps its own, distinct label for the file picker.
        scrollBackupsTo("Choose backup file")
        composeRule.onNodeWithText("Choose backup file").assertExists()
    }

    private fun scrollBackupsTo(text: String) {
        // The Backups screen's list has no test tag: scroll the first scrollable container.
        composeRule.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasTextMatcher(text))
    }

    // ---- Workers -------------------------------------------------------------------------

    private fun exportThroughWorker(target: File, format: ExportFormat) {
        val info =
            runWorker(
                OneTimeWorkRequestBuilder<DataExportWorker>()
                    .setInputData(
                        workDataOf(
                            DataExportWorker.KEY_URI to Uri.fromFile(target).toString(),
                            DataExportWorker.KEY_NAME to target.name,
                            DataExportWorker.KEY_FORMAT to format.name,
                        )
                    )
                    .build()
            )
        assertEquals(
            "${format.name} export failed: " +
                info.outputData.getString(DataExportWorker.KEY_FAILURE),
            WorkInfo.State.SUCCEEDED,
            info.state,
        )
        assertTrue("${format.name} export wrote no file", target.isFile && target.length() > 0)
    }

    private fun restoreThroughWorker(source: File): WorkInfo =
        runWorker(
            OneTimeWorkRequestBuilder<DataRestoreWorker>()
                .setInputData(
                    workDataOf(
                        DataRestoreWorker.KEY_URI to Uri.fromFile(source).toString(),
                        DataRestoreWorker.KEY_TOTAL to source.length(),
                    )
                )
                .build()
        )

    /** Enqueues [request] on the app's WorkManager (Hilt worker factory) and waits for its end. */
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
            SystemClock.sleep(POLL_MILLIS)
        }
        error("Worker did not finish within ${WORK_TIMEOUT_SECONDS}s")
    }

    private fun awaitCount(description: String, condition: suspend () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + SYNC_TIMEOUT_MILLIS
        var lastFailure: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                if (runBlocking { condition() }) return
            } catch (failure: Throwable) {
                lastFailure = failure
            }
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for $description", lastFailure)
    }

    // ---- Server helpers ------------------------------------------------------------------

    private suspend fun serverProjects(api: ProjectsApi): List<Pair<Int, String>> {
        val all = mutableListOf<Pair<Int, String>>()
        var page = 1
        while (page <= MAX_PAGES) {
            val result = api.listProjects(page = page)
            all += result.items.map { it.id to it.name }
            if (!result.hasMore) return all
            page++
        }
        error("Project list exceeded $MAX_PAGES pages")
    }

    private suspend fun serverYarns(api: YarnsApi): List<Pair<Int, String>> {
        val all = mutableListOf<Pair<Int, String>>()
        var page = 1
        while (page <= MAX_PAGES) {
            val result = api.listYarns(page = page)
            all += result.items.map { it.id to it.name }
            if (!result.hasMore) return all
            page++
        }
        error("Yarn list exceeded $MAX_PAGES pages")
    }

    /** Deletes the originals and the restored copies; the next sync tombstones them in Room. */
    private suspend fun cleanup(
        projectsApi: ProjectsApi,
        yarnsApi: YarnsApi,
        projectName: String,
        yarnName: String,
    ) {
        runCatching {
            serverProjects(projectsApi)
                .filter { it.second == projectName }
                .forEach { runCatching { projectsApi.deleteProject(it.first) } }
            serverYarns(yarnsApi)
                .filter { it.second == yarnName }
                .forEach { runCatching { yarnsApi.deleteYarn(it.first) } }
            graph.projectRepository().sync()
            graph.yarnRepository().sync()
        }
    }

    private fun imagePart(fileName: String): MultipartBody.Part =
        MultipartBody.Part.createFormData(
            "file",
            fileName,
            PNG_1X1.toRequestBody("image/png".toMediaType()),
        )

    private fun freshWorkDir(): File =
        File(context.cacheDir, "data-backup-e2e-${UUID.randomUUID()}").also { it.mkdirs() }

    /**
     * Minimal v2 client for one account's PAT; unwraps the `{"data": ...}` envelope. Failures name
     * only the method, path and status, never a body or the token.
     */
    private class FixtureHttp(private val baseUrl: String, private val bearer: String) {
        fun get(path: String): JSONObject = send("GET", path, null)

        fun send(method: String, path: String, body: JSONObject?): JSONObject =
            call(method, path, body?.toString()?.toByteArray(), "application/json")

        fun uploadPng(path: String, fileName: String) {
            val boundary = "e2e${UUID.randomUUID().toString().replace("-", "")}"
            val out = java.io.ByteArrayOutputStream()
            out.write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; " +
                        "filename=\"$fileName\"\r\nContent-Type: image/png\r\n\r\n")
                    .toByteArray()
            )
            out.write(PNG_1X1)
            out.write("\r\n--$boundary--\r\n".toByteArray())
            call("POST", path, out.toByteArray(), "multipart/form-data; boundary=$boundary")
        }

        private fun call(
            method: String,
            path: String,
            body: ByteArray?,
            contentType: String,
        ): JSONObject {
            val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Authorization", "Bearer $bearer")
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", contentType)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw AssertionError("$method $path returned HTTP $status")
                }
                val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                if (text.isBlank()) return JSONObject()
                val json = JSONObject(text)
                return json.optJSONObject("data") ?: json
            } finally {
                connection.disconnect()
            }
        }
    }

    // ---- Archive readers -----------------------------------------------------------------

    /** The regular files of an archive in order; JSON files keep their bytes. */
    private class Archive(val names: List<String>, private val jsons: Map<String, ByteArray>) {
        val topDir: String = names.first().substringBefore('/')

        fun json(relative: String): Any {
            val bytes = checkNotNull(jsons["$topDir/$relative"]) { "$relative is missing" }
            val text = bytes.toString(Charsets.UTF_8)
            return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun readZip(file: File): Archive {
        val names = mutableListOf<String>()
        val jsons = mutableMapOf<String, ByteArray>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                names += entry.name
                if (entry.name.endsWith(".json")) jsons[entry.name] = zip.readBytes()
            }
        }
        assertTrue("zip has no entries", names.isNotEmpty())
        return Archive(names, jsons)
    }

    /**
     * A minimal ustar/pax reader: 512-byte headers, octal sizes, `path` from pax extended headers
     * (`x`) and the ustar prefix field, data padded to 512 bytes.
     */
    private fun readTarGz(file: File): Archive {
        val names = mutableListOf<String>()
        val jsons = mutableMapOf<String, ByteArray>()
        GZIPInputStream(file.inputStream().buffered()).use { input ->
            val header = ByteArray(TAR_BLOCK)
            var paxPath: String? = null
            while (readFully(input, header)) {
                if (header.all { it == 0.toByte() }) break
                val size = octal(header, 124, 12)
                val type = header[156].toInt().toChar()
                val padded = (size + TAR_BLOCK - 1) / TAR_BLOCK * TAR_BLOCK
                when (type) {
                    'x' -> {
                        val data = ByteArray(size.toInt())
                        check(readFully(input, data)) { "truncated pax header" }
                        skip(input, padded - size)
                        paxPath = paxRecord(data, "path") ?: paxPath
                    }
                    'g' -> skip(input, padded)
                    '0',
                    '\u0000' -> {
                        val prefix = cString(header, 345, 155)
                        val base = cString(header, 0, 100)
                        val name = paxPath ?: if (prefix.isEmpty()) base else "$prefix/$base"
                        paxPath = null
                        names += name
                        if (name.endsWith(".json")) {
                            val data = ByteArray(size.toInt())
                            check(readFully(input, data)) { "truncated entry $name" }
                            skip(input, padded - size)
                            jsons[name] = data
                        } else {
                            skip(input, padded)
                        }
                    }
                    else -> {
                        // Directories and other types carry no data we read.
                        paxPath = null
                        skip(input, padded)
                    }
                }
            }
        }
        assertTrue("tar has no entries", names.isNotEmpty())
        return Archive(names, jsons)
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var filled = 0
        while (filled < buffer.size) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read < 0) {
                check(filled == 0) { "truncated tar block" }
                return false
            }
            filled += read
        }
        return true
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        val scratch = ByteArray(8192)
        while (left > 0) {
            val read = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            check(read >= 0) { "truncated tar data" }
            left -= read
        }
    }

    private fun cString(buffer: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && buffer[end] != 0.toByte()) end++
        return String(buffer, offset, end - offset, Charsets.UTF_8)
    }

    private fun octal(buffer: ByteArray, offset: Int, length: Int): Long =
        cString(buffer, offset, length).trim().ifEmpty { "0" }.toLong(8)

    /** `LEN key=value\n` records of a pax extended header. */
    private fun paxRecord(data: ByteArray, key: String): String? {
        var pos = 0
        while (pos < data.size) {
            var space = pos
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            val length =
                String(data, pos, space - pos, Charsets.US_ASCII).toIntOrNull() ?: return null
            val record = String(data, space + 1, pos + length - space - 2, Charsets.UTF_8)
            if (record.startsWith("$key=")) return record.substringAfter('=')
            pos += length
        }
        return null
    }

    private companion object {
        const val TAR_BLOCK = 512
        const val WORK_TIMEOUT_SECONDS = 240L
        const val POLL_MILLIS = 500L
        const val SYNC_TIMEOUT_MILLIS = 120_000L
        const val MAX_PAGES = 50

        /** The 1x1 PNG the live-write journey uploads; the server accepts it. */
        val PNG_1X1: ByteArray =
            ("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d4944415408d7" +
                    "63f8cfc0f01f00050001ff729c52670000000049454e44ae426082")
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
    }
}
