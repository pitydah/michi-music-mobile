package org.michimusic.link
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
class LinkAuthStrategyGateTest {
    @Test fun homeNeverDowngradesToLegacyOrReceiverButton() = assertEquals(
        "LINK_HOME_AUTH_NOT_IMPLEMENTED: vinculación Home requiere autorización de Micro Server",
        LinkAuthStrategyGate.refusal("HOME_MEMBERSHIP"),
    )
    @Test fun unknownStrategyIsNotLegacy() = assertEquals(
        "LINK_AUTH_STRATEGY_UNSUPPORTED: perfil de autenticación no reconocido",
        LinkAuthStrategyGate.refusal("ARBITRARY_PROFILE"),
    )
    @Test fun canonicalLegacyProfilesRemainAvailable() {
        for (profile in listOf("PLAYER_PASSWORD", "SERVER_CODE", "ED25519_CHALLENGE", "RECEIVER_BUTTON", "LEGACY"))
            assertNull(LinkAuthStrategyGate.refusal(profile))
    }
}
