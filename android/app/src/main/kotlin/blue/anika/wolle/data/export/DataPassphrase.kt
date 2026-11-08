package blue.anika.wolle.data.export

import android.net.Uri
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Shortest passphrase the server accepts, in UTF-8 bytes (`docs/data-export.md`, "Encryption"). */
const val PASSPHRASE_MIN_BYTES = 12

/** Longest passphrase the server accepts, in UTF-8 bytes. */
const val PASSPHRASE_MAX_BYTES = 1024

/** The request header that carries a restore passphrase. */
const val PASSPHRASE_HEADER = "X-Archive-Passphrase"

/** The size of [passphrase] in UTF-8 bytes: the server counts bytes, not characters. */
fun passphraseBytes(passphrase: String): Int = passphrase.toByteArray(Charsets.UTF_8).size

/** What is wrong with a new passphrase and its repetition. */
enum class PassphraseProblem {
    /** Fewer than [PASSPHRASE_MIN_BYTES] UTF-8 bytes. */
    TooShort,

    /** Longer than [PASSPHRASE_MAX_BYTES] UTF-8 bytes (the field stops input before this). */
    TooLong,

    /** The repetition differs. */
    Mismatch,
}

/** The first problem of a new [passphrase] and its [repeat], or null when both are fine. */
fun passphraseProblem(passphrase: String, repeat: String): PassphraseProblem? {
    val bytes = passphraseBytes(passphrase)
    return when {
        bytes < PASSPHRASE_MIN_BYTES -> PassphraseProblem.TooShort
        bytes > PASSPHRASE_MAX_BYTES -> PassphraseProblem.TooLong
        passphrase != repeat -> PassphraseProblem.Mismatch
        else -> null
    }
}

/** Whether [passphrase] is a length the server takes (a restore passphrase has no minimum). */
fun passphraseFits(passphrase: String): Boolean =
    passphraseBytes(passphrase) <= PASSPHRASE_MAX_BYTES

/** base64url without padding of the UTF-8 bytes, the value of [PASSPHRASE_HEADER]. */
fun encodePassphraseHeader(passphrase: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(passphrase.toByteArray(Charsets.UTF_8))

/**
 * Keeps passphrases in memory only, between the screen and the transfer that needs one. A worker's
 * input data and a job's extras are written to disk, so they carry only the random id [put]
 * returns, never the passphrase. The transfer [take]s it exactly once: it is removed on the first
 * read. If the process dies first, or the entry is older than [ttlMillis], [take] finds nothing and
 * the transfer fails like any other failure; the user starts it again and types the passphrase
 * again.
 */
@Singleton
class PassphraseHolder
internal constructor(
    private val ttlMillis: Long,
    private val clock: () -> Long,
) {
    @Inject constructor() : this(DEFAULT_TTL_MILLIS, System::currentTimeMillis)

    private class Entry(val passphrase: String, val storedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Stores [passphrase] and returns the id to hand to the transfer. */
    fun put(passphrase: String): String {
        purgeExpired()
        val id = UUID.randomUUID().toString()
        entries[id] = Entry(passphrase, clock())
        return id
    }

    /** The passphrase for [id], removed by this call; null when unknown, used or expired. */
    fun take(id: String): String? {
        val entry = entries.remove(id) ?: return null
        return entry.passphrase.takeIf { clock() - entry.storedAt <= ttlMillis }
    }

    /** Drops [id] without reading it. */
    fun discard(id: String) {
        entries.remove(id)
    }

    internal val size: Int
        get() = entries.size

    private fun purgeExpired() {
        val now = clock()
        entries.entries.removeIf { now - it.value.storedAt > ttlMillis }
    }

    companion object {
        /** A transfer that waits for the network longer than this fails rather than keep it. */
        const val DEFAULT_TTL_MILLIS = 30L * 60 * 1000
    }
}

/**
 * A restore the server refused for want of a passphrase. [wrong]: one was sent and did not open the
 * file. Held in memory only, so the Backups screen can ask again (also when the user comes back
 * through the failure notification).
 */
data class PassphrasePrompt(val source: Uri, val totalBytes: Long, val wrong: Boolean)
