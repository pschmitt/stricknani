package blue.anika.wolle.data.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceNameTest {
    private fun resolve(
        device: String? = null,
        bluetooth: String? = null,
        manufacturer: String? = "Google",
        model: String? = "Pixel 9",
    ) = resolveDeviceName(device, bluetooth, manufacturer, model)

    @Test
    fun `the user-visible device name wins`() {
        assertEquals("Philipp's phone", resolve(device = " Philipp's phone ", bluetooth = "bt"))
    }

    @Test
    fun `falls back to the bluetooth name, skipping blanks`() {
        assertEquals("My BT", resolve(device = "  ", bluetooth = "My BT"))
    }

    @Test
    fun `falls back to manufacturer and model`() {
        assertEquals("Google Pixel 9", resolve())
    }

    @Test
    fun `manufacturer is not repeated when the model starts with it`() {
        assertEquals("OnePlus 12", resolve(manufacturer = "OnePlus", model = "OnePlus 12"))
        assertEquals("ASUS ZenFone", resolve(manufacturer = "asus", model = "ASUS ZenFone"))
    }

    @Test
    fun `is capped at 100 characters and trimmed`() {
        val result = resolve(device = "a".repeat(99) + " " + "b".repeat(20))
        assertEquals(99, result.length)
        assertEquals(100, resolve(device = "x".repeat(300)).length)
    }

    @Test
    fun `never empty`() {
        assertEquals("Android", resolve(manufacturer = null, model = null))
    }

    @Test
    fun `token name is prefixed`() {
        assertEquals("Android · Pixel 9", deviceTokenName("Pixel 9"))
    }
}
