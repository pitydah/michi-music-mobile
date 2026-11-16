package org.michimusic.mobile.devices.aircat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AirCatUserFacingFailurePolicyTest {
    @Test fun rawProtocolErrorCannotExposeCredentials() {
        val msg = "https://host.test/file?token=secret /private/storage/file.flac"
        val receipt = AirCatUserFacingFailurePolicy.describe(AirCatException.ProtocolError(msg))
        assertEquals("AIRC_TRANSFER_UNAVAILABLE", receipt.code)
        assertFalse(receipt.message.contains("secret"))
        assertFalse(receipt.message.contains("/private/"))
    }

    @Test fun revokedIsDistinguishedFromExpired() {
        val revoked = AirCatUserFacingFailurePolicy.describe(AirCatException.RevokedPeer("secret-id"))
        val expired = AirCatUserFacingFailurePolicy.describe(AirCatException.TransferExpired(2L, 3L))
        assertEquals("AIRC_HOME_REVOKED", revoked.code)
        assertEquals("AIRC_GRANT_EXPIRED", expired.code)
        assertFalse(revoked.message.contains("secret-id"))
    }
    @Test fun rawProgressErrorsAreAlwaysSafe() {
        val raw = "AIRC_HASH_MISMATCH: https://private.invalid/x?token=secret /sdcard/secret.flac"
        val projected = AirCatUserFacingFailurePolicy.safeMessage(raw)!!
        assertEquals("La integridad del archivo no coincide", projected)
        assertFalse(projected.contains("secret"))
        assertFalse(projected.contains("https://"))
        assertEquals("La transferencia no pudo continuar",
            AirCatUserFacingFailurePolicy.safeMessage("IllegalStateException /storage/private"))
        assertEquals(null, AirCatUserFacingFailurePolicy.safeMessage(null))
    }

    @Test fun remoteTrackTitleIsBoundedAndHasNoBidiOrControls() {
        val dirty = "Audio\u202Eexe\u2069\n" + "A".repeat(10000)
        val safe = AirCatUserFacingFailurePolicy.safeTitle(dirty)
        assertFalse(safe.contains("\u202E"))
        assertFalse(safe.contains("\u2069"))
        assertFalse(safe.contains("\n"))
        assertEquals(true, safe.codePointCount(0, safe.length) <= 96)
    }

}
