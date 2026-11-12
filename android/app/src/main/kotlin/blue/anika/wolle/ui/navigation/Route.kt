package blue.anika.wolle.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.MdiIcons
import blue.anika.wolle.ui.common.PhosphorIcons
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

/** Type-safe Navigation Compose destinations (see MainActivity/StricknaniNavHost). */
sealed interface Route {
    @Serializable data object Onboarding : Route

    /** Password sign-in again after the server rejected the saved token (nothing is wiped). */
    @Serializable data object SignInAgain : Route

    @Serializable data object Home : Route

    @Serializable data object Projects : Route

    @Serializable data object Categories : Route

    @Serializable data object Yarns : Route

    /**
     * The project list with filters preselected: [owner] is `me`, `shared` or a user id and
     * [status] `in_progress` or `done`, like `?owner=` and `?status=` of the website's
     * `/app/projects`. Same screen as [Projects]; Home's "Ongoing projects" opens it with `owner =
     * "me", status = "in_progress"`.
     */
    @Serializable
    data class ProjectsFiltered(
        val owner: String? = null,
        val status: String? = null,
        /** `only` lists the trash, like `?trashed=only`. */
        val trashed: String? = null,
    ) : Route

    /** The yarn list with the owner filter preselected; see [ProjectsFiltered]. */
    @Serializable
    data class YarnsByOwner(val owner: String? = null, val trashed: String? = null) : Route

    @Serializable data object Search : Route

    @Serializable data object Settings : Route

    /** Create (new project or yarn) - opened from the first row of More. */
    @Serializable data object Create : Route

    @Serializable data class ProjectDetail(val projectId: Int) : Route

    @Serializable data class YarnDetail(val yarnId: Int) : Route

    /**
     * `projectId == null` is create; a value is edit. `focus` opens the editor at one section or
     * step (see `ProjectEditorFocus`) - used when a detail section is long-pressed. A new project
     * can start from an import: [draftId] is a draft waiting for review (a card, a notification),
     * [importUrl] a link to import right away (a shared link).
     */
    @Serializable
    data class ProjectEditor(
        val projectId: Int? = null,
        val focus: String? = null,
        val draftId: String? = null,
        val importUrl: String? = null,
    ) : Route

    /** `yarnId == null` is create; a value is edit. The other arguments work as for projects. */
    @Serializable
    data class YarnEditor(
        val yarnId: Int? = null,
        val focus: String? = null,
        val draftId: String? = null,
        val importUrl: String? = null,
    ) : Route

    /** Gauge calculator (SNA-11) - pure math, reachable from `HomeScreen`'s top bar. */
    @Serializable data object Gauge : Route

    /** Open-source libraries used (SNA-34) - reachable from Settings' About category. */
    @Serializable data object Libraries : Route

    /** The full "Recent activity" timeline - opened from Home's "See all". */
    @Serializable data object Activity : Route

    /**
     * Administrator audit log (SNA-86) - reachable from Settings -> Admin. With [userId] it shows
     * only the events that account took part in (the accounts list's "Activity" entry).
     */
    @Serializable data class AdminAudit(val userId: Long? = null) : Route

    /** The trash: deleted items wait here for 90 days - reachable from More. */
    @Serializable data object Trash : Route

    /**
     * A project or yarn in the trash, read-only and online-only (it is not in Room): [kind] is
     * `project` or `yarn`. Opened from the Trash screen.
     */
    @Serializable data class TrashedItem(val kind: String, val id: Int) : Route

    /** Administrator account list (SNA-86) - reachable from Settings -> Admin. */
    @Serializable data object AdminAccounts : Route

    /** Administrator backups and the guarded restore - reachable from Settings -> Admin. */
    @Serializable data object AdminBackups : Route

    /**
     * A Settings sub-screen (SNA-21). [categoryName] is `SettingsCategory.name` (a plain `String`
     * rather than the enum itself, to sidestep any Navigation Compose enum-as-route-arg quirks).
     */
    @Serializable data class SettingsCategoryRoute(val categoryName: String) : Route
}

/**
 * Destination that opens the projects list with [ownerId] preselected as owner filter (admin users
 * menu; the web equivalent is `/app/projects?owner=<id>`).
 */
fun projectsOwnedByRoute(ownerId: Long): Route = Route.ProjectsFiltered(owner = ownerId.toString())

/** Like [projectsOwnedByRoute] for the yarns list (`/app/yarns?owner=<id>` on the web). */
fun yarnsOwnedByRoute(ownerId: Long): Route = Route.YarnsByOwner(owner = ownerId.toString())

/**
 * The customizable bottom-navigation destinations, in default display order (see android/TODO.md
 * SNA-5/SNA-9/SNA-16). Categories remains available as an optional destination, but is hidden by
 * default because it is also available from Settings. [GAUGE] (SNA-11's calculator, previously only
 * reachable via `HomeScreen`'s top bar icon) was added as a navbar option per user feedback
 * (2026-08-18) - `NavbarCustomization.sanitize`'s "missing destination" handling appends it as
 * visible for anyone with an already-saved navbar preference, same as any other newly added
 * destination.
 */
enum class TopLevelDestination(
    val route: Route,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val routeTypes: Set<KClass<out Route>>,
    val defaultVisible: Boolean = true,
) {
    HOME(
        Route.Home,
        R.string.destination_label_home,
        PhosphorIcons.HouseLine,
        setOf(Route.Home::class),
    ),
    PROJECTS(
        Route.Projects,
        R.string.destination_label_projects,
        PhosphorIcons.Hoodie,
        setOf(
            Route.Projects::class,
            Route.ProjectsFiltered::class,
            Route.ProjectDetail::class,
            Route.ProjectEditor::class,
        ),
    ),
    CATEGORIES(
        Route.Categories,
        R.string.destination_label_categories,
        PhosphorIcons.Tag,
        setOf(Route.Categories::class),
        defaultVisible = false,
    ),
    YARNS(
        Route.Yarns,
        R.string.destination_label_yarns,
        MdiIcons.YarnBall,
        setOf(
            Route.Yarns::class,
            Route.YarnsByOwner::class,
            Route.YarnDetail::class,
            Route.YarnEditor::class,
        ),
    ),
    SEARCH(
        Route.Search,
        R.string.destination_label_search,
        PhosphorIcons.MagnifyingGlass,
        setOf(Route.Search::class),
        // Hidden by default so the bar matches the website's four tabs (Home, Projects, Yarns,
        // More); More -> Navigation can bring it back.
        defaultVisible = false,
    ),
    GAUGE(
        Route.Gauge,
        R.string.destination_label_gauge,
        PhosphorIcons.Ruler,
        setOf(Route.Gauge::class),
        // Hidden by default to keep the bar at four destinations; More and Home's title bar open
        // the calculator, and More -> Navigation can add it back.
        defaultVisible = false,
    ),
    /**
     * The "More" tab (the id stays `SETTINGS`: it is stored in saved navbar preferences). It shows
     * the account avatar in the bar when there is one, otherwise the [icon].
     */
    SETTINGS(
        Route.Settings,
        R.string.destination_label_more,
        Icons.Default.MoreHoriz,
        setOf(
            Route.Settings::class,
            Route.Create::class,
            Route.SettingsCategoryRoute::class,
            Route.Libraries::class,
            Route.AdminAudit::class,
            Route.AdminAccounts::class,
            Route.AdminBackups::class,
            Route.Trash::class,
            Route.TrashedItem::class,
            Route.Activity::class,
        ),
    ),
}

@Composable fun TopLevelDestination.label(): String = stringResource(labelRes)
