package blue.anika.wolle.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import blue.anika.wolle.data.account.AccountDto
import blue.anika.wolle.data.account.AccountV2Api
import blue.anika.wolle.data.account.ApiTokenDto
import blue.anika.wolle.data.account.ChangePasswordRequest
import blue.anika.wolle.data.account.CreateTokenRequest
import blue.anika.wolle.data.account.DetailCardOrderDto
import blue.anika.wolle.data.account.RenameTokenRequest
import blue.anika.wolle.data.account.RevokedTokenDto
import blue.anika.wolle.data.account.UpdateAccountRequest
import blue.anika.wolle.data.api.v2.V2Envelope
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MultipartBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

class DetailCardOrderSyncTest {

    @get:Rule val folder = TemporaryFolder()

    private val project = DetailCardDomain.PROJECT
    private val yarn = DetailCardDomain.YARN
    private val custom = listOf("steps", "details", "materials", "description")

    @Test
    fun `the server wins unless a local change is still pending`() {
        val fromServer = DetailCardOrderSync.reconcile(project, null, false, custom)
        assertEquals(DetailCardOrderReconciled(custom, pending = false), fromServer)

        val keptLocal = DetailCardOrderSync.reconcile(project, custom, true, null)
        assertEquals(DetailCardOrderReconciled(custom, pending = true), keptLocal)

        val pendingWins =
            DetailCardOrderSync.reconcile(
                project,
                custom,
                true,
                DetailCardOrder.defaults(project),
            )
        assertEquals(DetailCardOrderReconciled(custom, pending = true), pendingWins)
    }

    @Test
    fun `a default or missing server order clears the local one`() {
        val reset =
            DetailCardOrderSync.reconcile(project, custom, false, DetailCardOrder.defaults(project))
        assertEquals(DetailCardOrderReconciled(null, pending = false), reset)

        val offlineServer = DetailCardOrderSync.reconcile(yarn, listOf("notes"), false, null)
        assertEquals(DetailCardOrderReconciled(listOf("notes"), pending = false), offlineServer)
    }

    @Test
    fun `a server order is sanitized`() {
        val merged =
            DetailCardOrderSync.reconcile(yarn, null, false, listOf("notes", "bogus", "notes"))
        assertEquals(
            listOf(
                YarnDetailCard.NOTES,
                YarnDetailCard.DETAILS,
                YarnDetailCard.DESCRIPTION,
                YarnDetailCard.USED_IN,
            ),
            merged.local,
        )
    }

    @Test
    fun `the patch names only pending pages and resets with an empty list`() {
        val onlyYarn = DetailCardOrderSync.patch(setOf(yarn), custom, listOf("notes"))
        assertEquals(DetailCardOrderDto(project = null, yarn = listOf("notes")), onlyYarn)

        val reset = DetailCardOrderSync.patch(setOf(project), null, null)
        assertEquals(DetailCardOrderDto(project = emptyList(), yarn = null), reset)
    }

    @Test
    fun `the wire format omits untouched pages and parses the account payload`() {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        assertEquals(
            """{"detail_card_order":{"yarn":["notes"]}}""",
            json.encodeToString(
                UpdateAccountRequest(detailCardOrder = DetailCardOrderDto(yarn = listOf("notes")))
            ),
        )
        val account =
            json.decodeFromString<AccountDto>(
                """{"id":1,"email":"a@b.c","detail_card_order":
                   {"project":["steps","details"],"yarn":["details"]}}"""
            )
        assertEquals(listOf("steps", "details"), account.detailCardOrder?.project)
        assertNull(
            json.decodeFromString<AccountDto>("""{"id":1,"email":"a@b.c"}""").detailCardOrder
        )
    }

    private class FakeApi : AccountV2Api {
        var server = DetailCardOrderDto(project = emptyList(), yarn = emptyList())
        var offline = false
        val updates = mutableListOf<DetailCardOrderDto>()

        private fun dto() = AccountDto(id = 1, email = "a@b.c", detailCardOrder = server)

        override suspend fun account(): V2Envelope<AccountDto> {
            if (offline) throw IOException("offline")
            return V2Envelope(dto())
        }

        override suspend fun updateAccount(request: UpdateAccountRequest): V2Envelope<AccountDto> {
            if (offline) throw IOException("offline")
            val patch = requireNotNull(request.detailCardOrder)
            updates += patch
            server =
                DetailCardOrderDto(
                    project = patch.project?.let { normalized(project, it) } ?: server.project,
                    yarn = patch.yarn?.let { normalized(DetailCardDomain.YARN, it) } ?: server.yarn,
                )
            return V2Envelope(dto())
        }

        private fun normalized(domain: DetailCardDomain, order: List<String>) =
            DetailCardOrder.sanitize(domain, order.takeIf { it.isNotEmpty() })

        private val project = DetailCardDomain.PROJECT

        override suspend fun tokens() = error("unused")

        override suspend fun createToken(request: CreateTokenRequest) = error("unused")

        override suspend fun revokeToken(id: Long): Response<V2Envelope<RevokedTokenDto>> =
            error("unused")

        override suspend fun renameCurrentToken(
            request: RenameTokenRequest
        ): V2Envelope<ApiTokenDto> = error("unused")

        override suspend fun changePassword(request: ChangePasswordRequest) = error("unused")

        override suspend fun uploadAvatar(file: MultipartBody.Part) = error("unused")

        override suspend fun deleteAvatar() = error("unused")
    }

    private fun TestScope.store(): androidx.datastore.core.DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(folder.root, "prefs.preferences_pb")
        }

    @Test
    fun `a change made offline is kept, then pushed once the server answers`() = runTest {
        val api = FakeApi().apply { offline = true }
        val repository = DetailCardOrderRepository(store(), api)

        repository.setOrder(project, custom)
        repository.sync()
        assertEquals(custom, repository.projectOrder.first())

        api.offline = false
        repository.sync()
        assertEquals(custom, api.server.project)
        assertEquals(1, api.updates.size)
        assertEquals(custom, repository.projectOrder.first())

        // No longer pending: a later change on the web replaces the cache.
        api.server = api.server.copy(project = emptyList())
        repository.sync()
        assertEquals(DetailCardOrder.defaults(project), repository.projectOrder.first())
    }

    @Test
    fun `an online change reaches the server at once and a web change arrives on sync`() = runTest {
        val api = FakeApi()
        val repository = DetailCardOrderRepository(store(), api)

        repository.setOrder(yarn, listOf("notes", "details", "description", "used_in"))
        assertEquals(listOf("notes", "details", "description", "used_in"), api.server.yarn)

        api.server = api.server.copy(yarn = listOf("used_in", "details", "description", "notes"))
        repository.sync()
        assertEquals(
            listOf("used_in", "details", "description", "notes"),
            repository.yarnOrder.first(),
        )

        repository.reset(yarn)
        assertEquals(DetailCardOrder.defaults(yarn), api.server.yarn)
        assertEquals(DetailCardOrder.defaults(yarn), repository.yarnOrder.first())
    }

    @Test
    fun `an order saved before the first sync is sent instead of overwritten`() = runTest {
        val dataStore = store()
        dataStore.edit {
            it[stringPreferencesKey("detail_card_order_project")] = custom.joinToString(",")
        }
        val api = FakeApi()
        val repository = DetailCardOrderRepository(dataStore, api)

        repository.sync()

        assertEquals(custom, api.server.project)
        assertEquals(custom, repository.projectOrder.first())
        assertEquals(DetailCardOrder.defaults(yarn), repository.yarnOrder.first())
    }
}
