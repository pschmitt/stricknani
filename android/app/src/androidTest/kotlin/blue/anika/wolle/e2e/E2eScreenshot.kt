package blue.anika.wolle.e2e

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Best-effort named screenshots copied out of the emulator by android-e2e.yaml. */
internal fun captureE2eScreenshot(name: String) {
    runCatching {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val directory =
            instrumentation.targetContext
                .getExternalFilesDir("e2e-screenshots")
                ?.apply(File::mkdirs) ?: return
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        File(directory, "$safeName.png").outputStream().use { output ->
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        screenshot.recycle()
    }
}
