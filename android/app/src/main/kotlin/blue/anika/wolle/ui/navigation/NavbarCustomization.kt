package blue.anika.wolle.ui.navigation

import blue.anika.wolle.data.settings.NavbarItemPreference

/**
 * Maps `AppPreferencesRepository.navbarItems`'s raw `NavbarItemPreference` list onto the actual
 * `TopLevelDestination` enum (SNA-16). Kept out of the data layer since it needs to know which
 * destinations currently exist.
 */
object NavbarCustomization {

    /**
     * `null`/empty/corrupt input falls back to the destinations' declared defaults and order.
     * [TopLevelDestination.SETTINGS] is always forced visible - it's the only way back to this
     * customization UI, so hiding it would lock the user out of un-hiding anything. Unknown ids are
     * dropped (e.g. a destination removed in a later release); destinations missing from a saved
     * preference (e.g. one added in a later release) are appended with their declared default
     * visibility.
     */
    fun sanitize(raw: List<NavbarItemPreference>?): List<NavbarItemPreference> {
        val known =
            raw.orEmpty()
                .distinctBy { it.id }
                .mapNotNull { pref ->
                    TopLevelDestination.entries
                        .find { it.name == pref.id }
                        ?.let { destination ->
                            val visible = destination == TopLevelDestination.SETTINGS || pref.visible
                            NavbarItemPreference(pref.id, visible)
                        }
                }
        val missing =
            TopLevelDestination.entries
                .filterNot { destination -> known.any { it.id == destination.name } }
                .map { NavbarItemPreference(it.name, visible = it.defaultVisible) }
        return known + missing
    }

    fun defaultPreferences(): List<NavbarItemPreference> =
        TopLevelDestination.entries.map { destination ->
            NavbarItemPreference(destination.name, visible = destination.defaultVisible)
        }

    fun toDestinations(items: List<NavbarItemPreference>): List<TopLevelDestination> =
        items.mapNotNull { pref ->
            TopLevelDestination.entries.find { it.name == pref.id }
        }

    fun visibleDestinations(items: List<NavbarItemPreference>): List<TopLevelDestination> =
        toDestinations(items.filter { it.visible })
}
