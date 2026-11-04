package blue.anika.wolle.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegedInstallerTest {

    @Test
    fun `no manager and no binder is unavailable`() {
        assertEquals(PrivilegedState.UNAVAILABLE, privilegedState(false, false, false))
    }

    @Test
    fun `an installed manager without a running service is not running`() {
        assertEquals(PrivilegedState.NOT_RUNNING, privilegedState(true, false, false))
        // A stale grant means nothing while the service is down.
        assertEquals(PrivilegedState.NOT_RUNNING, privilegedState(true, false, true))
    }

    @Test
    fun `a running service without permission needs permission`() {
        assertEquals(PrivilegedState.PERMISSION_NEEDED, privilegedState(true, true, false))
    }

    @Test
    fun `a running service with permission is ready`() {
        assertEquals(PrivilegedState.READY, privilegedState(true, true, true))
    }

    @Test
    fun `a denied permission is reported as denied only while the service runs`() {
        assertEquals(
            PrivilegedState.PERMISSION_DENIED,
            privilegedState(true, true, false, permissionDenied = true),
        )
        assertEquals(
            PrivilegedState.READY,
            privilegedState(true, true, true, permissionDenied = true),
        )
        assertEquals(
            PrivilegedState.NOT_RUNNING,
            privilegedState(true, false, false, permissionDenied = true),
        )
    }

    @Test
    fun `outside developer mode Shizuku never takes the install`() {
        assertEquals(
            InstallPath.PACKAGE_INSTALLER,
            chooseInstallPath(true, PrivilegedState.READY, developerMode = false),
        )
    }

    @Test
    fun `Sui has a binder without any manager app`() {
        assertEquals(PrivilegedState.READY, privilegedState(false, true, true))
        assertEquals(PrivilegedState.PERMISSION_NEEDED, privilegedState(false, true, false))
    }

    @Test
    fun `a pre-v11 Shizuku is not usable`() {
        assertEquals(
            PrivilegedState.UNAVAILABLE,
            privilegedState(true, true, true, supportedVersion = false),
        )
    }

    @Test
    fun `only an enabled and ready Shizuku takes the install`() {
        assertEquals(
            InstallPath.PRIVILEGED,
            chooseInstallPath(true, PrivilegedState.READY),
        )
        assertEquals(
            InstallPath.PACKAGE_INSTALLER,
            chooseInstallPath(false, PrivilegedState.READY),
        )
        listOf(
                PrivilegedState.UNAVAILABLE,
                PrivilegedState.NOT_RUNNING,
                PrivilegedState.PERMISSION_NEEDED,
                PrivilegedState.PERMISSION_DENIED,
            )
            .forEach {
                assertEquals(InstallPath.PACKAGE_INSTALLER, chooseInstallPath(true, it))
            }
    }

    @Test
    fun `the no-op installer is unavailable and cannot install`() {
        assertEquals(PrivilegedState.UNAVAILABLE, NoPrivilegedInstaller.state.value)
        assertEquals(null, NoPrivilegedInstaller.managerLaunchIntent())
    }
}
