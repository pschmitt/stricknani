package blue.anika.wolle.ui.sharing

import blue.anika.wolle.data.api.dto.AccessDto
import blue.anika.wolle.data.api.dto.PeoplePageDto
import blue.anika.wolle.data.api.dto.PersonDto
import blue.anika.wolle.data.api.dto.ShareDto
import blue.anika.wolle.data.api.dto.ShareListDto
import blue.anika.wolle.data.api.dto.TransferResultDto
import blue.anika.wolle.data.api.dto.YarnCandidatesPageDto
import blue.anika.wolle.data.repository.ShareRole
import blue.anika.wolle.data.repository.SharedKind
import blue.anika.wolle.data.repository.SharingError
import blue.anika.wolle.data.repository.SharingException
import blue.anika.wolle.data.repository.SharingGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharingControllerTest {
    private val anna = PersonDto(7, "Anna")
    private val ben = PersonDto(12, "Ben")
    private val cleo = PersonDto(13, "Cleo")

    private class FakeGateway(
        var shares: MutableList<ShareDto> = mutableListOf(),
        val owner: PersonDto = PersonDto(1, "Me"),
        val directory: List<PersonDto> = emptyList(),
    ) : SharingGateway {
        val calls = mutableListOf<String>()
        var failNext: SharingError? = null
        var transferMove: Boolean? = null

        private fun maybeFail() {
            failNext?.let {
                failNext = null
                throw SharingException(it)
            }
        }

        override suspend fun shares(kind: SharedKind, id: Int): ShareListDto {
            calls += "shares"
            return ShareListDto(owner, shares.toList())
        }

        override suspend fun add(
            kind: SharedKind,
            id: Int,
            userId: Long,
            role: ShareRole,
        ): ShareDto {
            calls += "add:$userId:${role.wire}"
            maybeFail()
            return ShareDto(directory.first { it.id == userId }, role.wire).also { shares += it }
        }

        override suspend fun changeRole(
            kind: SharedKind,
            id: Int,
            userId: Long,
            role: ShareRole,
        ): ShareDto {
            calls += "role:$userId:${role.wire}"
            maybeFail()
            val updated = ShareDto(shares.first { it.user.id == userId }.user, role.wire)
            shares = shares.map { if (it.user.id == userId) updated else it }.toMutableList()
            return updated
        }

        override suspend fun revoke(kind: SharedKind, id: Int, userId: Long) {
            calls += "revoke:$userId"
            maybeFail()
            shares.removeAll { it.user.id == userId }
        }

        override suspend fun leave(kind: SharedKind, id: Int) {
            calls += "leave"
            maybeFail()
        }

        override suspend fun transfer(
            kind: SharedKind,
            id: Int,
            newOwnerId: Long,
            moveLinkedYarns: Boolean,
        ): TransferResultDto {
            calls += "transfer:$newOwnerId"
            transferMove = moveLinkedYarns
            maybeFail()
            return TransferResultDto(
                newOwner = directory.first { it.id == newOwnerId },
                access = AccessDto("editor", false, true, false),
            )
        }

        /** Holds the answer of a query until the test lets it through (out-of-order replies). */
        val gates = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<Unit>>()

        override suspend fun people(query: String?, page: Int): PeoplePageDto {
            calls += "people:${query.orEmpty()}"
            gates[query.orEmpty()]?.await()
            return PeoplePageDto(
                items = directory.filter { query.isNullOrBlank() || it.name.contains(query, true) }
            )
        }

        override suspend fun yarnCandidates(
            projectId: Int,
            query: String?,
            page: Int,
        ): YarnCandidatesPageDto = YarnCandidatesPageDto()
    }

    private fun TestScope.controller(
        gateway: FakeGateway,
        kind: SharedKind = SharedKind.Project,
        online: Boolean? = true,
        onLeft: () -> Unit = {},
    ) =
        SharingController(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            kind = kind,
            id = 4,
            repository = gateway,
            hasConnection = { online },
            onLeft = onLeft,
        )

    @Test
    fun `offline the sheet is disabled with an explanation and nothing is requested`() = runTest {
        val gateway = FakeGateway(directory = listOf(ben))
        val c = controller(gateway, online = false)

        c.open()
        advanceUntilIdle()

        assertTrue(c.state.value.offline)
        assertTrue("sharing is never queued or attempted offline", gateway.calls.isEmpty())
    }

    @Test
    fun `an unknown connection state is treated as online`() = runTest {
        val gateway = FakeGateway(directory = listOf(ben))
        val c = controller(gateway, online = null)

        c.open()
        advanceUntilIdle()

        assertFalse(c.state.value.offline)
        assertEquals(SharingPhase.Ready, c.state.value.phase)
    }

    @Test
    fun `opening shows the owner and the people it is shared with`() = runTest {
        val gateway =
            FakeGateway(shares = mutableListOf(ShareDto(anna, "viewer")), directory = listOf(ben))
        val c = controller(gateway)

        c.open()
        advanceUntilIdle()

        assertEquals("Me", c.state.value.owner?.name)
        assertEquals(listOf("Anna"), c.state.value.shares.map { it.user.name })
        assertEquals(listOf("Ben"), c.people.state.value.people.map { it.name })
    }

    @Test
    fun `a new person is added as a viewer by default, as an editor when chosen`() = runTest {
        val gateway = FakeGateway(directory = listOf(ben, cleo))
        val c = controller(gateway)
        c.open()

        c.select(ben)
        c.shareSelected()
        c.setNewRole(ShareRole.Editor)
        c.select(cleo)
        c.shareSelected()
        advanceUntilIdle()

        assertTrue("add:12:viewer" in gateway.calls)
        assertTrue("add:13:editor" in gateway.calls)
        assertEquals(listOf("Ben", "Cleo"), c.state.value.shares.map { it.user.name })
    }

    @Test
    fun `changing a role and removing a person go through`() = runTest {
        val gateway =
            FakeGateway(shares = mutableListOf(ShareDto(anna, "viewer")), directory = listOf(anna))
        val c = controller(gateway)
        c.open()

        c.setRole(7, ShareRole.Editor)
        assertEquals("editor", c.state.value.shares.single().role)
        c.remove(7)

        assertTrue(c.state.value.shares.isEmpty())
        assertEquals(
            listOf("role:7:editor", "revoke:7"),
            gateway.calls.filter { !it.startsWith("people") && it != "shares" },
        )
    }

    @Test
    fun `a refused change shows the error and reloads the real shares`() = runTest {
        val gateway =
            FakeGateway(shares = mutableListOf(ShareDto(anna, "viewer")), directory = listOf(anna))
        val c = controller(gateway)
        c.open()
        gateway.failNext = SharingError.Forbidden

        c.setRole(7, ShareRole.Editor)

        assertEquals(SharingError.Forbidden, c.state.value.error)
        assertEquals(2, gateway.calls.count { it == "shares" })
        assertEquals("viewer", c.state.value.shares.single().role)
        assertTrue(c.state.value.busy.isEmpty())
    }

    @Test
    fun `losing the connection mid-call switches the sheet to offline`() = runTest {
        val gateway = FakeGateway(directory = listOf(ben))
        val c = controller(gateway)
        c.open()
        gateway.failNext = SharingError.Offline

        c.select(ben)
        c.shareSelected()

        assertTrue(c.state.value.offline)
    }

    @Test
    fun `leaving calls back only after the server confirmed`() = runTest {
        var left = 0
        val gateway = FakeGateway()
        val c = controller(gateway, onLeft = { left++ })
        gateway.failNext = SharingError.NotFound

        c.leave()
        assertEquals(0, left)
        assertEquals(SharingError.NotFound, c.state.value.error)

        c.leave()
        assertEquals(1, left)
    }

    @Test
    fun `transfer passes the chosen move flag on and reports the new owner`() = runTest {
        val project = FakeGateway(directory = listOf(ben))
        val projectController = controller(project)
        projectController.openTransfer()
        projectController.selectNewOwner(ben)
        projectController.setMoveLinkedYarns(true)
        projectController.confirmTransfer()
        assertEquals(true, project.transferMove)
        assertTrue(projectController.transfer.value.done)
        assertEquals("Ben", projectController.transferred.value?.name)
    }

    @Test
    fun `the move flag defaults to off and a transfer needs a chosen person`() = runTest {
        val gateway = FakeGateway(directory = listOf(ben))
        val c = controller(gateway)
        c.openTransfer()

        c.confirmTransfer()
        assertTrue("nobody chosen, nothing sent", gateway.calls.none { it.startsWith("transfer") })
        assertFalse(c.transfer.value.moveLinkedYarns)

        c.selectNewOwner(ben)
        c.confirmTransfer()
        assertEquals(false, gateway.transferMove)
    }

    @Test
    fun `a failed transfer keeps the dialog open with the error`() = runTest {
        val gateway = FakeGateway(directory = listOf(ben))
        val c = controller(gateway)
        c.openTransfer()
        c.selectNewOwner(ben)
        gateway.failNext = SharingError.Conflict

        c.confirmTransfer()

        assertEquals(SharingError.Conflict, c.transfer.value.error)
        assertFalse(c.transfer.value.done)
        assertFalse(c.transfer.value.submitting)
        assertNull(c.transferred.value)
    }

    @Test
    fun `picking a person collapses the picker to them, Change brings the list back`() = runTest {
        val gateway = FakeGateway(directory = listOf(ben, cleo))
        val c = controller(gateway)
        c.open()

        c.select(ben)
        assertEquals(ben, c.state.value.selected)
        assertTrue(
            "nothing is shared before Share is pressed",
            gateway.calls.none { it.startsWith("add") },
        )

        c.select(null)
        assertNull(c.state.value.selected)

        c.select(ben)
        c.shareSelected()
        assertNull("the picker resets after sharing", c.state.value.selected)
        assertEquals(listOf("Ben"), c.state.value.shares.map { it.user.name })
    }

    @Test
    fun `transfer collapses to the chosen person and can be changed`() = runTest {
        val gateway = FakeGateway(directory = listOf(ben, cleo))
        val c = controller(gateway)
        c.openTransfer()

        c.selectNewOwner(ben)
        assertEquals(ben, c.transfer.value.selected)
        c.selectNewOwner(null)
        assertNull(c.transfer.value.selected)
        c.selectNewOwner(cleo)
        c.confirmTransfer()
        assertTrue("transfer:13" in gateway.calls)
    }

    @Test
    fun `typing shows matches only, an older answer arriving late never replaces them`() = runTest {
        val gateway = FakeGateway(directory = listOf(anna, ben, cleo))
        gateway.gates["b"] = kotlinx.coroutines.CompletableDeferred()
        val c = controller(gateway)

        c.people.setQuery("b")
        advanceTimeBy(PEOPLE_SEARCH_DEBOUNCE_MILLIS + 1) // "b" is in flight, held back
        c.people.setQuery("be")
        advanceUntilIdle()
        // The answer for "b" now arrives, after the newer one.
        gateway.gates.getValue("b").complete(Unit)
        advanceUntilIdle()

        assertEquals("be", c.people.state.value.resultsFor)
        assertEquals(listOf("Ben"), c.people.state.value.visiblePeople.map { it.name })
    }

    @Test
    fun `while the new query is loading the old list is not shown`() = runTest {
        val gateway = FakeGateway(directory = listOf(anna, ben, cleo))
        val c = controller(gateway)
        c.people.start()
        advanceUntilIdle()
        assertEquals(3, c.people.state.value.visiblePeople.size)

        gateway.gates["x"] = kotlinx.coroutines.CompletableDeferred()
        c.people.setQuery("x")

        assertTrue(c.people.state.value.visiblePeople.isEmpty())
    }

    @Test
    fun `people search is debounced and keeps the latest query`() = runTest {
        val gateway = FakeGateway(directory = listOf(anna, ben, cleo))
        val c = controller(gateway)

        c.people.setQuery("b")
        c.people.setQuery("be")
        advanceUntilIdle()

        assertEquals(listOf("people:be"), gateway.calls.filter { it.startsWith("people") })
        assertEquals(listOf("Ben"), c.people.state.value.people.map { it.name })
    }
}
