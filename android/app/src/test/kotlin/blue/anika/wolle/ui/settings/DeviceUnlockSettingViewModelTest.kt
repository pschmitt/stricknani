package blue.anika.wolle.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import blue.anika.wolle.R
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.ui.common.DeviceLockState
import blue.anika.wolle.ui.common.FakeDangerousActionGuard
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceUnlockSettingViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val guard = FakeDangerousActionGuard()
    private var lock = DeviceLockState.Available

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.preferences() =
        AppPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) {
                File(tmp.root, "prefs.preferences_pb")
            },
            Json,
        )

    private fun TestScope.viewModel(prefs: AppPreferencesRepository) =
        DeviceUnlockSettingViewModel(
            prefs,
            guard,
            object : DeviceLockProbe {
                override fun lockState() = lock
            },
        )

    @Test
    fun `the setting is on by default`() = runTest {
        assertTrue(preferences().deviceUnlockEnabled.first())
    }

    @Test
    fun `turning it off asks for the unlock and does nothing when refused`() = runTest {
        val prefs = preferences()
        val vm = viewModel(prefs)
        guard.unlocked = false

        vm.setEnabled(false)

        assertEquals(listOf(R.string.unlock_reason_turn_off), guard.requests)
        assertTrue(prefs.deviceUnlockEnabled.first())
    }

    @Test
    fun `turning it off after the unlock is stored and turning it on needs none`() = runTest {
        val prefs = preferences()
        val vm = viewModel(prefs)

        vm.setEnabled(false)
        assertFalse(prefs.deviceUnlockEnabled.first { !it })

        guard.requests.clear()
        vm.setEnabled(true)
        assertTrue(prefs.deviceUnlockEnabled.first { it })
        assertTrue(guard.requests.isEmpty())
    }

    @Test
    fun `the hint shows only without a screen lock`() = runTest {
        val vm = viewModel(preferences())
        assertFalse(vm.needsScreenLock())
        lock = DeviceLockState.NotSecured
        assertTrue(vm.needsScreenLock())
    }
}
