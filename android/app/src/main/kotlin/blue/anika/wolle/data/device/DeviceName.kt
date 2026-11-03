package blue.anika.wolle.data.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import javax.inject.Inject
import javax.inject.Singleton

/** Longest device name the server accepts (`device_name`, 100 characters). */
const val MAX_DEVICE_NAME_LENGTH = 100

/** Prefix of every token the app names itself, so the web list shows where it came from. */
private const val TOKEN_NAME_PREFIX = "Android · "

/**
 * The name to label this phone's API token with: the user-visible device name, then the Bluetooth
 * name, then "manufacturer model". Blank candidates are skipped; the result is trimmed and capped.
 */
fun resolveDeviceName(
    deviceName: String?,
    bluetoothName: String?,
    manufacturer: String?,
    model: String?,
): String {
    val named = listOf(deviceName, bluetoothName).firstNotNullOfOrNull { it.cleaned() }
    return (named ?: fromBuild(manufacturer.cleaned(), model.cleaned())).capped()
}

/** `Android · <device name>`, the name the app gives its own token. */
fun deviceTokenName(deviceName: String): String = TOKEN_NAME_PREFIX + deviceName

private fun fromBuild(manufacturer: String?, model: String?): String =
    when {
        model == null -> manufacturer ?: "Android"
        manufacturer == null -> model
        model.startsWith(manufacturer, ignoreCase = true) -> model
        else -> "$manufacturer $model"
    }

private fun String?.cleaned(): String? = this?.trim()?.ifBlank { null }

private fun String.capped(): String = take(MAX_DEVICE_NAME_LENGTH).trim()

/** Reads the device name from the system settings. */
@Singleton
class DeviceNameProvider
@Inject
constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) {
    fun deviceName(): String =
        resolveDeviceName(
            deviceName =
                setting {
                    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                },
            bluetoothName =
                setting { Settings.Secure.getString(context.contentResolver, "bluetooth_name") },
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
        )

    fun tokenName(): String = deviceTokenName(deviceName())

    private fun setting(read: () -> String?): String? = runCatching(read).getOrNull()
}
