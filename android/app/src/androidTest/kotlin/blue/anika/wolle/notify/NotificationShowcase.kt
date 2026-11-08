package blue.anika.wolle.notify

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.timer.ProjectTimerReminderNotifier
import blue.anika.wolle.data.update.UpdateAvailableNotifier
import blue.anika.wolle.sync.RustReplayTestEntryPoint
import blue.anika.wolle.sync.SyncNotifier
import blue.anika.wolle.sync.SyncSummary
import blue.anika.wolle.sync.SyncedItem
import blue.anika.wolle.sync.SyncedKind
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test: posts every rich notification once against the configured account (a cover photo is
 * uploaded to the first project when it has none), for a look on a device. Runs only with the
 * instrumentation argument `notification_showcase=true`; the notifications stay up.
 */
@RunWith(AndroidJUnit4::class)
class NotificationShowcase {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun postEveryRichNotification() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("notification_showcase") == "true")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand(
                    "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}"
                )
                .close()
        }
        val graph =
            EntryPointAccessors.fromApplication(context, RustReplayTestEntryPoint::class.java)
        val api = graph.projectsApi()
        val settings = graph.settingsRepository()
        val resolver = MediaUrlResolver(settings)
        val images = NotificationImages(context)
        runBlocking {
            val first = api.listProjects(page = 1).items.first()
            var project = api.getProject(first.id)
            if (project.images.none { it.isTitleImage }) {
                api.uploadTitleImage(project.id, coverPart())
                project = api.getProject(first.id)
            }
            val coverPath = project.images.firstOrNull { it.isTitleImage }?.thumbnailUrl
            val cover = resolver.resolve(coverPath)

            val sync = SyncNotifier(context, images)
            sync.ensureChannel()
            sync.notifySyncFoundChanges(
                SyncSummary(
                    projects =
                        listOf(SyncedItem(SyncedKind.Project, project.id, project.name, cover))
                )
            )

            ProjectTimerReminderNotifier(context, settings, resolver, images)
                .show(
                    projectId = project.id,
                    projectName = project.name,
                    startedAtMillis = System.currentTimeMillis() - 65 * 60_000L,
                    previewPath = coverPath,
                )

            UpdateAvailableNotifier(context)
                .show(
                    "1.7.0",
                    "Richer notifications with thumbnails.\nLocal-only mode improvements.",
                )
        }
    }

    private fun coverPart(): MultipartBody.Part {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        Canvas(bitmap)
            .drawRect(
                0f,
                0f,
                512f,
                512f,
                Paint().apply {
                    shader =
                        LinearGradient(
                            0f,
                            0f,
                            512f,
                            512f,
                            0xFFB0236B.toInt(),
                            0xFF5B2A86.toInt(),
                            Shader.TileMode.CLAMP,
                        )
                },
            )
        val bytes =
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return MultipartBody.Part.createFormData(
            "file",
            "cover.png",
            bytes.toByteArray().toRequestBody("image/png".toMediaType()),
        )
    }
}
