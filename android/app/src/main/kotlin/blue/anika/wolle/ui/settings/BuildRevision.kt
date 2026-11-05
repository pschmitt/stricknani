package blue.anika.wolle.ui.settings

private const val COMMIT_URL_BASE = "https://github.com/pschmitt/stricknani/commit/"
private const val DIRTY_SUFFIX = "-dirty"
private val DESCRIBE_SHA = Regex(""".*-g([0-9a-f]{7,40})""")
private val PLAIN_SHA = Regex("""[0-9a-f]{7,40}""")

/**
 * The commit an APK was built from, parsed from `BuildConfig.GIT_REVISION`: a bare SHA (local
 * builds), `git describe` output (`v1.2.3-5-g9a7bfc70abcd`, CI) or an exact tag (`v1.2.3`).
 */
internal data class BuildRevision(val ref: String, val dirty: Boolean) {
    /** `9a7bfc70` or `9a7bfc70-dirty`; a tag is shown as it is. */
    val display: String
        get() = (if (PLAIN_SHA.matches(ref)) ref.take(8) else ref) + if (dirty) DIRTY_SUFFIX else ""

    /** GitHub commit page; never carries the `-dirty` suffix. GitHub resolves tags there too. */
    val commitUrl: String
        get() = COMMIT_URL_BASE + ref

    companion object {
        /** Null for a blank or `unknown` revision. */
        fun parse(raw: String?): BuildRevision? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed == "unknown") return null
            val dirty = trimmed.endsWith(DIRTY_SUFFIX)
            val clean = trimmed.removeSuffix(DIRTY_SUFFIX)
            if (clean.isEmpty()) return null
            val ref = DESCRIBE_SHA.matchEntire(clean)?.groupValues?.get(1) ?: clean
            return BuildRevision(ref, dirty)
        }
    }
}
