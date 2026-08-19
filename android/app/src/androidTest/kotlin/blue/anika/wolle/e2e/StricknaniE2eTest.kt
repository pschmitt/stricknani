package blue.anika.wolle.e2e

import android.Manifest
import android.content.ContentValues
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText as hasTextMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import blue.anika.wolle.MainActivity
import blue.anika.wolle.ui.common.DESTRUCTIVE_DELETE_DIALOG_TAG
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
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
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun hasText(text: String): Boolean =
        composeRule
            .onAllNodesWithText(text, substring = false, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

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
            waitForText(name)

            composeRule.onNodeWithText("Home").performClick()
            waitForText("Changes pending")
        } finally {
            setAirplaneMode(device, enabled = false)
        }
    }

    private fun editYarn(currentName: String, updatedName: String) {
        openYarns()
        composeRule.onNodeWithText(currentName).performClick()
        waitForText("More actions")
        composeRule.onNodeWithContentDescription("More actions").performClick()
        clickTextWithAction("Edit")
        waitForText("Edit yarn")
        composeRule.onNodeWithTag("e2e-yarn-editor-name").performTextReplacement(updatedName)
        composeRule.onNodeWithContentDescription("Save").performClick()
        waitForText(updatedName)
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
        waitForText(name)
    }

    private fun editProject(currentName: String, updatedName: String) {
        composeRule.onNodeWithText(currentName).performClick()
        waitForText("More actions")
        composeRule.onNodeWithContentDescription("More actions").performClick()
        clickTextWithAction("Edit")
        waitForText("Edit project")
        composeRule.onNodeWithTag("e2e-project-editor-name").performTextReplacement(updatedName)
        composeRule.onNodeWithContentDescription("Save").performClick()
        waitForText(updatedName)
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
        waitForText("Riverbend Merino DK", timeoutMillis = 120_000)
    }

    private fun selectFixturePhoto(fileName: String) {
        composeRule.onNodeWithText("Add photo").performScrollTo().performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        checkNotNull(device.wait(Until.findObject(By.text(fileName)), PICKER_TIMEOUT_MILLIS)) {
            "Fixture photo $fileName did not appear in the system picker"
        }.click()
        waitForText(fileName)
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
            ) { "Unable to create fixture image" }
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
        (URL("${baseUrl.trimEnd('/')}/${path.trimStart('/')}").openConnection() as HttpURLConnection)
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
            val body = (if (status in 200..399) inputStream else errorStream)?.bufferedReader()?.use {
                it.readText()
            }.orEmpty()
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
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
    }

    private class FixturePhoto(val fileName: String, private val cleanup: () -> Unit) {
        fun delete() = cleanup()
    }

    private companion object {
        const val PICKER_TIMEOUT_MILLIS = 15_000L
        const val FIXTURE_TIMEOUT_MILLIS = 120_000L
        const val FIXTURE_POLL_INTERVAL_MILLIS = 500L
        const val FIXTURE_CONNECT_TIMEOUT_MILLIS = 5_000
        val PNG_1X1 =
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d,
                0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06,
                0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4.toByte(), 0x89.toByte(), 0x00, 0x00, 0x00, 0x0d,
                0x49, 0x44, 0x41, 0x54, 0x08, 0xd7.toByte(), 0x63, 0xf8.toByte(), 0xcf.toByte(), 0xc0.toByte(),
                0xf0.toByte(), 0x1f, 0x00, 0x05, 0x00, 0x01, 0xff.toByte(), 0x89.toByte(), 0x99.toByte(),
                0x3d, 0x1d, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae.toByte(), 0x42, 0x60,
                0x82.toByte(),
            )
    }
}
