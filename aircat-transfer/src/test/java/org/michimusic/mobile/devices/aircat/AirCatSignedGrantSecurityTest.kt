package org.michimusic.mobile.devices.aircat

import org.junit.Assert.assertTrue
import org.junit.Test
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.digests.Blake3Digest
import java.security.SecureRandom
import java.util.Base64

class AirCatSignedGrantSecurityTest {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val priv = Ed25519PrivateKeyParameters(SecureRandom())
    private val pub = priv.generatePublicKey().encoded
    private val digest = Blake3Digest(256).also { it.update(pub, 0, pub.size) }
    private val hash = ByteArray(32).also { digest.doFinal(it, 0) }
    private val id = enc.encodeToString(hash)
    // HomeRootAuthority.home_id() is the identity derived from its root key.
    private val home get() = id
    private val sourceId = "S".repeat(43)
    private val destId = "D".repeat(43)
    private val now = 1_800_000_000_000L
    private val tlsPin = "sha256/" + Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
    private val anchor = AirCatHomeAnchor(home, id, enc.encodeToString(pub), sourceId, tlsPin,
        pinnedBaseUrl = "https://source.example")
    private val security = AirCatSignedGrantSecurity(object: AirCatHomeAuthorityProvider {
        override fun anchorFor(homeId: String) = if (homeId == home) anchor else null
        override fun anchorForSource(sourceMichiId: String) = if (sourceMichiId == sourceId) anchor else null
    })
    private val manifest = AirCatManifest(
        transferId = "transfer-01", sourceDeviceId = sourceId, totalTracks = 1, totalBytes = 6L,
        items = listOf(AirCatManifestItem("local-1", "remote-2", "song", "artist", "album", "flac", 6L, "a".repeat(64))),
    )
    private val challenge = enc.encodeToString(ByteArray(24) { 9 })
    private val intent = TransferIntent("transfer-01", sourceId, destId, "src-lib", "dst-lib",
        expiresAt = now + 300_000L, authorizationNonceBase64Url = challenge)
    private fun signed(g: AirCatSignedGrant): AirCatSignedGrant {
        val bytes = AirCatCanonicalWire.signedGrantBytes(g)
        val signer = Ed25519Signer().also { it.init(true, priv); it.update(bytes, 0, bytes.size) }
        return g.copy(signatureBase64Url = enc.encodeToString(signer.generateSignature()))
    }
    private val grant get() = signed(AirCatSignedGrant(
        version=1, homeId=home, authorityMichiId=id, transferId="transfer-01", sourceMichiId=sourceId,
        destinationMichiId=destId, sourceLibraryId="src-lib", destinationLibraryId="dst-lib",
        selectionScope="ALBUMS", scopes=listOf("content:read","content:transfer"),
        manifestSha256=AirCatCanonicalWire.manifestSha256(manifest)!!,
        issuedAtMillis=now - 10_000L, expiresAtMillis=now + 180_000L,
        nonceBase64Url=challenge, signatureBase64Url="",
    ))

