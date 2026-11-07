package blue.anika.wolle.ui.navigation

import android.os.SystemClock
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlin.reflect.KClass

/**
 * How a screen change moves.
 * - [Stack]: drilling into a screen. The new screen slides in from the right as a card on top while
 *   the one below recedes (a little smaller, shifted left, dimmed). Going back is the exact
 *   reverse, and the predictive-back gesture peels the card off proportionally.
 * - [Sheet]: editors. Same idea, but the card rises from the bottom and the screen below barely
 *   recedes.
 * - [Fade]: moving between bottom-bar tabs (no stack, so nothing slides) and every change with
 *   animations switched off.
 */
internal enum class MotionKind {
    Fade,
    Stack,
    Sheet,
}

private val ROUTE_CLASSES: List<KClass<out Route>> =
    listOf(
        Route.Onboarding::class,
        Route.Home::class,
        Route.Projects::class,
        Route.Categories::class,
        Route.Yarns::class,
        Route.Search::class,
        Route.Settings::class,
        Route.ProjectDetail::class,
        Route.YarnDetail::class,
        Route.ProjectEditor::class,
        Route.YarnEditor::class,
        Route.Gauge::class,
        Route.Activity::class,
        Route.Libraries::class,
        Route.AdminAudit::class,
        Route.AdminAccounts::class,
        Route.SettingsCategoryRoute::class,
    )

private val EDITOR_ROUTES: Set<KClass<out Route>> =
    setOf(Route.ProjectEditor::class, Route.YarnEditor::class)

private val TOP_LEVEL_ROOTS: Set<KClass<out Route>> by lazy {
    TopLevelDestination.entries.map { it.route::class }.toSet()
}

/** The [Route] class a destination belongs to, or null for an unknown destination. */
internal fun NavDestination?.routeClass(): KClass<out Route>? =
    this?.let { destination -> ROUTE_CLASSES.firstOrNull { destination.hasRoute(it) } }

/**
 * Picks the motion for moving [from] -> [to].
 *
 * @param isPop the change is a pop (back / up), as opposed to a push.
 * @param isTabSwitch the change comes from tapping a bottom-bar tab. Tabs are siblings, not a
 *   stack, so they cross-fade instead of sliding.
 */
internal fun classifyTransition(
    from: KClass<out Route>?,
    to: KClass<out Route>?,
    isPop: Boolean,
    isTabSwitch: Boolean,
): MotionKind {
    if (from == null || to == null || isTabSwitch) return MotionKind.Fade
    if (from == Route.Onboarding::class || to == Route.Onboarding::class) return MotionKind.Fade
    // Landing on a list after deleting what was opened (a push that clears the stack) is a
    // "return", not a drill-down.
    if (!isPop && to in TOP_LEVEL_ROOTS && from !in TOP_LEVEL_ROOTS) return MotionKind.Fade
    // The card that moves is the one being opened, or the one being closed.
    val moving = if (isPop) from else to
    return if (moving in EDITOR_ROUTES) MotionKind.Sheet else MotionKind.Stack
}

private const val STACK_MILLIS = 380
private const val FADE_OUT_MILLIS = 90
private const val FADE_IN_MILLIS = 110
private const val BELOW_SCALE = 0.94f
private const val SHEET_BELOW_SCALE = 0.96f
private const val BELOW_SHIFT = 0.08f
private const val STACK_SCRIM = 0.15f
private const val SHEET_SCRIM = 0.10f
private const val FADE_IN_SCALE = 0.98f
private const val TAB_SWITCH_WINDOW_MILLIS = 600L

/** Decelerates into place: a push should land softly. */
private val PushEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Close to linear at the start so the predictive-back gesture tracks the finger. */
private val PopEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/**
 * Shared between the nav-host transition lambdas and the per-screen [MotionLayer]: which kind of
 * change is running and which back-stack entry is the moving card. Plain fields on purpose - they
 * are written while the transition is planned and only read in the draw phase, so nothing needs to
 * recompose.
 */
@Stable
internal class ScreenMotion {
    var kind: MotionKind = MotionKind.Fade
    var isPop: Boolean = false
    var cardId: String? = null
    private var tabSwitchUntil = 0L

    fun markTabSwitch() {
        tabSwitchUntil = SystemClock.uptimeMillis() + TAB_SWITCH_WINDOW_MILLIS
    }

    private val isTabSwitch: Boolean
        get() = SystemClock.uptimeMillis() < tabSwitchUntil

    /** Called by each transition lambda; idempotent for one navigation. */
    fun plan(scope: AnimatedContentTransitionScope<NavBackStackEntry>, isPop: Boolean): MotionKind {
        val kind =
            classifyTransition(
                scope.initialState.destination.routeClass(),
                scope.targetState.destination.routeClass(),
                isPop,
                isTabSwitch,
            )
        this.kind = kind
        this.isPop = isPop
        cardId = if (isPop) scope.initialState.id else scope.targetState.id
        return kind
    }
}

