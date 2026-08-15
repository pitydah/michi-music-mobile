package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.sync.SyncViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SyncScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `shows searching state when discovering`() {
        val mockViewModel = mockk<SyncViewModel>(relaxed = true)
        val uiState = org.michimusic.mobile.sync.SyncUiState(
            state = org.michimusic.core.models.SyncConnectionState.DISCOVERING
        )
        every { mockViewModel.uiState } returns MutableStateFlow(uiState)

        composeTestRule.setContent {
            SyncScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("Buscando servidor Michi Desktop...").assertIsDisplayed()
    }

    @Test
    fun `shows discovered peers`() {
        val mockViewModel = mockk<SyncViewModel>(relaxed = true)
        val peer = org.michimusic.core.models.DiscoveredPeer(
            alias = "Mi PC",
            ip = "192.168.1.50",
            port = 7331,
            authRequired = false
        )
        val uiState = org.michimusic.mobile.sync.SyncUiState(
            state = org.michimusic.core.models.SyncConnectionState.DISCONNECTED,
            peers = listOf(peer)
        )
        every { mockViewModel.uiState } returns MutableStateFlow(uiState)

        composeTestRule.setContent {
            SyncScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("Mi PC").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("192.168.1.50:7331").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `allows manual connection`() {
        val mockViewModel = mockk<SyncViewModel>(relaxed = true)
        val uiState = org.michimusic.mobile.sync.SyncUiState()
        every { mockViewModel.uiState } returns MutableStateFlow(uiState)

        composeTestRule.setContent {
            SyncScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("Conexión Manual por IP").performScrollTo().performClick()
        
        composeTestRule.onNodeWithText("Dirección IP y Puerto").performTextInput("192.168.1.100")
        composeTestRule.onNodeWithText("Nombre del Dispositivo").performTextInput("Mi Server")
        composeTestRule.onNodeWithText("Conectar", useUnmergedTree = true).performClick()

        // Verificamos que se llamó connectManual
        io.mockk.verify { 
            mockViewModel.connectManual(name = "Mi Server", host = "192.168.1.100", any(), any())
        }
    }
}
