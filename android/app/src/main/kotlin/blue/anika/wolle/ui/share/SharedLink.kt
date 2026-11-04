package blue.anika.wolle.ui.share

import blue.anika.wolle.data.api.ImportAvailability
import java.net.URI

/** What a share can be imported as. */
enum class ShareImportKind {
    Project,
    Yarn,
}

/** The result of reading a share intent. */
sealed interface ShareIntake {
    /** Not a plain-text share at all (a normal launch, a link, a file). */
    data object NotAShare : ShareIntake

    /** A text share without an http(s) link in it. */
    data object NoLink : ShareIntake

    data class Link(val url: String) : ShareIntake
}

/** Reads the first http(s) link out of text that another app shared. */
object SharedLink {
    const val ACTION_SEND = "android.intent.action.SEND"

    private val WRAPPING_PREFIX = charArrayOf('<', '(', '[', '"', '\'')
    private val WRAPPING_SUFFIX = charArrayOf('>', ')', ']', '"', '\'', '.', ',', ';', '!', '?')

    /**
     * The first whitespace-separated token that is an http or https URL with a host. Browsers share
     * a bare URL or "title URL"; any other scheme (`file:`, `content:`, `javascript:` ...) is
     * ignored, and so is a token that only contains a link in a query string.
     */
    fun extract(text: CharSequence?): String? =
        text
            ?.split(Regex("\\s+"))
            .orEmpty()
            .map { it.trimStart(*WRAPPING_PREFIX).trimEnd(*WRAPPING_SUFFIX) }
            .firstOrNull(::isHttpUrl)

    fun isHttpUrl(candidate: String): Boolean {
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }

    /** The host to show in the chooser (without `www.`), or the whole link if it has none. */
    fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
            ?: url

    /** Classifies an incoming intent from its action, MIME type and `EXTRA_TEXT`. */
    fun intake(action: String?, mimeType: String?, text: CharSequence?): ShareIntake {
        if (action != ACTION_SEND || mimeType?.startsWith("text/plain") != true) {
            return ShareIntake.NotAShare
        }
        return extract(text)?.let(ShareIntake::Link) ?: ShareIntake.NoLink
    }
}

/** What the share UI shows for a pending link. Nothing here ever starts an import. */
sealed interface ShareStage {
    /** No share is waiting. */
    data object None : ShareStage

    /** Waiting for the user to sign in or set up the app; the link is kept. */
    data class AwaitingSignIn(val url: String) : ShareStage

    /** Ask whether to import the link as a project or a yarn. */
    data class Choose(val url: String) : ShareStage

    /** Link imports are off on the server: tell the user instead of offering a choice. */
    data class Unavailable(val url: String) : ShareStage
}

fun shareStage(
    pendingUrl: String?,
    isConfigured: Boolean,
    availability: ImportAvailability,
): ShareStage =
    when {
        pendingUrl == null -> ShareStage.None
        !isConfigured -> ShareStage.AwaitingSignIn(pendingUrl)
        !availability.url -> ShareStage.Unavailable(pendingUrl)
        else -> ShareStage.Choose(pendingUrl)
    }
