package blue.anika.wolle.data.update

import android.content.Intent
import android.content.IntentSender
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstallPathResolverTest {

    private class FakePrivileged(initial: PrivilegedState) : PrivilegedInstaller {
        val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<PrivilegedState> = mutableState
        var refreshes = 0
        var requests = 0

        override fun refresh() {
            refreshes++
        }

        override fun requestPermission() {
            requests++
        }

        override fun managerLaunchIntent(): Intent? = null

        override fun install(file: File, resultSender: IntentSender) = Unit
    }

    private class FakeInspector(private val silent: Boolean = false) : ApkInspector {
        override fun installed(): ApkFacts? = null

        override fun archive(file: File): ApkFacts? = null

        override fun canRequestInstalls(): Boolean = false

        override fun canInstallSilently(): Boolean = silent
    }

    private fun resolver(
        privileged: FakePrivileged,
        silent: Boolean = false,
        enabled: Boolean = true,
    ) = InstallPathResolver(privileged, FakeInspector(silent), flowOf(enabled))

    @Test
    fun `a ready Shizuku takes the install and refreshes first`() = runTest {
        val privileged = FakePrivileged(PrivilegedState.READY)
        assertEquals(InstallPath.PRIVILEGED, resolver(privileged).path())
        assertEquals(1, privileged.refreshes)
    }

    @Test
    fun `any other state takes the package installer`() = runTest {
        PrivilegedState.entries
            .filter { it != PrivilegedState.READY }
            .forEach {
                assertEquals(
                    InstallPath.PACKAGE_INSTALLER,
                    resolver(FakePrivileged(it)).path(),
                )
            }
    }

    @Test
    fun `a ready Shizuku is ignored while the updater is switched off`() = runTest {
        val privileged = FakePrivileged(PrivilegedState.READY)
        assertEquals(InstallPath.PACKAGE_INSTALLER, resolver(privileged, enabled = false).path())
        assertFalse(resolver(privileged, enabled = false).privilegedSelected())
    }

    @Test
    fun `switched off, silent install needs the installer of record`() = runTest {
        val ready = FakePrivileged(PrivilegedState.READY)
        assertFalse(resolver(ready, enabled = false).silentInstallPossible())
        assertTrue(resolver(ready, silent = true, enabled = false).silentInstallPossible())
        assertFalse(resolver(ready, enabled = false).silentInstallPossibleFlow().first())
        assertTrue(resolver(ready, enabled = true).silentInstallPossibleFlow().first())
    }

    @Test
    fun `it does not ask for permission while the updater is switched off`() = runTest {
        val privileged = FakePrivileged(PrivilegedState.PERMISSION_NEEDED)
        resolver(privileged, enabled = false).askForShizukuIfNeeded()
        assertEquals(0, privileged.requests)
    }

    @Test
    fun `silent install is possible through Shizuku or as installer of record`() = runTest {
        assertTrue(resolver(FakePrivileged(PrivilegedState.READY)).silentInstallPossible())
        val unavailable = FakePrivileged(PrivilegedState.UNAVAILABLE)
        assertTrue(resolver(unavailable, silent = true).silentInstallPossible())
        assertFalse(resolver(FakePrivileged(PrivilegedState.UNAVAILABLE)).silentInstallPossible())
    }

    @Test
    fun `it does not ask when Shizuku is ready or cannot be asked`() = runTest {
        PrivilegedState.entries
            .filter { it != PrivilegedState.PERMISSION_NEEDED }
            .forEach {
                val privileged = FakePrivileged(it)
                resolver(privileged).askForShizukuIfNeeded()
                assertEquals("$it", 0, privileged.requests)
            }
    }

    @Test
    fun `it asks once and resumes when the permission is granted`() = runTest {
        val privileged = FakePrivileged(PrivilegedState.PERMISSION_NEEDED)
        var done = false
        val job = launch {
            resolver(privileged).askForShizukuIfNeeded()
            done = true
        }
        runCurrent()
        assertEquals(1, privileged.requests)
        assertFalse(done)
        privileged.mutableState.value = PrivilegedState.READY
        runCurrent()
        assertTrue(done)
        job.join()
    }

    @Test
    fun `it resumes when the permission is denied`() = runTest {
        val privileged = FakePrivileged(PrivilegedState.PERMISSION_NEEDED)
        var done = false
        launch {
            resolver(privileged).askForShizukuIfNeeded()
            done = true
        }
        runCurrent()
        privileged.mutableState.value = PrivilegedState.PERMISSION_DENIED
        runCurrent()
        assertTrue(done)
    }

    @Test
    fun `it gives up when the dialog is ignored`() = runTest {
        val privileged = FakePrivileged(PrivilegedState.PERMISSION_NEEDED)
        var done = false
        launch {
            resolver(privileged).askForShizukuIfNeeded()
            done = true
        }
        runCurrent()
        advanceTimeBy(InstallPathResolver.PERMISSION_WAIT_MS - 1)
        runCurrent()
        assertFalse(done)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(done)
        assertEquals(1, privileged.requests)
    }
}
