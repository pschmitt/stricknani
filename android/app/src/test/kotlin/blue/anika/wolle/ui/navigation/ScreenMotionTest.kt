package blue.anika.wolle.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenMotionTest {

    private fun kind(
        from: kotlin.reflect.KClass<out Route>?,
        to: kotlin.reflect.KClass<out Route>?,
        isPop: Boolean = false,
        tab: Boolean = false,
    ) = classifyTransition(from, to, isPop, tab)

    @Test
    fun `tab switches fade whatever the routes are`() {
        assertEquals(
            MotionKind.Fade,
            kind(Route.Home::class, Route.Projects::class, tab = true),
        )
        assertEquals(
            MotionKind.Fade,
            kind(Route.ProjectDetail::class, Route.Home::class, isPop = true, tab = true),
        )
    }

    @Test
    fun `opening and closing a detail stacks and unstacks`() {
        assertEquals(MotionKind.Stack, kind(Route.Projects::class, Route.ProjectDetail::class))
        assertEquals(
            MotionKind.Stack,
            kind(Route.ProjectDetail::class, Route.Projects::class, isPop = true),
        )
        assertEquals(
            MotionKind.Stack,
            kind(Route.Settings::class, Route.SettingsCategoryRoute::class),
        )
    }

    @Test
    fun `a top-level screen opened from another one is a drill-down`() {
        assertEquals(MotionKind.Stack, kind(Route.Home::class, Route.Gauge::class))
        assertEquals(MotionKind.Stack, kind(Route.Home::class, Route.Yarns::class))
        assertEquals(MotionKind.Stack, kind(Route.Gauge::class, Route.Home::class, isPop = true))
    }

    @Test
    fun `editors are sheets in both directions`() {
        assertEquals(MotionKind.Sheet, kind(Route.Projects::class, Route.ProjectEditor::class))
        assertEquals(MotionKind.Sheet, kind(Route.ProjectDetail::class, Route.YarnEditor::class))
        assertEquals(
            MotionKind.Sheet,
            kind(Route.YarnEditor::class, Route.YarnDetail::class, isPop = true),
        )
    }

    @Test
    fun `returning to a list after a delete is a fade`() {
        assertEquals(MotionKind.Fade, kind(Route.ProjectDetail::class, Route.Projects::class))
        assertEquals(MotionKind.Fade, kind(Route.YarnEditor::class, Route.Yarns::class))
    }

    @Test
    fun `unknown and onboarding routes fade`() {
        assertEquals(MotionKind.Fade, kind(null, Route.Home::class))
        assertEquals(MotionKind.Fade, kind(Route.Onboarding::class, Route.Home::class))
    }
}
