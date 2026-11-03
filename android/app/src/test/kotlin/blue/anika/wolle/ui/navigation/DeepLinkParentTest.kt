package blue.anika.wolle.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkParentTest {

    @Test
    fun `projects open on top of the project list`() {
        assertEquals(Route.Projects, deepLinkParent(Route.ProjectDetail(7)))
        assertEquals(Route.Projects, deepLinkParent(Route.ProjectEditor(7)))
    }

    @Test
    fun `yarns open on top of the yarn list`() {
        assertEquals(Route.Yarns, deepLinkParent(Route.YarnDetail(3)))
        assertEquals(Route.Yarns, deepLinkParent(Route.YarnEditor(3)))
    }

    @Test
    fun `other routes keep the plain start destination underneath`() {
        assertNull(deepLinkParent(Route.Home))
        assertNull(deepLinkParent(Route.Settings))
    }
}
