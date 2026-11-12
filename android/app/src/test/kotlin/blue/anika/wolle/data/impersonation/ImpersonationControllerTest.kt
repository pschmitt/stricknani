package blue.anika.wolle.data.impersonation

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val SESSION =
    ImpersonationSession(
        targetId = 7,
        targetEmail = "member@example.invalid",
        targetName = "Member",
        adminEmail = "admin@example.invalid",
        expiresAtSeconds = 4_000,
    )

/**
 * Models [blue.anika.wolle.data.settings.SettingsRepository]: [session] is what the process started
 * with and never changes; [persisted] is what the next process will start with.
 */
private class FakeStore(override val session: ImpersonationSession? = null) : ImpersonationStore {
    var persisted: Pair<ImpersonationSession, String>? = session?.let { it to "sni_current" }
    var failWrites = false

    override fun begin(session: ImpersonationSession, token: String): Boolean {
        if (failWrites) return false
        persisted = session to token
        return true
    }

    override fun end(): Boolean {
        if (failWrites) return false
        persisted = null
        return true
    }
}

private class Events {
    val log = mutableListOf<String>()
}

private class FakeBackend(
    private val events: Events,
    var startFailure: Exception? = null,
    var stopFailure: Exception? = null,
    var stopHangs: Boolean = false,
) : ImpersonationBackend {
    override suspend fun start(targetId: Long): StartedImpersonation {
        events.log += "backend.start($targetId)"
        startFailure?.let { throw it }
        return StartedImpersonation("sni_new", SESSION)
    }

    override suspend fun stop() {
        events.log += "backend.stop"
        if (stopHangs) delay(60_000)
        stopFailure?.let { throw it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ImpersonationControllerTest {
    private val events = Events()

    private fun controller(
        store: FakeStore,
        backend: FakeBackend = FakeBackend(events),
        wipeOk: Boolean = true,
        onRestart: (Boolean) -> Unit = {},
    ) =
        ImpersonationController(
            store = store,
            backend = backend,
            wiper = {
                events.log += "wipe"
                wipeOk
            },
            restarter = {
                events.log += "restart(after=$it)"
                onRestart(it)
            },
            stopTimeoutMillis = 100,
        )

    @Test
    fun `start stores the credential before the restart and keeps this process's identity`() =
        runTest {
            val store = FakeStore()
            var storedAtRestart: Pair<ImpersonationSession, String>? = null
            val controller = controller(store) { storedAtRestart = store.persisted }

            assertEquals(StartResult.Started, controller.start(7))

            assertEquals(SESSION to "sni_new", storedAtRestart)
            assertEquals(listOf("backend.start(7)", "restart(after=false)"), events.log)
            // The running process stays the administrator's until the restart: a worker of this
            // process can not pick up the impersonation's credential half way.
            assertFalse(controller.isImpersonating)
            assertNull(controller.session)
        }

    @Test
    fun `a failed start changes nothing and does not restart`() = runTest {
        val store = FakeStore()
        val backend = FakeBackend(events, startFailure = IOException("offline"))
        val result = controller(store, backend).start(7)

        assertTrue(result is StartResult.Failed)
        assertNull(store.persisted)
        assertEquals(listOf("backend.start(7)"), events.log)
    }

    @Test
    fun `a credential that cannot be stored is revoked again and nothing restarts`() = runTest {
        val store = FakeStore().apply { failWrites = true }
        val result = controller(store).start(7)

        assertTrue(result is StartResult.Failed)
        assertEquals(listOf("backend.start(7)", "backend.stop"), events.log)
    }

    @Test
    fun `an impersonation cannot start another one`() = runTest {
        val result = controller(FakeStore(SESSION)).start(8)

        assertEquals(StartResult.AlreadyImpersonating, result)
        assertTrue(events.log.isEmpty())
    }

    @Test
    fun `stop revokes with the impersonation credential, forgets it, wipes and restarts home`() =
        runTest {
            val store = FakeStore(SESSION)
            var persistedAtRestart: Pair<ImpersonationSession, String>? = SESSION to "x"
            val controller = controller(store) { persistedAtRestart = store.persisted }

            assertTrue(controller.stop())

            // The revocation needs the credential, so it goes before it is forgotten.
            assertEquals(listOf("backend.stop", "wipe", "restart(after=true)"), events.log)
            assertNull(persistedAtRestart)
        }

    @Test
    fun `stop works offline, with a rejected credential and with a hanging server`() = runTest {
        for (backend in
            listOf(
                FakeBackend(events, stopFailure = IOException("offline")),
                FakeBackend(events, stopFailure = IllegalStateException("401")),
                FakeBackend(events, stopHangs = true),
            )) {
            events.log.clear()
            val store = FakeStore(SESSION)
            assertTrue(controller(store, backend).stop())
            assertNull(store.persisted)
            assertEquals("restart(after=true)", events.log.last())
        }
    }

    @Test
    fun `a failing wipe does not keep the administrator in the impersonation`() = runTest {
        val store = FakeStore(SESSION)
        assertTrue(controller(store, wipeOk = false).stop())
        assertNull(store.persisted)
        assertEquals("restart(after=true)", events.log.last())
    }

    @Test
    fun `stop that cannot forget the credential does not restart into the administrator`() =
        runTest {
            val store = FakeStore(SESSION).apply { failWrites = true }
            assertFalse(controller(store).stop())
            assertFalse(events.log.any { it.startsWith("restart") })
            assertFalse(events.log.contains("wipe"))
        }

    @Test
    fun `stop outside an impersonation does nothing`() = runTest {
        assertFalse(controller(FakeStore()).stop())
        assertTrue(events.log.isEmpty())
    }
}
