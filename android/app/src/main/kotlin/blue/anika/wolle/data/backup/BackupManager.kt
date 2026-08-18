package blue.anika.wolle.data.backup

import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.data.settings.ThemeMode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Builds/reads the credentials + settings backup payload (SNA-15): a zip (`manifest.json`,
 * `credentials.json`, `settings.json`), optionally password-encrypted via [BackupCrypto].
 *
 * Deliberately excludes the Room cache and cached media, unlike syncwich's fuller backup: every
 * table in `AppDatabase` is a disposable server-side mirror (see
 * `DatabaseModule.provideAppDatabase`'s kdoc - a schema bump just wipes and resyncs, no migration
 * needed) that a plain sync pull reconstructs from scratch. Backing it up would risk restoring
 * stale rows that then fight the next sync; the ticket's own restore requirement ("reconcile
 * against a subsequent sync pull rather than treating the backup as more authoritative") is
 * satisfied more simply by never backing up anything that could conflict with the server in the
 * first place. The credentials + settings are the genuinely non-reproducible part - losing the API
 * token means re-onboarding from scratch.
 */
@Singleton
class BackupManager
@Inject
constructor(
    private val settingsRepository: SettingsRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val json: Json,
) {
    suspend fun export(password: String?): ByteArray {
        val credentials = settingsRepository.credentials.first()
        val themeMode = appPreferencesRepository.themeMode.first()
        val navbarItems = appPreferencesRepository.navbarItems.first().orEmpty()
        val manifest = BackupManifest(createdAtEpochMillis = System.currentTimeMillis())

        val zipped = ByteArrayOutputStream()
        ZipOutputStream(zipped).use { zip ->
            zip.writeEntry("manifest.json", json.encodeToString(manifest))
            zip.writeEntry(
                "credentials.json",
                json.encodeToString(BackupCredentials(credentials.serverUrl, credentials.apiToken)),
            )
            zip.writeEntry(
                "settings.json",
                json.encodeToString(BackupSettings(themeMode.name, navbarItems)),
            )
        }
        return BackupCrypto.encode(zipped.toByteArray(), password)
    }

    /**
     * Applies [data] to local storage. Doesn't touch the Room cache - see this class's kdoc - so
     * the caller should trigger a fresh sync (`SyncScheduler.syncNow()`) right after a successful
     * restore.
     *
     * @throws BackupPasswordRequiredException the backup is encrypted and [password] is null/blank
     * @throws BackupDecryptionException see [BackupCrypto.decode]
     */
    suspend fun import(data: ByteArray, password: String?) {
        val zipped = BackupCrypto.decode(data, password)
        var credentials: BackupCredentials? = null
        var settings: BackupSettings? = null
        ZipInputStream(zipped.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val content = zip.readBytes().decodeToString()
                when (entry.name) {
                    "credentials.json" ->
                        credentials = json.decodeFromString<BackupCredentials>(content)
                    "settings.json" -> settings = json.decodeFromString<BackupSettings>(content)
                }
                entry = zip.nextEntry
            }
        }
        val restoredCredentials =
            credentials ?: throw BackupDecryptionException("Backup is missing credentials.json")
        settingsRepository.save(restoredCredentials.serverUrl, restoredCredentials.apiToken)
        settings?.let { restored ->
            runCatching { ThemeMode.valueOf(restored.themeMode) }
                .getOrNull()
                ?.let { appPreferencesRepository.setThemeMode(it) }
            appPreferencesRepository.setNavbarItems(restored.navbarItems)
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
