package blue.anika.wolle.data.settings

/**
 * SNA-37's in-app language picker. [tag] is a BCP-47 language tag passed to
 * `AppCompatDelegate.setApplicationLocales` - `null` for [SYSTEM] clears the per-app override so
 * the device locale (with English as the resource-system fallback) applies again.
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    GERMAN("de"),
}