private fun <T> spec(isPop: Boolean): FiniteAnimationSpec<T> =
    tween(STACK_MILLIS, easing = if (isPop) PopEasing else PushEasing)

/** The screen that comes in. [isPop] is true for the one returning from below. */
internal fun ScreenMotion.enter(
    scope: AnimatedContentTransitionScope<NavBackStackEntry>,
    isPop: Boolean,
    reduceMotion: Boolean,
): EnterTransition {
    val kind = plan(scope, isPop)
    if (reduceMotion) {
        this.kind = MotionKind.Fade
        return EnterTransition.None
    }
    return when (kind) {
        MotionKind.Fade ->
            fadeIn(tween(FADE_IN_MILLIS, delayMillis = FADE_OUT_MILLIS)) +
                scaleIn(tween(FADE_IN_MILLIS, delayMillis = FADE_OUT_MILLIS), FADE_IN_SCALE)
        MotionKind.Stack ->
            if (isPop) {
                // The screen below grows back to full size and slides home.
                slideInHorizontally(spec(true)) { -(it * BELOW_SHIFT).toInt() } +
                    scaleIn(spec(true), BELOW_SCALE)
            } else {
                slideInHorizontally(spec(false)) { it }
            }
        MotionKind.Sheet ->
            if (isPop) scaleIn(spec(true), SHEET_BELOW_SCALE)
            else slideInVertically(spec(false)) { it }
    }
}

/** The screen that goes out. [isPop] is true for the card being closed. */
internal fun ScreenMotion.exit(
    scope: AnimatedContentTransitionScope<NavBackStackEntry>,
    isPop: Boolean,
    reduceMotion: Boolean,
): ExitTransition {
    val kind = plan(scope, isPop)
    if (reduceMotion) {
        this.kind = MotionKind.Fade
        return ExitTransition.None
    }
    return when (kind) {
        MotionKind.Fade -> fadeOut(tween(FADE_OUT_MILLIS))
        MotionKind.Stack ->
            if (isPop) slideOutHorizontally(spec(true)) { it }
            else
                slideOutHorizontally(spec(false)) { -(it * BELOW_SHIFT).toInt() } +
                    scaleOut(spec(false), BELOW_SCALE)
        MotionKind.Sheet ->
            if (isPop) slideOutVertically(spec(true)) { it }
            else scaleOut(spec(false), SHEET_BELOW_SCALE)
    }
}

/**
 * Wraps one destination and adds what the built-in enter/exit transitions cannot: the moving card
 * gets rounded leading corners and a soft shadow, and the screen it covers is dimmed. Everything is
 * a `graphicsLayer`/draw-phase effect driven by the transition's own progress, so it also follows
 * the predictive-back gesture and costs no layout or recomposition.
 */
@Composable
internal fun AnimatedContentScope.MotionLayer(
    motion: ScreenMotion,
    entryId: String,
    content: @Composable () -> Unit,
) {
    // 0 while the screen is settled, 1 while it is fully off-screen / fully covered.
    val away =
        transition.animateFloat(
            transitionSpec = {
                if (motion.kind == MotionKind.Fade) snap() else spec<Float>(motion.isPop)
            },
            label = "screen-motion",
        ) { state ->
            if (state == EnterExitState.Visible) 0f else 1f
        }
    val shapes = MaterialTheme.shapes
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val progress = away.value
                if (progress <= 0f || motion.kind == MotionKind.Fade || motion.cardId != entryId) {
                    shape = RectangleShape
                    clip = false
                    shadowElevation = 0f
                    return@graphicsLayer
                }
                // Corners and shadow fade in over the first stretch of travel so a settled
                // card is exactly square and flat.
                val strength = (progress * 3f).coerceAtMost(1f)
                val radius = shapes.extraLarge.topStart.toPx(size, this) * strength
                shape =
                    if (motion.kind == MotionKind.Sheet) {
                        RoundedCornerShape(topStart = radius, topEnd = radius)
                    } else {
                        RoundedCornerShape(topStart = radius, bottomStart = radius)
                    }
                clip = true
                shadowElevation = 18.dp.toPx() * strength
            }
            .drawWithContent {
                drawContent()
                val progress = away.value
                if (progress > 0f && motion.kind != MotionKind.Fade && motion.cardId != entryId) {
                    val scrim = if (motion.kind == MotionKind.Sheet) SHEET_SCRIM else STACK_SCRIM
                    drawRect(Color.Black.copy(alpha = scrim * progress))
                }
            }
    ) {
        content()
    }
}
