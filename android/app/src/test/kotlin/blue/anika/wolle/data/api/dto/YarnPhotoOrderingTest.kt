package blue.anika.wolle.data.api.dto

import org.junit.Assert.assertEquals
import org.junit.Test

class YarnPhotoOrderingTest {
    @Test
    fun `primary photo is shown first regardless of server order`() {
        val photos =
            listOf(
                YarnPhotoDto(9, "/nine", "/nine-thumb", "", false),
                YarnPhotoDto(3, "/three", "/three-thumb", "", true),
                YarnPhotoDto(7, "/seven", "/seven-thumb", "", false),
            )

        assertEquals(listOf(3, 7, 9), photos.primaryFirst().map { it.id })
    }
}
