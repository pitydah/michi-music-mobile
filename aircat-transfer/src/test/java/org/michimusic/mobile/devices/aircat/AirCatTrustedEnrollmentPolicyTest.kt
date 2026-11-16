package org.michimusic.mobile.devices.aircat

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.digests.Blake3Digest
import java.security.SecureRandom
import java.util.Base64

class AirCatTrustedEnrollmentPolicyTest {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val pub = Ed25519PrivateKeyParameters(SecureRandom()).generatePublicKey().encoded
    private val digest = Blake3Digest(256).also { it.update(pub, 0, pub.size) }
    private val id = enc.encodeToString(ByteArray(32).also { digest.doFinal(it, 0) })
    private val source = enc.encodeToString(ByteArray(32) { 6 })
    private val now = 1_800_000_000_000L
    private val pin = "sha256/" + Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val qr = AirCatTrustedEnrollmentQr(
        type = "michi.aircat.home.enrollment.v1", homeId = id,
        authorityMichiId = id, authorityPublicKeyBase64Url = enc.encodeToString(pub),
        sourceMichiId = source, pinnedBaseUrl = "https://192.168.1.50:9443",
        tlsSpkiSha256 = pin,
        nonceBase64Url = enc.encodeToString(ByteArray(24) { 7 }),
        issuedAtMillis = now - 10_000, expiresAtMillis = now + 120_000,
    )
    private fun accepted(v: AirCatTrustedEnrollmentQr, at: Long=now) =
        AirCatTrustedEnrollmentPolicy.validate(v,at) != null

    @Test fun validOutOfBandHomeQr() { assertTrue(accepted(qr)) }
    @Test fun wrongHomeIdentityFails() { assertFalse(accepted(qr.copy(homeId="H".repeat(43)))) }
    @Test fun wrongAuthorityIdentityFails() { assertFalse(accepted(qr.copy(authorityMichiId="A".repeat(43)))) }
    @Test fun wrongRootKeyFails() { assertFalse(accepted(qr.copy(authorityPublicKeyBase64Url=enc.encodeToString(ByteArray(32))))) }
    @Test fun wrongSourceIdFails() { assertFalse(accepted(qr.copy(sourceMichiId="alias"))) }
    @Test fun wrongProtocolFails() { assertFalse(accepted(qr.copy(type="legacy-peer"))) }
    @Test fun noHttpQr() { assertFalse(accepted(qr.copy(pinnedBaseUrl="http://192.168.1.50"))) }
    @Test fun noEmbeddedCredential() { assertFalse(accepted(qr.copy(pinnedBaseUrl="https://user:pass@192.168.1.50"))) }
    @Test fun noUnpinnedServer() { assertFalse(accepted(qr.copy(tlsSpkiSha256=""))) }
    @Test fun noChangedPin() { assertFalse(accepted(qr.copy(tlsSpkiSha256="sha256/invalid"))) }
    @Test fun noQrWithPath() { assertFalse(accepted(qr.copy(pinnedBaseUrl="https://192.168.1.50/admin"))) }
    @Test fun noExpiredQr() { assertFalse(accepted(qr,now+300_000)) }
    @Test fun noFutureDatedQr() { assertFalse(accepted(qr.copy(issuedAtMillis=now+100_000,expiresAtMillis=now+200_000))) }
    @Test fun noLongLivedQr() { assertFalse(accepted(qr.copy(expiresAtMillis=now+800_000))) }
    @Test fun noShortNonce() { assertFalse(accepted(qr.copy(nonceBase64Url=enc.encodeToString(ByteArray(6))))) }
    @Test fun noMalformedNonce() { assertFalse(accepted(qr.copy(nonceBase64Url="%%%"))) }
}
