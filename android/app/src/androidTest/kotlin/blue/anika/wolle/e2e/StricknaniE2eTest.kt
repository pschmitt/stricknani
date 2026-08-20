package blue.anika.wolle.e2e

import android.Manifest
import android.content.ContentValues
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText as hasTextMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import blue.anika.wolle.MainActivity
import blue.anika.wolle.ui.common.DESTRUCTIVE_DELETE_DIALOG_TAG
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Shared fixture contract and wait helpers for the PR smoke and manual full lanes. */
abstract class StricknaniE2eTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    protected val baseUrl: String
        get() = requiredArgument("e2e_base_url")

    protected val token: String
        get() = requiredArgument("e2e_token")

    protected fun connectToFixture() {
        // Instrumentation methods share the installed app's DataStore and Room database. The
        // first method sees onboarding, while later methods start on the already-configured Home
        // graph. Wait for either state and only submit credentials when onboarding is visible.
        composeRule.waitUntil(120_000) {
            hasTag("e2e-onboarding-server-url") || hasText("Projects")
        }
        if (hasTag("e2e-onboarding-server-url")) {
            composeRule.onNodeWithTag("e2e-onboarding-server-url").performTextInput(baseUrl)
            composeRule.onNodeWithTag("e2e-onboarding-api-token").performTextInput(token)
            grantNotificationPermission()
            composeRule.onNodeWithText("Connect").performClick()
        }
        // Home does not render arbitrary yarns; the full journeys navigate to Projects/Yarns
        // before asserting fixture rows. The bottom-navigation label is the stable configured
        // state marker.
        waitForText("Projects", timeoutMillis = 120_000)
    }

    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand(
                    "pm grant blue.anika.wolle.debug ${Manifest.permission.POST_NOTIFICATIONS}"
                )
                .close()
        }
    }

    protected fun waitForText(text: String, timeoutMillis: Long = 30_000) {
        composeRule.waitUntil(timeoutMillis) { hasText(text) }
    }

    private fun hasTag(tag: String): Boolean =
    // Momentarily false (rather than propagating) while returning from an external Activity
    // (e.g. the system photo picker): our Activity has not finished resuming yet, so there is
    // no Compose hierarchy to query. The caller's waitUntil polls again on the next frame.
    runCatching {
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun hasText(text: String): Boolean = runCatching {
        composeRule
            .onAllNodesWithText(text, substring = false, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
    }
        .getOrDefault(false)

    protected fun openProjects() {
        composeRule.onNodeWithText("Projects").performClick()
        waitForText("Search projects")
        composeRule
            .onNodeWithTag("e2e-projects-list")
            .performScrollToNode(hasTextMatcher("Heirloom Baby Blanket"))
        waitForText("Heirloom Baby Blanket", timeoutMillis = 120_000)
    }

    protected fun openHeirloomProject() {
        openProjects()
        composeRule.onNodeWithText("Heirloom Baby Blanket").performClick()
        waitForText("Heirloom Baby Blanket")
        composeRule
            .onNodeWithTag("e2e-project-detail")
            .performScrollToNode(hasTextMatcher("Description"))
        waitForText("Description", timeoutMillis = 120_000)
    }

    protected fun openSettingsAccount() {
        composeRule.onNodeWithText("Settings").performClick()
        waitForText("Settings")
        composeRule
            .onNodeWithContentDescription("Account: Server connection and sign-out")
            .performClick()
        waitForText("Server URL")
        waitForText(baseUrl)
    }

    protected fun enterSearchTerm(term: String) {
        composeRule.onNodeWithText("Search").performClick()
        waitForText("Search projects and yarns")
        composeRule.onNodeWithTag("e2e-search-field").performTextInput(term)
        waitForText("Riverbend Merino DK")
    }

    private fun requiredArgument(name: String): String =
        androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString(name)
            ?.takeIf(String::isNotBlank) ?: error("$name instrumentation argument is required")
}

@RunWith(AndroidJUnit4::class)
class StricknaniSmokeTest : StricknaniE2eTest() {
    @Test
    fun onboardingSyncDetailAndSettings() {
        connectToFixture()
        captureE2eScreenshot("smoke-01-home")

        openProjects()
        composeRule.onNodeWithText("Heirloom Baby Blanket").performClick()
        waitForText("Description")
        captureE2eScreenshot("smoke-02-project-detail")

        composeRule.onNodeWithContentDescription("Back").performClick()
        openSettingsAccount()
        captureE2eScreenshot("smoke-03-settings-account")
    }
}

@RunWith(AndroidJUnit4::class)
class StricknaniFullE2eTest : StricknaniE2eTest() {
    @Test
    fun cachedBrowsingSearchAndSettings() {
        connectToFixture()
        captureE2eScreenshot("full-01-home")

        openHeirloomProject()
        composeRule.onNodeWithContentDescription("Back").performClick()

        enterSearchTerm("Riverbend")
        captureE2eScreenshot("full-02-search")

        composeRule.onNodeWithText("Settings").performClick()
        waitForText("Settings")
        captureE2eScreenshot("full-03-settings")
    }

    @Test
    fun cachedBrowsingAndQueuedEditSurviveOfflineMode() {
        connectToFixture()
        openHeirloomProject()

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        setAirplaneMode(device, enabled = true)
        try {
            // The detail remains available from Room while the server is unreachable.
            composeRule.onNodeWithContentDescription("More actions").performClick()
            composeRule.onNodeWithText("Edit").performClick()
            waitForText("Edit project")
            composeRule
                .onNodeWithText("Heirloom Baby Blanket", useUnmergedTree = true)
                .performTextReplacement("Offline queued project")
            composeRule.onNodeWithContentDescription("Save").performClick()
            waitForText("Offline queued project")

            // Returning to the cached list and Home proves both the read path and pending-write
            // status survive without a network response.
            composeRule.onNodeWithText("Projects").performClick()
            composeRule
                .onNodeWithTag("e2e-projects-list")
                .performScrollToNode(hasTextMatcher("Offline queued project"))
            waitForText("Offline queued project")
            composeRule.onNodeWithText("Home").performClick()
            waitForText("Changes pending")
            captureE2eScreenshot("full-04-offline-queued-edit")
        } finally {
            setAirplaneMode(device, enabled = false)
        }
    }

    private fun setAirplaneMode(device: UiDevice, enabled: Boolean) {
        val command =
            if (enabled) "cmd connectivity airplane-mode enable"
            else "cmd connectivity airplane-mode disable"
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
            .closeQuietly()
    }

    private fun Closeable.closeQuietly() {
        runCatching { close() }
    }
}

/**
 * Exercises the actual outbox, not just the cached UI: a create plus dependent upload is queued
 * while offline, then reaches the disposable server after connectivity returns. The follow-up
 * project journey covers the same queued create/update/delete contract through both editors.
 */
@RunWith(AndroidJUnit4::class)
class StricknaniLiveWriteE2eTest : StricknaniE2eTest() {
    @Test
    fun queuedWritesAndUploadsReachTheDisposableFixture() {
        connectToFixture()
        val suffix = System.currentTimeMillis().toString()
        val yarnName = "E2E offline yarn $suffix"
        val editedYarnName = "$yarnName updated"
        val projectName = "E2E project $suffix"
        val editedProjectName = "$projectName updated"
        val photo = createFixturePhoto("stricknani-e2e-$suffix.png")

        try {
            createOfflineYarnWithPhoto(yarnName, photo.fileName)
            val yarnId = awaitYarn(yarnName).getInt("id")
            awaitFixture("photo upload for yarn $yarnId") {
                fixtureObject("api/v1/yarns/$yarnId").getJSONArray("photos").length() == 1
            }

            editYarn(yarnName, editedYarnName)
            awaitFixture("updated yarn $yarnId") {
                fixtureObject("api/v1/yarns/$yarnId").getString("name") == editedYarnName
            }
            deleteYarn()
            awaitFixture("deleted yarn $yarnId") {
                fixtureStatus("api/v1/yarns/$yarnId") == HttpURLConnection.HTTP_NOT_FOUND
            }

            createProject(projectName)
            val projectId = awaitProject(projectName).getInt("id")
            editProject(projectName, editedProjectName)
            awaitFixture("updated project $projectId") {
                fixtureObject("api/v1/projects/$projectId").getString("name") == editedProjectName
            }
            deleteProject()
            awaitFixture("deleted project $projectId") {
                fixtureStatus("api/v1/projects/$projectId") == HttpURLConnection.HTTP_NOT_FOUND
            }
        } finally {
            photo.delete()
        }
    }

    private fun createOfflineYarnWithPhoto(name: String, photoName: String) {
        openYarns()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        setAirplaneMode(device, enabled = true)
        try {
            composeRule.onNodeWithTag("e2e-new-yarn").performClick()
            waitForText("New yarn")
            composeRule.onNodeWithTag("e2e-yarn-editor-name").performTextReplacement(name)
            selectFixturePhoto(photoName)
            composeRule.onNodeWithContentDescription("Save").performClick()
            // Unlike the create-then-navigateUp() flows used elsewhere in this test, the Yarns
            // list screen underneath the editor is never disposed here, so it keeps whatever
            // scroll position openYarns() left it at (near "Riverbend Merino DK") rather than
            // resetting to top. Confirmed via a live CI run's printed tree: the create succeeded,
            // but the new row (sorted first by updatedAt) was simply off-screen above the
            // viewport. Retry scrolling to it rather than a one-shot attempt, since the local
            // insert lands asynchronously on the ViewModel's coroutine.
            runCatching { waitForYarnRow(name) }
                .onFailure {
                    composeRule.onRoot(useUnmergedTree = true).printToLog(E2E_LOG_TAG)
                    throw it
                }

            composeRule.onNodeWithText("Home").performClick()
            waitForText("Changes pending")
        } finally {
            setAirplaneMode(device, enabled = false)
        }
    }

    private fun editYarn(currentName: String, updatedName: String) {
        openYarns()
        // openYarns() leaves the list scrolled to its "Riverbend Merino DK" load marker, which
        // can push this freshly-created row (sorted first by updatedAt) out of the composed
        // viewport - scroll back to it explicitly rather than assuming it is still on screen.
        scrollYarnsListToText(currentName, timeoutMillis = 30_000)
        composeRule.onNodeWithText(currentName).performClick()
        waitForContentDescription("More actions")
        composeRule.onNodeWithContentDescription("More actions").performClick()
        clickTextWithAction("Edit")
        waitForText("Edit yarn")
        composeRule.onNodeWithTag("e2e-yarn-editor-name").performTextReplacement(updatedName)
        composeRule.onNodeWithContentDescription("Save").performClick()
        runCatching { waitForText(updatedName) }
            .onFailure {
                composeRule.onRoot(useUnmergedTree = true).printToLog(E2E_LOG_TAG)
                throw it
            }
    }

    private fun deleteYarn() {
        composeRule.onNodeWithContentDescription("More actions").performClick()
        clickTextWithAction("Delete")
        composeRule.onNodeWithTag(DESTRUCTIVE_DELETE_DIALOG_TAG).assertIsDisplayed()
        clickTextWithAction("Delete")
        waitForText("Yarns")
    }

    private fun createProject(name: String) {
        openProjects()
        composeRule.onNodeWithTag("e2e-new-project").performClick()
        waitForText("New project")
        composeRule.onNodeWithTag("e2e-project-editor-name").performTextReplacement(name)
        composeRule.onNodeWithContentDescription("Save").performClick()
        // Same class of issue fixed for the yarns list: the Projects list underneath the pushed
        // editor is never disposed, so it stays scrolled to openProjects()'s "Heirloom Baby
        // Blanket" load marker rather than resetting to top - scroll back to the new row (sorted
        // first by updatedAt) with retries instead of a bare waitForText.
        runCatching { scrollProjectsListToText(name, timeoutMillis = 30_000) }
            .onFailure {
                composeRule.onRoot(useUnmergedTree = true).printToLog(E2E_LOG_TAG)
                throw it
            }
        waitForText(name)
    }

    private fun scrollProjectsListToText(text: String, timeoutMillis: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val found = runCatching {
                composeRule
                    .onNodeWithTag("e2e-projects-list")
                    .performScrollToNode(hasTextMatcher(text))
                true
            }
                .getOrDefault(false)
            if (found) return
            Thread.sleep(FIXTURE_POLL_INTERVAL_MILLIS)
        }
        error("\"$text\" did not appear in the projects list within ${timeoutMillis}ms")
    }

    private fun editProject(currentName: String, updatedName: String) {
        composeRule.onNodeWithText(currentName).performClick()
        waitForContentDescription("More actions")
        composeRule.onNodeWithContentDescription("More actions").performClick()
        clickTextWithAction("Edit")
        waitForText("Edit project")
        composeRule.onNodeWithTag("e2e-project-editor-name").performTextReplacement(updatedName)
        composeRule.onNodeWithContentDescription("Save").performClick()
        runCatching { waitForText(updatedName) }
            .onFailure {
                composeRule.onRoot(useUnmergedTree = true).printToLog(E2E_LOG_TAG)
                throw it
            }
    }

    private fun deleteProject() {
        composeRule.onNodeWithContentDescription("More actions").performClick()
        clickTextWithAction("Delete")
        composeRule.onNodeWithTag(DESTRUCTIVE_DELETE_DIALOG_TAG).assertIsDisplayed()
        clickTextWithAction("Delete")
        waitForText("Projects")
    }

    private fun openYarns() {
        composeRule.onNodeWithText("Yarns").performClick()
        waitForText("Search yarns")
        // A freshly (re)created Yarns screen kicks off its own server sync; a fresh CI run showed
        // the list transiently rendering as few as 2 (non-scrollable) rows while that sync
        // replaces local data, even after the create/edit under test was already confirmed
        // server-side via the fixture API. Retry rather than a one-shot scroll attempt.
        scrollYarnsListToText("Riverbend Merino DK", timeoutMillis = 120_000)
        waitForText("Riverbend Merino DK", timeoutMillis = 120_000)
    }

    private fun selectFixturePhoto(fileName: String) {
        composeRule
            .onNodeWithTag("e2e-yarn-editor-form")
            .performScrollToNode(hasTextMatcher("Add yarn photos"))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val appPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        composeRule.onNodeWithText("Add yarn photos").performClick()
        // The modern Android Photo Picker (API 33+) renders a thumbnail grid rather than visible
        // filenames, so a text match on `fileName` never hits. The freshly-inserted fixture image
        // is the only (or most recent) item, so fall back to its thumbnail resource id. Wait for
        // the picker's own package to actually become foreground before doing anything - our own
        // Activity is still frontmost for a moment after the click - then log its hierarchy either
        // way so a failure here is diagnosable from the captured logcat instead of guessing again.
        val pickerPackage =
            waitForForegroundPackageChange(from = appPackage, timeoutMillis = PICKER_TIMEOUT_MILLIS)
        logWindowHierarchy("picker, before selecting $fileName")
        val target =
            device.wait(Until.findObject(By.text(fileName)), TEXT_MATCH_TIMEOUT_MILLIS)
                ?: device.wait(
                    Until.findObject(By.res(Pattern.compile(".*:id/icon_thumbnail$"))),
                    PICKER_TIMEOUT_MILLIS,
                )
        checkNotNull(target) { "Fixture photo $fileName did not appear in the system picker" }
            .click()
        // This is a multi-select picker (the app launches GetMultipleContents()): tapping a
        // thumbnail only toggles its selection state and reveals a confirm button - it does not
        // by itself return to the app. Log the post-tap hierarchy either way, so if the confirm
        // button's real id/text differs from this guess, the next failure is diagnosable instead
        // of another blind guess.
        logWindowHierarchy("picker, after tapping thumbnail for $fileName")
        val confirm =
            device.wait(
                Until.findObject(By.res(Pattern.compile(".*:id/button_add$"))),
                TEXT_MATCH_TIMEOUT_MILLIS,
            ) ?: device.wait(Until.findObject(By.textStartsWith("Add")), PICKER_TIMEOUT_MILLIS)
        checkNotNull(confirm) { "No confirm/add button appeared after selecting $fileName" }.click()
        waitForForegroundPackageChange(from = pickerPackage, timeoutMillis = PICKER_TIMEOUT_MILLIS)
        logWindowHierarchy("app, after selecting $fileName")
        // Returning from the external picker Activity does not preserve the form's LazyColumn
        // scroll position (confirmed via a live CI run: the app resumed scrolled to the
        // description/notes fields, with the photos section - and the newly-picked filename -
        // below the fold again). Scroll back to it before asserting the pick landed.
        composeRule
            .onNodeWithTag("e2e-yarn-editor-form")
            .performScrollToNode(hasTextMatcher("Add yarn photos"))
        // The Google Photo Picker does not return our synthetic MediaStore display name: a live
        // CI run's printed semantics tree showed the row rendered as "1000000018.png" (a
        // picker-generated name) instead of `fileName`, even though the pick otherwise succeeded.
        // Assert on the "Remove selected photo" affordance that only renders once a photo is
        // actually in form.photos, rather than a filename the app never controlled.
        runCatching { waitForContentDescription("Remove selected photo") }
            .onFailure {
                composeRule.onRoot(useUnmergedTree = true).printToLog(E2E_LOG_TAG)
                throw it
            }
    }

    private fun waitForContentDescription(description: String, timeoutMillis: Long = 30_000) {
        composeRule.waitUntil(timeoutMillis) {
            runCatching {
                    composeRule
                        .onAllNodesWithContentDescription(description)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                .getOrDefault(false)
        }
    }

    private fun waitForForegroundPackageChange(from: String, timeoutMillis: Long): String {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = device.currentPackageName
            if (current != null && current != from) return current
            Thread.sleep(FIXTURE_POLL_INTERVAL_MILLIS)
        }
        error("Foreground package did not change away from $from within ${timeoutMillis}ms")
    }

    private fun waitForYarnRow(name: String, timeoutMillis: Long = 30_000) =
        scrollYarnsListToText(name, timeoutMillis)

    private fun scrollYarnsListToText(text: String, timeoutMillis: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val found = runCatching {
                composeRule
                    .onNodeWithTag("e2e-yarns-list")
                    .performScrollToNode(hasTextMatcher(text))
                true
            }
                .getOrDefault(false)
            if (found) return
            Thread.sleep(FIXTURE_POLL_INTERVAL_MILLIS)
        }
        error("\"$text\" did not appear in the yarns list within ${timeoutMillis}ms")
    }

    private fun logWindowHierarchy(label: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val hierarchy =
            ByteArrayOutputStream().use { stream ->
                device.dumpWindowHierarchy(stream)
                stream.toString("UTF-8")
            }
        Log.i(E2E_LOG_TAG, "Window hierarchy ($label):\n$hierarchy")
    }

    private fun createFixturePhoto(fileName: String): FixturePhoto {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri =
            checkNotNull(
                resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    },
                )
            ) {
                "Unable to create fixture image"
            }
        resolver.openOutputStream(uri).use { output ->
            checkNotNull(output) { "Unable to write fixture image" }.write(PNG_1X1)
        }
        return FixturePhoto(fileName) { resolver.delete(uri, null, null) }
    }

    private fun awaitYarn(name: String): JSONObject {
        var yarn: JSONObject? = null
        awaitFixture("created yarn $name") {
            namedItem("api/v1/yarns?limit=100", name).also { yarn = it } != null
        }
        return checkNotNull(yarn)
    }

    private fun awaitProject(name: String): JSONObject {
        var project: JSONObject? = null
        awaitFixture("created project $name") {
            namedItem("api/v1/projects?limit=100", name).also { project = it } != null
        }
        return checkNotNull(project)
    }

    private fun namedItem(path: String, name: String): JSONObject? {
        val items = fixtureObject(path).getJSONArray("items")
        return (0 until items.length()).firstNotNullOfOrNull { index ->
            items.getJSONObject(index).takeIf { it.getString("name") == name }
        }
    }

    private fun awaitFixture(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + FIXTURE_TIMEOUT_MILLIS
        var lastFailure: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                if (condition()) return
            } catch (failure: Throwable) {
                lastFailure = failure
            }
            Thread.sleep(FIXTURE_POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("Timed out waiting for $description", lastFailure)
    }

    private fun fixtureObject(path: String): JSONObject {
        val connection = fixtureConnection(path)
        return connection.useResponse { status, body ->
            check(status in 200..299) { "Fixture request $path failed with HTTP $status: $body" }
            JSONObject(body)
        }
    }

    private fun fixtureStatus(path: String): Int =
        fixtureConnection(path).useResponse { status, _ -> status }

    private fun fixtureConnection(path: String): HttpURLConnection =
        (URL("${baseUrl.trimEnd('/')}/${path.trimStart('/')}").openConnection()
                as HttpURLConnection)
            .apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = FIXTURE_CONNECT_TIMEOUT_MILLIS
                readTimeout = FIXTURE_CONNECT_TIMEOUT_MILLIS
            }

    private inline fun <T> HttpURLConnection.useResponse(
        block: (status: Int, body: String) -> T
    ): T =
        try {
            val status = responseCode
            val body =
                (if (status in 200..399) inputStream else errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
            block(status, body)
        } finally {
            disconnect()
        }

    private fun clickTextWithAction(text: String) {
        val candidates = composeRule.onAllNodesWithText(text)
        val index =
            candidates.fetchSemanticsNodes().indexOfFirst { node ->
                node.config.contains(SemanticsActions.OnClick)
            }
        check(index >= 0) { "$text is missing click semantics" }
        candidates[index].performClick()
    }

    private fun setAirplaneMode(device: UiDevice, enabled: Boolean) {
        val command =
            if (enabled) "cmd connectivity airplane-mode enable"
            else "cmd connectivity airplane-mode disable"
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
            .close()
    }

    private class FixturePhoto(val fileName: String, private val cleanup: () -> Unit) {
        fun delete() = cleanup()
    }

    private companion object {
        const val E2E_LOG_TAG = "StricknaniE2E"
        const val PICKER_TIMEOUT_MILLIS = 15_000L
        const val TEXT_MATCH_TIMEOUT_MILLIS = 3_000L
        const val FIXTURE_TIMEOUT_MILLIS = 120_000L
        const val FIXTURE_POLL_INTERVAL_MILLIS = 500L
        const val FIXTURE_CONNECT_TIMEOUT_MILLIS = 5_000
        val PNG_1X1 =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4e,
                0x47,
                0x0d,
                0x0a,
                0x1a,
                0x0a,
                0x00,
                0x00,
                0x00,
                0x0d,
                0x49,
                0x48,
                0x44,
                0x52,
                0x00,
                0x00,
                0x00,
                0x01,
                0x00,
                0x00,
                0x00,
                0x01,
                0x08,
                0x06,
                0x00,
                0x00,
                0x00,
                0x1f,
                0x15,
                0xc4.toByte(),
                0x89.toByte(),
                0x00,
                0x00,
                0x00,
                0x0d,
                0x49,
                0x44,
                0x41,
                0x54,
                0x08,
                0xd7.toByte(),
                0x63,
                0xf8.toByte(),
                0xcf.toByte(),
                0xc0.toByte(),
                0xf0.toByte(),
                0x1f,
                0x00,
                0x05,
                0x00,
                0x01,
                0xff.toByte(),
                0x89.toByte(),
                0x99.toByte(),
                0x3d,
                0x1d,
                0x00,
                0x00,
                0x00,
                0x00,
                0x49,
                0x45,
                0x4e,
                0x44,
                0xae.toByte(),
                0x42,
                0x60,
                0x82.toByte(),
            )
    }
}
