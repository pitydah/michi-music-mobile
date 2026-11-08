package org.michimusic.mobile.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.core.models.SyncConnectionState

class AeroLinkMotionTest {
    @Test fun connectionIsVerifiedOnlyForRealConnectedState() {
        assertEquals(AeroLinkEvidenceLevel.VERIFIED,
            aeroLinkEvidenceLevel(SyncConnectionState.CONNECTED))
        assertEquals(AeroLinkEvidenceLevel.NEUTRAL,
            aeroLinkEvidenceLevel(SyncConnectionState.PAIRED))
        assertEquals(AeroLinkEvidenceLevel.PENDING,
            aeroLinkEvidenceLevel(SyncConnectionState.PAIRING_REQUIRED))
        assertEquals(AeroLinkEvidenceLevel.WARNING,
            aeroLinkEvidenceLevel(SyncConnectionState.UNAUTHORIZED))
    }

    @Test fun discoveryNeverClaimsConnectedAndStreamAdvertisementNeverClaimsSession() {
        assertEquals("Buscando dispositivos Michi...",
            aeroLinkHeadline(SyncConnectionState.DISCOVERING, true))
        assertEquals("Identidad vinculada",
            aeroLinkHeadline(SyncConnectionState.PAIRED, false))
        assertEquals("Michi Music Stream · dispositivo anunciado", aeroStreamPeerEvidenceLabel(true))
        assertNull(aeroStreamPeerEvidenceLabel(false))
    }
}
