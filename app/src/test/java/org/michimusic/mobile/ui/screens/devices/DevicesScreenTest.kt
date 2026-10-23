package org.michimusic.mobile.ui.screens.devices

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.sync.DeviceAction
import org.michimusic.mobile.sync.DeviceActionType
import org.michimusic.mobile.ui.screens.DeviceNodeCard
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DevicesScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun deviceNodeCard_verifiedDevice_displaysVerifiedBadgeAndNoRawIpInSubtitle() {
        val verifiedItem = DeviceUiItem(
            id = "server-01",
            name = "Michi Main Server",
            ip = "192.168.1.150",
            port = 7331,
            deviceType = "server",
            discoveryStatus = DeviceDiscoveryStatus.DISCOVERED,
            trustStatus = DeviceTrustStatus.TRUSTED,
            connectionStatus = DeviceConnectionStatus.CONNECTED,
            capabilities = setOf("Biblioteca", "Sincronización"),
            primaryAction = DeviceAction(
                type = DeviceActionType.BROWSE_LIBRARY,
                label = "Explorar",
                isPrimary = true,
            ),
            secondaryActions = emptyList(),
        )

        composeTestRule.setContent {
            MichiTheme {
                DeviceNodeCard(
                    item = verifiedItem,
                    onInfoClick = {},
                    onActionClick = {},
                )
            }
        }

        // Trust badge must be displayed
        composeTestRule.onNodeWithText("● Verificado").assertIsDisplayed()
        // Connection status must be displayed
        composeTestRule.onNodeWithText("Conectado").assertIsDisplayed()
        // Subtitle must contain capabilities and formatted type, not raw IP
        composeTestRule.onNodeWithText("Micro Server · Biblioteca · Sincronización").assertIsDisplayed()
        // Primary action displayed
        composeTestRule.onNodeWithText("Explorar").assertIsDisplayed()

        // Info button must have at least 48dp touch target
        composeTestRule.onNodeWithTag("device_info_192.168.1.150")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun deviceNodeCard_announcedUntrustedDevice_displaysAnnouncedBadge() {
        val announcedItem = DeviceUiItem(
            id = "node-02",
            name = "Nuevo Dispositivo",
            ip = "192.168.1.175",
            port = 7331,
            deviceType = "phone",
            discoveryStatus = DeviceDiscoveryStatus.DISCOVERED,
            trustStatus = DeviceTrustStatus.UNKNOWN, // maps to "● Anunciado"
            connectionStatus = DeviceConnectionStatus.OFFLINE,
            capabilities = emptySet(),
            primaryAction = DeviceAction(
                type = DeviceActionType.CONNECT,
                label = "Conectar",
                isPrimary = true,
            ),
            secondaryActions = emptyList(),
        )

        composeTestRule.setContent {
            MichiTheme {
                DeviceNodeCard(
                    item = announcedItem,
                    onInfoClick = {},
                    onActionClick = {},
                )
            }
        }

        // Announced badge must be displayed
        composeTestRule.onNodeWithText("● Anunciado").assertIsDisplayed()
        // Connection status must be displayed
        composeTestRule.onNodeWithText("Fuera de línea").assertIsDisplayed()
        // Subtitle must indicate local network fallback without raw IP
        composeTestRule.onNodeWithText("Teléfono · Red local").assertIsDisplayed()
        // Primary action displayed
        composeTestRule.onNodeWithText("Conectar").assertIsDisplayed()
    }
}