    @Test fun authorizationRequestBindsWholeManifestAndNonce() {
        val request = AirCatGrantRequest.create(intent.copy(authorizationNonceBase64Url=""),manifest)
        assertTrue(request.intent == intent.copy(authorizationNonceBase64Url=""))
        assertTrue(request.manifestSha256 == AirCatCanonicalWire.manifestSha256(manifest))
        assertTrue(Base64.getUrlDecoder().decode(request.clientNonceBase64Url).size == 24)
    }
    @Test fun replayedGrantForDifferentAuthorizeChallengeFails() {
        val other = enc.encodeToString(ByteArray(24) { 1 })
        assertTrue(security.verify(intent.copy(authorizationNonceBase64Url=other), manifest, grant, now).isFailure)
    }
    @Test fun directCallerWithoutChallengeFails() {
        assertTrue(security.verify(intent.copy(authorizationNonceBase64Url=""), manifest, grant, now).isFailure)
    }
    @Test fun incorrectManifestTotalCannotBeCanonicalized() {
        assertTrue(AirCatCanonicalWire.manifestSha256(manifest.copy(totalBytes = 7L)) == null)
    }
    @Test fun duplicateLogicalTrackCannotBeCanonicalized() {
        val duplicate = manifest.copy(totalTracks=2,totalBytes=12L,items=listOf(manifest.items[0],manifest.items[0]))
        assertTrue(AirCatCanonicalWire.manifestSha256(duplicate) == null)
    }
    @Test fun manifestTotalOverflowCannotBeCanonicalized() {
        val f = manifest.items[0].copy(size=Long.MAX_VALUE)
        val duplicate = manifest.copy(totalTracks=2,totalBytes=Long.MAX_VALUE,items=listOf(f,f.copy(logicalTrackId="distinct")))
        assertTrue(AirCatCanonicalWire.manifestSha256(duplicate) == null)
    }
    @Test fun crossRepoRustGoldenManifestDigest() {
        val fixture = AirCatManifest(transferId="tx",sourceDeviceId="source",
            totalTracks=1,totalBytes=6L,items=listOf(AirCatManifestItem(
                "local","remote","song","artist","album","flac",6L,"a".repeat(64))))
        assertTrue(AirCatCanonicalWire.manifestSha256(fixture) ==
            "4e053cfccaeda739685245d3e225e0c4b1e0cff66a4878a6fd1693e0d22074ea")
    }
    @Test fun grantRequestNonceChangesEachTime() {
        assertTrue(AirCatGrantRequest.create(intent.copy(authorizationNonceBase64Url=""),manifest).clientNonceBase64Url !=
            AirCatGrantRequest.create(intent.copy(authorizationNonceBase64Url=""),manifest).clientNonceBase64Url)
    }
    @Test fun grantRequestRefusesEmbeddedGrant() {
        assertTrue(runCatching { AirCatGrantRequest.create(intent.copy(signedGrant=grant,authorizationNonceBase64Url=""),manifest) }.isFailure)
    }
    @Test fun emptyOptionalArtistAndAlbumAreCanonicalizable() {
        val withoutMetadata = manifest.copy(items=manifest.items.map { it.copy(artist="", album="") })
        assertTrue(AirCatCanonicalWire.manifestSha256(withoutMetadata)?.length == 64)
    }
    @Test fun missingRemoteFileIdCannotBeCanonicalized() {
        val broken = manifest.copy(items=manifest.items.map { it.copy(remoteTrackId="") })
        assertTrue(AirCatCanonicalWire.manifestSha256(broken) == null)
    }
    @Test fun grantRequestRefusesEmptyManifest() {
        assertTrue(runCatching { AirCatGrantRequest.create(intent.copy(authorizationNonceBase64Url=""),manifest.copy(items=emptyList(),totalTracks=0,totalBytes=0)) }.isFailure)
    }
    @Test fun authorityPreflightRequiresEnrolledSourceIndex() {
        val onlyHome = object: AirCatHomeAuthorityProvider {
            override fun anchorFor(homeId: String) = if(homeId == home) anchor else null
        }
        assertTrue(AirCatSignedGrantSecurity(onlyHome).enrolledTlsPin(sourceId,"https://source.example") == null)
    }
    @Test fun authorityPreflightRejectsPlainHttpEvenWhenIndexed() {
        val provider = object:AirCatHomeAuthorityProvider {
            override fun anchorFor(homeId: String) = if (homeId == home) anchor else null
            override fun anchorForSource(sourceMichiId: String) = if (sourceMichiId == sourceId) anchor else null
        }
        assertTrue(AirCatSignedGrantSecurity(provider).enrolledTlsPin(sourceId,"http://source.example") == null)
        assertTrue(AirCatSignedGrantSecurity(provider).enrolledTlsPin(sourceId,"https://source.example") == tlsPin)
    }

