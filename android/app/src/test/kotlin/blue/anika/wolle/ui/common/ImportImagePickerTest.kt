package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportImagePickerTest {
    @Test
    fun hostDropsWwwAndPath() {
        assertEquals(
            "garnstudio.com",
            importImageHost("https://www.garnstudio.com/drops/mag/273/22/22-chart.jpg"),
        )
        assertEquals(
            "images.garnstudio.com",
            importImageHost("https://images.garnstudio.com/a.jpg"),
        )
    }

    @Test
    fun unparsableUrlFallsBackToItself() {
        assertEquals("not a url", importImageHost("not a url"))
    }
}
