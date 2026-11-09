package blue.anika.wolle.ui.create

import blue.anika.wolle.R
import blue.anika.wolle.data.api.ApiCapabilitiesState
import blue.anika.wolle.data.api.ApiGeneration
import blue.anika.wolle.data.api.offersProjectImport
import blue.anika.wolle.data.api.offersYarnImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateOptionsTest {
    @Test
    fun `two tiles in the website order, no separate imports`() {
        assertEquals(
            listOf(CreateKind.NewProject, CreateKind.NewYarn),
            createOptions().map { it.kind },
        )
    }

    @Test
    fun `short texts without imports, the web wording with imports`() {
        assertEquals(
            listOf(R.string.more_create_project_subtitle, R.string.more_create_yarn_subtitle),
            createOptions(ImportOffers()).map { it.descriptionRes },
        )
        assertEquals(
            listOf(R.string.create_project_subtitle_import, R.string.create_yarn_subtitle_import),
            createOptions(ImportOffers(project = true, yarn = true)).map { it.descriptionRes },
        )
        assertEquals(
            listOf(R.string.create_project_subtitle_import, R.string.more_create_yarn_subtitle),
            createOptions(ImportOffers(project = true)).map { it.descriptionRes },
        )
    }

    @Test
    fun `card subtitle follows the offers, then the mode`() {
        val imports = ImportOffers(project = true, yarn = true)
        assertEquals(
            R.string.more_create_subtitle_import,
            moreCreateSubtitleRes(imports, localMode = false),
        )
        assertEquals(
            R.string.more_create_subtitle,
            moreCreateSubtitleRes(ImportOffers(), localMode = false),
        )
        assertEquals(
            R.string.more_create_subtitle_local,
            moreCreateSubtitleRes(ImportOffers(), localMode = true),
        )
    }

    @Test
    fun `local only mode never offers imports`() {
        val v2 = ApiCapabilitiesState(generation = ApiGeneration.V2, importsUrl = true)
        assertFalse(v2.offersProjectImport(localMode = true))
        assertFalse(v2.offersYarnImport(localMode = true))
        assertTrue(v2.offersProjectImport(localMode = false))
        assertTrue(v2.offersYarnImport(localMode = false))
    }

    @Test
    fun `a v2 server with link import off still imports projects from files, not yarns`() {
        val v2 = ApiCapabilitiesState(generation = ApiGeneration.V2, importsUrl = false)
        assertTrue(v2.offersProjectImport(localMode = false))
        assertFalse(v2.offersYarnImport(localMode = false))
    }
}