    @Test fun validGrantWithEnrolledAuthority() { assertTrue(security.verify(intent,manifest,grant,now).isSuccess) }
    @Test fun rootWithoutSourceEnrollmentCannotAuthorize() {
        val onlyHome = object: AirCatHomeAuthorityProvider {
            override fun anchorFor(homeId: String) = if(homeId == home) anchor else null
        }
        assertTrue(AirCatSignedGrantSecurity(onlyHome).verify(intent,manifest,grant,now).isFailure)
    }
    @Test fun sourceEnrollmentPinnedToDifferentUrlCannotConnect() {
        val indexed = anchor.copy(pinnedBaseUrl="https://source.example")
        val provider = object: AirCatHomeAuthorityProvider {
            override fun anchorFor(homeId: String) = indexed
            override fun anchorForSource(sourceMichiId: String) = indexed
        }
        val check = AirCatSignedGrantSecurity(provider)
        assertTrue(check.enrolledTlsPin(sourceId,"https://another.example") == null)
        assertTrue(check.enrolledTlsPin(sourceId,"https://source.example") == tlsPin)
    }
    @Test fun noGrantFailsClosed() { assertTrue(security.verify(intent,manifest,null,now).isFailure) }
    @Test fun unknownHomeFailsClosed() { assertTrue(AirCatSignedGrantSecurity(AirCatHomeAuthorityProvider.Unavailable).verify(intent,manifest,grant,now).isFailure) }
    @Test fun tamperedFileHashFails() { assertTrue(security.verify(intent,manifest.copy(items=manifest.items.map { it.copy(sha256="b".repeat(64)) }),grant,now).isFailure) }
    @Test fun tamperedRemoteTrackFails() { assertTrue(security.verify(intent,manifest.copy(items=manifest.items.map { it.copy(remoteTrackId="different") }),grant,now).isFailure) }
    @Test fun tamperedArtistMetadataFails() { assertTrue(security.verify(intent,manifest.copy(items=manifest.items.map { it.copy(artist="other") }),grant,now).isFailure) }
    @Test fun changedAudienceFails() { assertTrue(security.verify(intent.copy(destinationMichiId="attacker"),manifest,grant,now).isFailure) }
    @Test fun changedSignedDestinationFails() { assertTrue(security.verify(intent,manifest,grant.copy(destinationMichiId="attacker"),now).isFailure) }
    @Test fun missingScopeFails() { assertTrue(security.verify(intent,manifest,signed(grant.copy(scopes=listOf("content:read"))),now).isFailure) }
    @Test fun expiredGrantFails() { assertTrue(security.verify(intent,manifest,grant,now+200_000L).isFailure) }
    @Test fun longLivedGrantFails() { assertTrue(security.verify(intent,manifest,signed(grant.copy(expiresAtMillis=now+800_000L)),now).isFailure) }
    @Test fun staleNonceEncodingFails() { assertTrue(security.verify(intent,manifest,signed(grant.copy(nonceBase64Url="a")),now).isFailure) }
    @Test fun wrongAuthorityKeyFails() {
        val stranger = Ed25519PrivateKeyParameters(SecureRandom()).generatePublicKey().encoded
        val badSecurity = AirCatSignedGrantSecurity(object: AirCatHomeAuthorityProvider {
            override fun anchorFor(homeId: String) = anchor.copy(authorityPublicKeyBase64Url=enc.encodeToString(stranger))
            override fun anchorForSource(sourceMichiId: String) = anchor
        })
        assertTrue(badSecurity.verify(intent,manifest,grant,now).isFailure)
    }
    @Test fun wrongTlsPinFails() {
        val bad = AirCatSignedGrantSecurity(object: AirCatHomeAuthorityProvider {
            override fun anchorFor(homeId: String) = anchor.copy(tlsSpkiSha256="garbage")
            override fun anchorForSource(sourceMichiId: String) = anchor.copy(tlsSpkiSha256="garbage")
        })
        assertTrue(bad.verify(intent,manifest,grant,now).isFailure)
    }
    @Test fun mutatedSignatureFails() {
        val g=grant
        assertTrue(security.verify(intent,manifest,g.copy(signatureBase64Url="A".repeat(86)),now).isFailure)
    }
    @Test fun differentHomeIdFails() { assertTrue(security.verify(intent,manifest,grant.copy(homeId="another-home"),now).isFailure) }
    @Test fun reorderedFilesFail() {
        val more=manifest.copy(totalTracks=2,totalBytes=12L,items=listOf(manifest.items[0],manifest.items[0].copy(logicalTrackId="other",remoteTrackId="remote-other")))
        val g=signed(grant.copy(manifestSha256=AirCatCanonicalWire.manifestSha256(more)!!))
        assertTrue(security.verify(intent,more.copy(items=more.items.reversed()),g,now).isFailure)
    }
    @Test fun signedExtraScopeCannotGrantMoreThanHomeContract() {
        val elevated = signed(grant.copy(scopes=listOf("content:delete", "content:read", "content:transfer")))
        assertTrue(security.verify(intent, manifest, elevated, now).isFailure)
    }
    @Test fun callerCannotAskForExtraScopeEvenWithOrdinaryGrant() {
        val escalated = intent.copy(requiredScopes=listOf("content:read", "content:transfer", "content:delete"))
        assertTrue(security.verify(escalated, manifest, grant, now).isFailure)
    }
    @Test fun differentHomeIdCannotBeAcceptedEvenWhenIssuerSignsIt() {
        val forgedDomain = signed(grant.copy(homeId="H".repeat(43)))
        val differentHome = AirCatSignedGrantSecurity(object : AirCatHomeAuthorityProvider {
            private val alternate = anchor.copy(homeId="H".repeat(43))
            override fun anchorFor(homeId: String) = if (homeId == alternate.homeId) alternate else null
            override fun anchorForSource(sourceMichiId: String) = if (sourceMichiId == sourceId) alternate else null
        })
        assertTrue(differentHome.verify(intent, manifest, forgedDomain, now).isFailure)
    }
    @Test fun versionTwoManifestCannotBeAuthorizedByVersionOneGrant() {
        val newFormat = manifest.copy(manifestVersion=2)
        assertTrue(AirCatCanonicalWire.manifestSha256(newFormat) == null)
        assertTrue(security.verify(intent.copy(manifestVersion=2), newFormat, grant, now).isFailure)
    }
    @Test fun duplicatedRemoteTrackIdCannotBeCanonicalized() {
        val one = manifest.items[0]
        val duplicate = manifest.copy(totalTracks=2,totalBytes=12L,items=listOf(one,one.copy(logicalTrackId="different")))
        assertTrue(AirCatCanonicalWire.manifestSha256(duplicate) == null)
    }
    @Test fun enrolledSourceCannotUseUnpinnedAlternativeHost() {
        assertTrue(security.enrolledTlsPin(sourceId,"https://other.example") == null)
        assertTrue(security.enrolledTlsPin(sourceId,"https://source.example") == tlsPin)
    }
    @Test fun enrollmentWithEmptyBaseUrlIsNotNetworkReady() {
        val unenrolledEndpoint = anchor.copy(pinnedBaseUrl="")
        val store = object : AirCatHomeAuthorityProvider {
            override fun anchorFor(homeId: String) = unenrolledEndpoint
            override fun anchorForSource(sourceMichiId: String) = unenrolledEndpoint
        }
        assertTrue(AirCatSignedGrantSecurity(store).enrolledTlsPin(sourceId,"https://source.example") == null)
    }

    @Test fun producerAuthorizationCanonicalizationHasBoundedMemoryBudget() {
        // Each field is individually permitted (<=4096), but the canonical
        // batch exceeds the global 8 MiB cap and must be rejected in-flight.
        val many = (0 until 9000).map { idx -> manifest.items.first().copy(
            logicalTrackId = "logical-$idx", remoteTrackId = "remote-$idx",
            title = "X".repeat(1000),
        ) }
        val abusive = manifest.copy(totalTracks = many.size,
            totalBytes = 6L * many.size, items = many)
        assertTrue(AirCatCanonicalWire.manifestSha256(abusive) == null)
    }
    @Test fun homeSourcePreflightRejectsNonCanonicalAuthorityKey() {
        val padded = anchor.copy(authorityPublicKeyBase64Url = anchor.authorityPublicKeyBase64Url + "=")
        val badSecurity = AirCatSignedGrantSecurity(object : AirCatHomeAuthorityProvider {
            override fun anchorFor(homeId: String) = if (homeId == home) padded else null
            override fun anchorForSource(sourceMichiId: String) = if (sourceMichiId == sourceId) padded else null
        })
        assertTrue(!badSecurity.enrolledSourceReady(sourceId,"https://source.example"))
    }

}
