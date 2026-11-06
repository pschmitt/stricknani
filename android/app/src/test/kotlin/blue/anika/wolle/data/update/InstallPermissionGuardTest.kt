package blue.anika.wolle.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** STRICKNANI-E: Play builds have no REQUEST_INSTALL_PACKAGES and must never ask the platform. */
class InstallPermissionGuardTest {
    @Test
    fun `builds without self-update never ask the package manager`() {
        var asked = false
        assertFalse(
            canRequestInstallsSafely(selfUpdateEnabled = false) {
                asked = true
                true
            }
        )
        assertFalse(asked)
    }

    @Test
    fun `a missing install permission reads as cannot install`() {
        assertFalse(
            canRequestInstallsSafely(selfUpdateEnabled = true) {
                throw SecurityException(
                    "Need to declare android.permission.REQUEST_INSTALL_PACKAGES"
                )
            }
        )
        assertTrue(canRequestInstallsSafely(selfUpdateEnabled = true) { true })
    }
}
