package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.dto.ImageDto
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.YarnPhotoDto
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.MutationOperation
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.data.uploads.PendingMediaOrder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class MediaOrderingTest {
    private val json = Json

    private fun image(id: Int, stepId: Int? = null, title: Boolean = false, position: Int? = null) =
        ImageDto(
            id = id,
            url = "/i/$id",
            thumbnailUrl = "/i/$id/t",
            altText = "",
            imageType = if (stepId == null) "title" else "step",
            isTitleImage = title,
            isStitchSample = false,
            stepId = stepId,
            position = position,
        )

    private fun project(images: List<ImageDto>) =
        ProjectDto(
            id = 1,
            name = "P",
            isAiEnhanced = false,
            isFavorite = false,
            createdAt = "2026-11-03T00:00:00Z",
            updatedAt = "2026-11-03T00:00:00Z",
            images = images,
        )

    private fun photo(id: Int, primary: Boolean = false, position: Int? = null) =
        YarnPhotoDto(id, "/p/$id", "/p/$id/t", "", primary, position)

    private fun yarn(photos: List<YarnPhotoDto>) =
        YarnDto(
            id = 1,
            name = "Y",
            isAiEnhanced = false,
            isFavorite = false,
            createdAt = "2026-11-03T00:00:00Z",
            updatedAt = "2026-11-03T00:00:00Z",
            photos = photos,
        )

    @Test
    fun `canMakeCover is true only for later server photos and moves them to the front`() {
        assertFalse(canMakeCover(0, 3))
        assertTrue(canMakeCover(1, 3))
        assertTrue(canMakeCover(2, 3))
        assertFalse(canMakeCover(3, 3))
        assertFalse(canMakeCover(-1, 3))
        assertEquals(listOf(30, 10, 20), listOf(10, 20, 30).movedItem(2, 0))
    }

    @Test
    fun `movedItem moves one item and ignores bad indexes`() {
        assertEquals(listOf(2, 1, 3), listOf(1, 2, 3).movedItem(1, 0))
        assertEquals(listOf(2, 3, 1), listOf(1, 2, 3).movedItem(0, 2))
        val same = listOf(1, 2, 3)
        assertSame(same, same.movedItem(1, 1))
        assertSame(same, same.movedItem(0, 3))
        assertSame(same, same.movedItem(-1, 0))
    }

    @Test
    fun `an order is sent only when the remaining ids really moved`() {
        assertNull(reorderedIdsOrNull(listOf(1, 2, 3), listOf(1, 2, 3), emptySet()))
        assertEquals(
            listOf(3, 1, 2),
            reorderedIdsOrNull(listOf(1, 2, 3), listOf(3, 1, 2), emptySet()),
        )
        // Deleting the cover alone is not a reorder; the server promotes the next image itself.
        assertNull(reorderedIdsOrNull(listOf(1, 2, 3), listOf(2, 3), setOf(1)))
        assertEquals(listOf(3, 2), reorderedIdsOrNull(listOf(1, 2, 3), listOf(3, 2), setOf(1)))
        // A list that does not hold exactly the surviving ids is never sent.
        assertNull(reorderedIdsOrNull(listOf(1, 2), listOf(2), emptySet()))
    }

    @Test
    fun `title order moves the cover and leaves step images in their slots`() {
        val before =
            project(
                listOf(
                    image(1, title = true, position = 0),
                    image(10, stepId = 5, position = 0),
                    image(2, position = 1),
                    image(11, stepId = 5, position = 1),
                    image(3, position = 2),
                )
            )

        val after = before.withTitleImageOrder(listOf(3, 1, 2))

        assertEquals(listOf(3, 10, 1, 11, 2), after.images.map { it.id })
        assertEquals(listOf(3, 1, 2), after.titleImages().map { it.id })
        assertEquals(listOf(0, 1, 2), after.titleImages().map { it.position })
        assertEquals(listOf(true, false, false), after.titleImages().map { it.isTitleImage })
        assertEquals(listOf(true, false, false), after.titleImages().map { it.isPrimary })
        assertEquals(
            before.images.filter { it.stepId == 5 },
            after.images.filter { it.stepId == 5 },
        )
    }

    @Test
    fun `a step order keeps the project cover`() {
        val before =
            project(
                listOf(
                    image(1, title = true, position = 0),
                    image(10, stepId = 5, position = 0),
                    image(11, stepId = 5, position = 1),
                    image(20, stepId = 6, position = 0),
                )
            )

        val after = before.withStepImageOrder(5, listOf(11, 10))

        assertEquals(listOf(11, 10), after.stepImages(5).map { it.id })
        assertEquals(listOf(0, 1), after.stepImages(5).map { it.position })
        assertEquals(before.images.first { it.id == 1 }, after.images.first { it.id == 1 })
        assertEquals(before.images.first { it.id == 20 }, after.images.first { it.id == 20 })
    }

    @Test
    fun `an order that is not exactly the group is not applied locally`() {
        val before = project(listOf(image(1), image(2), image(3)))
        assertSame(before, before.withTitleImageOrder(listOf(1, 2)))
        assertSame(before, before.withTitleImageOrder(listOf(1, 2, 2)))
        assertSame(before, before.withTitleImageOrder(listOf(1, 2, 9)))
    }

    @Test
    fun `server images come back sorted by position, unpositioned ones keep their order`() {
        val p =
            project(listOf(image(1, position = 2), image(2, position = 0), image(3, position = 1)))
        assertEquals(listOf(2, 3, 1), p.titleImages().map { it.id })
        val legacy = project(listOf(image(7), image(5), image(6)))
        assertEquals(listOf(7, 5, 6), legacy.titleImages().map { it.id })
    }

    @Test
    fun `a photo order makes the first photo the main one`() {
        val after =
            yarn(
                    listOf(
                        photo(1, primary = true, position = 0),
                        photo(2, position = 1),
                        photo(3, position = 2),
                    )
                )
                .withPhotoOrder(listOf(2, 3, 1))

        assertEquals(listOf(2, 3, 1), after.photos.map { it.id })
        assertEquals(listOf(true, false, false), after.photos.map { it.isPrimary })
        assertEquals(listOf(0, 1, 2), after.photos.map { it.position })
        val bad = yarn(listOf(photo(1), photo(2)))
        assertSame(bad, bad.withPhotoOrder(listOf(1)))
    }

    @Test
    fun `a newer step order replaces only the queued order of the same step`() {
        fun queued(id: Long, order: PendingMediaOrder) =
            PendingMutationEntity(
                id = id,
                entityType = MutationEntityType.PROJECT,
                operation = MutationOperation.PROJECT_STEP_IMAGE_ORDER,
                localId = 1,
                payloadJson = json.encodeToString(order),
                createdAt = 0,
            )
        val rows =
            listOf(
                queued(1, PendingMediaOrder(listOf(1, 2), 5)),
                queued(2, PendingMediaOrder(listOf(3, 4), 6)),
            )

        assertEquals(2L, findQueuedOrder(rows, 6, json)?.id)
        assertEquals(1L, findQueuedOrder(rows, 5, json)?.id)
        assertNull(findQueuedOrder(rows, 7, json))
        assertNull(findQueuedOrder(rows, null, json))
        val titleRow = queued(3, PendingMediaOrder(listOf(9, 8)))
        assertEquals(3L, findQueuedOrder(listOf(titleRow), null, json)?.id)
    }

    private fun http(status: Int) =
        HttpException(
            Response.error<Any>(status, "{}".toResponseBody("application/json".toMediaType()))
        )

    @Test
    fun `a rejected order is dropped, a transient failure stays queued`() = runTest {
        assertTrue(sendMediaOrder {})
        for (status in listOf(404, 405, 422)) {
            assertFalse("HTTP $status", sendMediaOrder { throw http(status) })
        }
        assertFailsWith<HttpException> { sendMediaOrder { throw http(500) } }
        assertFailsWith<HttpException> { sendMediaOrder { throw http(401) } }
        assertFailsWith<java.io.IOException> {
            sendMediaOrder { throw java.io.IOException("offline") }
        }
    }

    @Test
    fun `the canonical cover leads the gallery over a stale title flag and its position`() {
        val stale = image(13, title = true, position = 1).copy(isPrimary = false)
        val cover = image(24, position = 2).copy(isPrimary = true)
        val other = image(23, position = 0).copy(isPrimary = false)
        val detail = project(listOf(other, stale, cover))

        assertEquals(listOf(24, 23, 13), detail.galleryImages().map { it.id })
        assertEquals(listOf(24, 23, 13), detail.titleImages().map { it.id })
        assertTrue(cover.isCover())
        assertFalse(stale.isCover())
        // Payloads without is_primary fall back to the title flag.
        assertTrue(image(1, title = true).isCover())
    }
}

private suspend inline fun <reified T : Throwable> assertFailsWith(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return
        throw e
    }
    fail("Expected ${T::class.java.simpleName}")
}
