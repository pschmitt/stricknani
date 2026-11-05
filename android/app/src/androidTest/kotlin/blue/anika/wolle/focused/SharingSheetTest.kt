package blue.anika.wolle.focused

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import blue.anika.wolle.data.api.dto.PeoplePageDto
import blue.anika.wolle.data.api.dto.PersonDto
import blue.anika.wolle.data.api.dto.ShareDto
import blue.anika.wolle.data.api.dto.ShareListDto
import blue.anika.wolle.data.api.dto.TransferResultDto
import blue.anika.wolle.data.api.dto.YarnCandidatesPageDto
import blue.anika.wolle.data.repository.ItemAccess
import blue.anika.wolle.data.repository.ShareRole
import blue.anika.wolle.data.repository.SharedKind
import blue.anika.wolle.data.repository.SharingGateway
import blue.anika.wolle.ui.common.ItemBanner
import blue.anika.wolle.ui.common.ItemBannerView
import blue.anika.wolle.ui.common.OwnedKind
import blue.anika.wolle.ui.common.OwnerBadgeData
import blue.anika.wolle.ui.common.itemBannerOf
import blue.anika.wolle.ui.sharing.SharingController
import blue.anika.wolle.ui.sharing.SharingSheet
import blue.anika.wolle.ui.theme.StricknaniTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Share sheet as the owner sees it, and the banner a recipient sees; no server needed. */
@RunWith(AndroidJUnit4::class)
class SharingSheetTest {
    @get:Rule val composeRule = createComposeRule()

    private val anna = PersonDto(7, "Anna")
    private val ben = PersonDto(12, "Ben")

    private class FakeGateway(val shares: List<ShareDto>, val directory: List<PersonDto>) :
        SharingGateway {
        override suspend fun shares(kind: SharedKind, id: Int) =
            ShareListDto(PersonDto(1, "Me"), shares)

        override suspend fun add(kind: SharedKind, id: Int, userId: Long, role: ShareRole) =
            ShareDto(directory.first { it.id == userId }, role.wire)

        override suspend fun changeRole(kind: SharedKind, id: Int, userId: Long, role: ShareRole) =
            ShareDto(directory.first { it.id == userId }, role.wire)

        override suspend fun revoke(kind: SharedKind, id: Int, userId: Long) = Unit

        override suspend fun leave(kind: SharedKind, id: Int) = Unit

        override suspend fun transfer(
            kind: SharedKind,
            id: Int,
            newOwnerId: Long,
            moveLinkedYarns: Boolean,
        ) =
            TransferResultDto(
                directory.first { it.id == newOwnerId },
                blue.anika.wolle.data.api.dto.AccessDto("editor", false, true, false),
            )

        override suspend fun people(query: String?, page: Int) = PeoplePageDto(items = directory)

        override suspend fun yarnCandidates(projectId: Int, query: String?, page: Int) =
            YarnCandidatesPageDto()
    }

    private fun controller(online: Boolean) =
        SharingController(
            scope = CoroutineScope(Dispatchers.Main),
            kind = SharedKind.Project,
            id = 4,
            repository = FakeGateway(listOf(ShareDto(anna, "viewer")), listOf(anna, ben)),
            hasConnection = { online },
        )

    @Test
    fun owner_sees_owner_people_roles_picker_note_and_a_quiet_transfer() {
        val controller = controller(online = true)
        composeRule.setContent {
            StricknaniTheme {
                SharingSheet(
                    controller = controller,
                    isProject = true,
                    linkedYarnCount = 2,
                    onDismiss = {},
                    onTransfer = {},
                )
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("share-7").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Owner").assertIsDisplayed()
        composeRule.onNodeWithText("Can view").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "People you share this with can also see its 2 linked yarns, read-only."
            )
            .assertIsDisplayed()
        composeRule.onNodeWithText("Transfer ownership").assertIsDisplayed()
        // The person already shared with is not offered again; Ben is.
        composeRule.onNodeWithText("Ben").performClick()
        composeRule.onNodeWithTag("picked-person").assertIsDisplayed()
        composeRule.onNodeWithTag("share-picked").assertIsDisplayed()
        composeRule.onNodeWithText("Change").performClick()
        composeRule.onNodeWithTag("picked-person").assertDoesNotExist()
    }

    @Test
    fun offline_the_sheet_explains_and_disables_sharing() {
        val controller = controller(online = false)
        composeRule.setContent {
            StricknaniTheme {
                SharingSheet(
                    controller = controller,
                    isProject = false,
                    linkedYarnCount = 0,
                    onDismiss = {},
                    onTransfer = {},
                )
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("sharing-offline").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("sharing-offline").assertIsDisplayed()
        composeRule.onNodeWithTag("transfer-action").assertIsNotEnabled()
    }

    @Test
    fun recipient_sees_who_shared_it_and_their_role() {
        val anna = OwnerBadgeData(7, "Anna", null)
        val banner =
            itemBannerOf(
                anna,
                ItemAccess(role = "viewer", canEdit = false, canManage = false),
                OwnedKind.Project,
            )
                as ItemBanner

        composeRule.setContent { StricknaniTheme { ItemBannerView(banner) } }

        composeRule.onNodeWithText("Shared by Anna · Can view").assertIsDisplayed()
    }

    @Test
    fun derived_yarn_says_it_is_visible_through_a_shared_project() {
        composeRule.setContent { StricknaniTheme { ItemBannerView(ItemBanner.Derived) } }

        composeRule.onNodeWithText("Visible through a shared project").assertIsDisplayed()
    }
}
