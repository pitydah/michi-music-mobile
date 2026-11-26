package org.michimusic.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.EmbeddedTagEvidence
import org.michimusic.data.cache.MetadataAppliedTransaction
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.MetadataLibraryDurableOverlay
import org.michimusic.data.repository.groupAlbumsFromTracks

/**
 * MF02: the durable overlay must preserve the complete ordered genre list from
 * source-owned structured evidence instead of inventing a singleton from the
 * scalar compatibility value.
 *
 * MF03: the overlay may only publish values that a real Metadata Editor
 * transaction committed for the exact owner, track and URI, and only while the
 * provider's announced content still belongs to that revision.
 */
class MetadataLibraryDurableOverlayTest {
    private val source = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
    private val trackId = "local_1"
    private val uri = "content://media/external_primary/audio/media/1"
    private val sha = "b".repeat(64)
    private val transactionId = "0f0e0d0c-0b0a-4908-8706-050403020100"
    private val appliedAt = 1_700_000_000_000L
    private val providerModifiedSeconds = appliedAt / 1_000L

    private fun track(
        id: String = trackId,
        title: String = "Provider title",
        genre: String = "Rock",
        genreValues: List<String> = emptyList(),
        albumId: String = "7",
        size: Long = 120L,
        duration: Long = 200_000L,
        filepath: String = uri,
        sourceId: String = "",
    ) = Track(
        id = id,
        title = title,
        artist = "Provider artist",
        album = "Provider album",
        albumId = albumId,
        duration = duration,
        size = size,
        format = "FLAC",
        coverId = "cover_rock",
        trackNumber = 1,
        year = 2019,
        filepath = filepath,
        sourceId = sourceId,
        genre = genre,
        genreValues = genreValues,
    )

    private fun durable(
        id: String = trackId,
        title: String = "Durable title",
        genre: String = "Jazz",
        album: String = "Durable album",
        albumArtist: String = "Durable album artist",
        revision: Long = 2L,
        size: Long = 120L,
        duration: Long = 200_000L,
        filepath: String = uri,
        sourceId: String = source,
        verifiedSha256: String = "",
        verifiedSize: Long = 0L,
        verifiedAt: Long = 0L,
        verifiedAlgorithm: String = "",
    ) = CachedTrack(
        id = id,
        title = title,
        artist = "Durable artist",
        album = album,
        albumArtist = albumArtist,
        duration = duration,
        size = size,
        format = "FLAC",
        coverId = "cover_jazz",
        trackNumber = 3,
        year = 2021,
        filepath = filepath,
        sourceId = sourceId,
        genre = genre,
        metadataRevision = revision,
        verifiedSha256 = verifiedSha256,
        verifiedSize = verifiedSize,
        verifiedAt = verifiedAt,
        verifiedAlgorithm = verifiedAlgorithm,
    )

    private fun marker(
        sourceId: String = source,
        trackId: String = this.trackId,
        uri: String = this.uri,
        originalRevision: Long = 1L,
        committedRevision: Long = 2L,
        candidateSha256: String = sha,
        candidateBytes: Long = 120L,
        appliedAt: Long = this.appliedAt,
        transactionId: String = this.transactionId,
    ) = MetadataAppliedTransaction(
        transactionId = transactionId,
        sourceId = sourceId,
        trackId = trackId,
        uri = uri,
        originalRevision = originalRevision,
        committedRevision = committedRevision,
        candidateSha256 = candidateSha256,
        candidateBytes = candidateBytes,
        appliedAt = appliedAt,
    )

    private fun evidence(
        sourceId: String = source,
        trackId: String = this.trackId,
        sizeBytes: Long = 120L,
        modifiedAt: Long = providerModifiedSeconds,
        parserVersion: Int = EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
        genre: String = "Jazz",
        values: List<String> = listOf("Jazz"),
    ) = EmbeddedTagEvidence(
        sourceId = sourceId,
        trackId = trackId,
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        parserVersion = parserVersion,
        genre = genre,
        genreValues = values.joinToString("\u0000"),
    )

    private fun observation(
        sourceId: String = source,
        trackId: String = this.trackId,
        uri: String = this.uri,
        sizeBytes: Long = 120L,
        modifiedAt: Long = providerModifiedSeconds,
        capability: MetadataLibraryDurableOverlay.ProviderCapability =
            MetadataLibraryDurableOverlay.ProviderCapability.MEDIASTORE,
    ) = MetadataLibraryDurableOverlay.ProviderObservation(
        sourceId = sourceId,
        trackId = trackId,
        uri = uri,
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        capability = capability,
    )

    private fun overlay(
        original: Track,
        durable: CachedTrack,
        publications: List<MetadataAppliedTransaction> = listOf(marker()),
        evidenceRows: List<EmbeddedTagEvidence> = emptyList(),
        observations: List<MetadataLibraryDurableOverlay.ProviderObservation> = listOf(observation()),
    ): Track = MetadataLibraryDurableOverlay.apply(
        listOf(original),
        listOf(durable),
        publications,
        evidenceRows,
        observations,
    ).single()

    private fun refusal(
        original: Track,
        durable: CachedTrack,
        publications: List<MetadataAppliedTransaction> = listOf(marker()),
        evidenceRows: List<EmbeddedTagEvidence> = emptyList(),
        observations: List<MetadataLibraryDurableOverlay.ProviderObservation> = listOf(observation()),
    ): MetadataLibraryDurableOverlay.Refusal {
        val decision = MetadataLibraryDurableOverlay.decide(
            original,
            durable,
            publications,
            evidenceRows.singleOrNull(),
            observations.singleOrNull(),
        )
        return (decision as MetadataLibraryDurableOverlay.Decision.KeepProvider).reason
    }

    private fun durableTrack(row: CachedTrack) = Track(
        id = row.id,
        title = row.title,
        artist = row.artist,
        album = row.album,
        albumArtist = row.albumArtist,
        duration = row.duration,
        size = row.size,
        format = row.format,
        coverId = row.coverId,
        trackNumber = row.trackNumber,
        year = row.year,
        filepath = row.filepath,
        sourceId = row.sourceId,
        genre = row.genre,
    )

    @Test
    fun safReopenAttachesTheWholeOrderedGenreListFromMatchingEvidence() {
        val row = durable(genre = "Rock; Jazz; Blues")
        val overlaid = MetadataLibraryDurableOverlay.attachDurableGenres(
            listOf(durableTrack(row)),
            listOf(row),
            listOf(evidence(genre = "Rock; Jazz; Blues", values = listOf("Rock", "Jazz", "Blues"))),
        ).single()

        assertEquals("Rock; Jazz; Blues", overlaid.genre)
        assertEquals(listOf("Rock", "Jazz", "Blues"), overlaid.genreValues)
    }

    @Test
    fun safReopenIgnoresStaleOrForeignEvidence() {
        val row = durable(genre = "Rock; Jazz")
        val stale = evidence(
            modifiedAt = providerModifiedSeconds - 5L,
            parserVersion = EmbeddedTagEvidence.CURRENT_PARSER_VERSION - 1,
            genre = "Rock; Jazz",
            values = listOf("Rock", "Jazz"),
        )
        val foreign = evidence(
            sourceId = "another-library",
            genre = "Rock; Jazz",
            values = listOf("Rock", "Jazz"),
        )

        val overlaid = MetadataLibraryDurableOverlay.attachDurableGenres(
            listOf(durableTrack(row)),
            listOf(row),
            listOf(stale, foreign),
        ).single()

        assertEquals("Rock; Jazz", overlaid.genre)
        assertEquals(emptyList<String>(), overlaid.genreValues)
    }

    @Test
    fun keepsRockJazzBluesInTagOrderAndScalarCompatibilityValue() {
        val durable = durable(genre = "Rock; Jazz; Blues")
        val evidence = evidence(genre = "Rock; Jazz; Blues", values = listOf("Rock", "Jazz", "Blues"))

        val overlaid = overlay(track(), durable, evidenceRows = listOf(evidence))

        assertEquals("Rock; Jazz; Blues", overlaid.genre)
        assertEquals(listOf("Rock", "Jazz", "Blues"), overlaid.genreValues)
    }

    @Test
    fun keepsMetalRockInTagOrder() {
        val durable = durable(genre = "Metal; Rock")
        val evidence = evidence(genre = "Metal; Rock", values = listOf("Metal", "Rock"))

        val overlaid = overlay(track(), durable, evidenceRows = listOf(evidence))

        assertEquals("Metal; Rock", overlaid.genre)
        assertEquals(listOf("Metal", "Rock"), overlaid.genreValues)
    }

    @Test
    fun removingOneGenreUsesTheNewOrderedEvidence() {
        val durable = durable(genre = "Metal; Rock")
        val evidence = evidence(genre = "Metal; Rock", values = listOf("Metal", "Rock"))

        val overlaid = overlay(
            track(genre = "Rock; Jazz"),
            durable,
            evidenceRows = listOf(evidence),
        )

        assertEquals("Metal; Rock", overlaid.genre)
        assertEquals(listOf("Metal", "Rock"), overlaid.genreValues)
    }

    @Test
    fun removingAllGenresIsAConfirmedAbsenceNotAnInventedValue() {
        val durable = durable(genre = "")
        val evidence = evidence(genre = "", values = emptyList())
        val decision = MetadataLibraryDurableOverlay.decide(
            track(genre = "Rock; Jazz"),
            durable,
            marker(),
            evidence,
            observation(),
        )

        assertTrue(decision is MetadataLibraryDurableOverlay.Decision.Apply)
        decision as MetadataLibraryDurableOverlay.Decision.Apply
        assertEquals("", decision.track.genre)
        assertEquals(emptyList<String>(), decision.track.genreValues)
        assertTrue(decision.genreAbsenceConfirmed)
    }

    @Test
    fun aLiteralSemicolonInsideOneGenreIsNotSplit() {
        val durable = durable(genre = "Rock; Pop")
        val evidence = evidence(genre = "Rock; Pop", values = listOf("Rock; Pop"))

        val overlaid = overlay(track(), durable, evidenceRows = listOf(evidence))

        assertEquals("Rock; Pop", overlaid.genre)
        assertEquals(listOf("Rock; Pop"), overlaid.genreValues)
    }

    @Test
    fun reorderedGenresKeepTheEvidenceOrder() {
        val durable = durable(genre = "Blues; Jazz; Rock")
        val evidence = evidence(genre = "Blues; Jazz; Rock", values = listOf("Blues", "Jazz", "Rock"))

        val overlaid = overlay(track(), durable, evidenceRows = listOf(evidence))

        assertEquals(listOf("Blues", "Jazz", "Rock"), overlaid.genreValues)
    }

    @Test
    fun staleEvidenceFromAnOlderRevisionIsNeverReused() {
        val durable = durable(genre = "Metal; Rock")
        val stale = evidence(
            modifiedAt = providerModifiedSeconds - 30L,
            genre = "Rock; Jazz; Blues",
            values = listOf("Rock", "Jazz", "Blues"),
        )

        val overlaid = overlay(track(genre = "Rock"), durable, evidenceRows = listOf(stale))

        assertEquals("Metal; Rock", overlaid.genre)
        assertEquals(emptyList<String>(), overlaid.genreValues)
    }

    @Test
    fun evidenceFromAnotherSourceIsNeverUsed() {
        val durable = durable(genre = "Rock")
        val foreign = evidence(
            sourceId = "another-library",
            genre = "Rock",
            values = listOf("Rock", "Jazz", "Blues"),
        )

        val overlaid = overlay(track(genre = "Rock"), durable, evidenceRows = listOf(foreign))

        assertEquals("Rock", overlaid.genre)
        assertEquals(emptyList<String>(), overlaid.genreValues)
    }

    @Test
    fun evidenceWithADifferentParserVersionIsNeverUsed() {
        val durable = durable(genre = "Rock")
        val old = evidence(
            parserVersion = EmbeddedTagEvidence.CURRENT_PARSER_VERSION - 1,
            genre = "Rock",
            values = listOf("Rock", "Jazz"),
        )

        val overlaid = overlay(track(genre = "Rock"), durable, evidenceRows = listOf(old))

        assertEquals(emptyList<String>(), overlaid.genreValues)
    }

    @Test
    fun providerValuesArePreservedOnlyWhenTheyDescribeTheDurableScalar() {
        val durable = durable(genre = "Rock; Jazz")
        val matching = track(genre = "Rock; Jazz", genreValues = listOf("Rock", "Jazz"))
        val different = track(genre = "Pop; Jazz", genreValues = listOf("Pop", "Jazz"))

        val kept = overlay(matching, durable, evidenceRows = emptyList())
        val notKept = overlay(different, durable, evidenceRows = emptyList())

        assertEquals(listOf("Rock", "Jazz"), kept.genreValues)
        assertEquals(emptyList<String>(), notKept.genreValues)
        assertEquals("Rock; Jazz", notKept.genre)
    }

    @Test
    fun withoutAnAppliedMarkerTheProviderRowIsUntouched() {
        val original = track()

        val overlaid = overlay(original, durable(), publications = emptyList(), evidenceRows = listOf(evidence()))

        assertSame(original, overlaid)
        assertEquals(
            MetadataLibraryDurableOverlay.Refusal.NO_PUBLICATION,
            refusal(original, durable(), publications = emptyList(), evidenceRows = listOf(evidence())),
        )
    }

    @Test
    fun foreignMarkerIsNotPublicationProof() {
        val original = track()
        val foreign = marker(sourceId = "another-library", uri = "content://media/external_primary/audio/media/9")

        val overlaid = overlay(original, durable(), publications = listOf(foreign), evidenceRows = listOf(evidence()))

        assertSame(original, overlaid)
    }

    @Test
    fun olderMarkerThanTheDurableRevisionIsStale() {
        val original = track()
        val older = marker(originalRevision = 0L, committedRevision = 1L)

        val overlaid = overlay(
            original,
            durable(revision = 3L),
            publications = listOf(older),
            evidenceRows = listOf(evidence()),
        )

        assertSame(original, overlaid)
        assertEquals(
            MetadataLibraryDurableOverlay.Refusal.STALE_PUBLICATION,
            refusal(original, durable(revision = 3L), publications = listOf(older), evidenceRows = listOf(evidence())),
        )
    }

    @Test
    fun markerNewerThanTheDurableRowIsNotApplied() {
        val original = track()
        val newer = marker(originalRevision = 2L, committedRevision = 3L)

        val overlaid = overlay(
            original,
            durable(revision = 2L),
            publications = listOf(newer),
            evidenceRows = listOf(evidence()),
        )

        assertSame(original, overlaid)
        assertEquals(
            MetadataLibraryDurableOverlay.Refusal.NEWER_PUBLICATION_EXISTS,
            refusal(original, durable(revision = 2L), publications = listOf(newer), evidenceRows = listOf(evidence())),
        )
    }

    @Test
    fun markerForAnotherUriUnderTheSameOwnerIsRefused() {
        val original = track()
        val wrongUri = marker(uri = "content://media/external_primary/audio/media/9")

        val overlaid = overlay(original, durable(), publications = listOf(wrongUri), evidenceRows = listOf(evidence()))

        assertSame(original, overlaid)
    }

    @Test
    fun observationForAnotherUriUnderTheSameOwnerIsRefused() {
        val original = track()
        val wrongUri = observation(uri = "content://media/external_primary/audio/media/9")

        val overlaid = overlay(
            original,
            durable(),
            evidenceRows = listOf(evidence()),
            observations = listOf(wrongUri),
        )

        assertSame(original, overlaid)
    }

    @Test
    fun durableRowFromAnotherSourceUnderTheSameTrackIdIsNeverApplied() {
        val original = track()
        val foreignDurable = durable(sourceId = "safroot_other")
        val foreignMarker = marker(sourceId = "safroot_other")
        val foreignEvidence = evidence(sourceId = "safroot_other")
        val foreignObservation = observation(sourceId = "safroot_other")

        val overlaid = MetadataLibraryDurableOverlay.apply(
            listOf(original),
            listOf(foreignDurable),
            listOf(foreignMarker),
            listOf(foreignEvidence),
            listOf(foreignObservation),
        ).single()

        assertSame(original, overlaid)
    }

    @Test
    fun externalEditWithTheSameSizeIsNotOverlaid() {
        val original = track()
        val editedAfterPublication = observation(modifiedAt = providerModifiedSeconds + 60L)

        val overlaid = overlay(
            original,
            durable(),
            evidenceRows = listOf(evidence()),
            observations = listOf(editedAfterPublication),
        )

        assertSame(original, overlaid)
        assertEquals(
            MetadataLibraryDurableOverlay.Refusal.SOURCE_CHANGED_SINCE_PUBLICATION,
            refusal(original, durable(), evidenceRows = listOf(evidence()), observations = listOf(editedAfterPublication)),
        )
    }

    @Test
    fun reusedUriWithANewerStampIsNotOverlaid() {
        val original = track()
        val replacement = observation(modifiedAt = providerModifiedSeconds + 1L)

        val overlaid = overlay(
            original,
            durable(),
            evidenceRows = listOf(evidence(modifiedAt = providerModifiedSeconds + 1L)),
            observations = listOf(replacement),
        )

        assertSame(original, overlaid)
    }

    @Test
    fun reusedUriWithADifferentDurationIsNotOverlaid() {
        val original = track(duration = 300_000L)

        val overlaid = overlay(original, durable(duration = 200_000L), evidenceRows = listOf(evidence()))

        assertSame(original, overlaid)
    }

    @Test
    fun unsupportedProviderCapabilityIsRefused() {
        val original = track()
        val unsupported = observation(capability = MetadataLibraryDurableOverlay.ProviderCapability.UNSUPPORTED)

        val overlaid = overlay(
            original,
            durable(),
            evidenceRows = listOf(evidence()),
            observations = listOf(unsupported),
        )

        assertSame(original, overlaid)
        assertEquals(
            MetadataLibraryDurableOverlay.Refusal.PROVIDER_UNSUPPORTED,
            refusal(original, durable(), evidenceRows = listOf(evidence()), observations = listOf(unsupported)),
        )
    }

    @Test
    fun providerSizeMismatchIsRefused() {
        val original = track()
        val mismatched = observation(sizeBytes = 121L)

        val overlaid = overlay(original, durable(), evidenceRows = listOf(evidence()), observations = listOf(mismatched))

        assertSame(original, overlaid)
    }

    @Test
    fun missingProviderObservationCannotProveCurrentContent() {
        val original = track()

        val overlaid = overlay(original, durable(), evidenceRows = listOf(evidence()), observations = emptyList())

        assertSame(original, overlaid)
        assertEquals(
            MetadataLibraryDurableOverlay.Refusal.PROVIDER_UNAVAILABLE,
            refusal(original, durable(), evidenceRows = listOf(evidence()), observations = emptyList()),
        )
    }

    @Test
    fun insufficientSafStampIsRefused() {
        val original = track()
        val safWithoutStamp = observation(
            modifiedAt = 0L,
            capability = MetadataLibraryDurableOverlay.ProviderCapability.SAF_LOCAL,
        )

        val overlaid = overlay(
            original,
            durable(),
            evidenceRows = listOf(evidence()),
            observations = listOf(safWithoutStamp),
        )

        assertSame(original, overlaid)
        assertEquals(
            MetadataLibraryDurableOverlay.Refusal.PROVIDER_STAMP_UNAVAILABLE,
            refusal(original, durable(), evidenceRows = listOf(evidence()), observations = listOf(safWithoutStamp)),
        )
    }

    @Test
    fun safWithATrustworthyProviderStampApplies() {
        val durable = durable(genre = "Metal; Rock")
        val safObservation = observation(
            modifiedAt = appliedAt - 1_000L,
            capability = MetadataLibraryDurableOverlay.ProviderCapability.SAF_LOCAL,
        )

        val overlaid = overlay(
            track(),
            durable,
            evidenceRows = listOf(
                evidence(
                    modifiedAt = appliedAt - 1_000L,
                    genre = "Metal; Rock",
                    values = listOf("Metal", "Rock"),
                ),
            ),
            observations = listOf(safObservation),
        )

        assertEquals(listOf("Metal", "Rock"), overlaid.genreValues)
    }

    @Test
    fun mediaStoreAnnouncementLagStillUsesTheCommittedRevision() {
        val durable = durable(genre = "Metal; Rock", title = "Committed title")
        val laggingProvider = track(title = "Old provider title", genre = "Rock; Jazz")
        val evidence = evidence(genre = "Metal; Rock", values = listOf("Metal", "Rock"))

        val overlaid = overlay(laggingProvider, durable, evidenceRows = listOf(evidence))

        assertEquals("Committed title", overlaid.title)
        assertEquals("Metal; Rock", overlaid.genre)
        assertEquals(listOf("Metal", "Rock"), overlaid.genreValues)
    }

    @Test
    fun validReopenAfterATitleOnlyPublicationAppliesWithoutInventingGenres() {
        val durable = durable(genre = "Jazz", title = "Committed title")

        val overlaid = overlay(
            track(genre = "Rock"),
            durable,
            evidenceRows = emptyList(),
        )

        assertEquals("Committed title", overlaid.title)
        assertEquals("Jazz", overlaid.genre)
        assertEquals(emptyList<String>(), overlaid.genreValues)
    }

    @Test
    fun durableVerifiedHashThatDisagreesWithTheMarkerRefusesTheOverlay() {
        val original = track()
        val verifiedWithDifferentBytes = durable(
            verifiedSha256 = "c".repeat(64),
            verifiedSize = 120L,
            verifiedAt = 1_700_000_000_100L,
            verifiedAlgorithm = "sha256",
        )

        val overlaid = overlay(original, verifiedWithDifferentBytes, evidenceRows = listOf(evidence()))

        assertSame(original, overlaid)
    }

    @Test
    fun durableVerifiedHashThatMatchesTheMarkerKeepsTheCommit() {
        val verifiedWithCommittedBytes = durable(
            verifiedSha256 = sha,
            verifiedSize = 120L,
            verifiedAt = 1_700_000_000_100L,
            verifiedAlgorithm = "sha256",
        )

        val overlaid = overlay(track(), verifiedWithCommittedBytes, evidenceRows = listOf(evidence()))

        assertEquals("Durable title", overlaid.title)
    }

    @Test
    fun sizeMismatchLeavesTheProviderTrackUntouched() {
        val original = track()
        val mismatched = durable(size = 121L)

        val overlaid = overlay(original, mismatched, evidenceRows = listOf(evidence()))

        assertSame(original, overlaid)
    }

    @Test
    fun pathMismatchLeavesTheProviderTrackUntouched() {
        val original = track()
        val mismatched = durable(filepath = "content://media/external_primary/audio/media/2")

        val overlaid = overlay(original, mismatched, evidenceRows = listOf(evidence()))

        assertSame(original, overlaid)
    }

    @Test
    fun changedAlbumIdentityClearsTheProviderAlbumId() {
        val overlaid = overlay(track(), durable(), evidenceRows = listOf(evidence()))

        assertEquals("Durable album", overlaid.album)
        assertEquals("", overlaid.albumId)
        assertEquals("Durable album artist", overlaid.albumArtist)
        assertEquals("cover_rock", overlaid.coverId)
        assertEquals(sha, overlaid.contentHash)
    }

    @Test
    fun groupingAndCountersUseTheDurableAlbumAndEveryCommittedGenre() {
        val first = track(id = "local_1")
        val second = track(
            id = "local_2",
            genre = "Rock",
            filepath = "content://media/external_primary/audio/media/2",
        )
        val durableFirst = durable(id = "local_1", genre = "Rock; Jazz; Blues")
        val durableSecond = durable(
            id = "local_2",
            genre = "Metal; Rock",
            filepath = "content://media/external_primary/audio/media/2",
        )
        val markers = listOf(
            marker(),
            marker(
                trackId = "local_2",
                uri = "content://media/external_primary/audio/media/2",
                transactionId = "0f0e0d0c-0b0a-4908-8706-050403020101",
            ),
        )
        val evidenceRows = listOf(
            evidence(genre = "Rock; Jazz; Blues", values = listOf("Rock", "Jazz", "Blues")),
            evidence(
                trackId = "local_2",
                genre = "Metal; Rock",
                values = listOf("Metal", "Rock"),
            ),
        )
        val observations = listOf(
            observation(),
            observation(trackId = "local_2", uri = "content://media/external_primary/audio/media/2"),
        )

        val overlaid = MetadataLibraryDurableOverlay.apply(
            listOf(first, second),
            listOf(durableFirst, durableSecond),
            markers,
            evidenceRows,
            observations,
        )
        val albums = groupAlbumsFromTracks(overlaid)

        assertEquals(1, albums.size)
        assertEquals(2, albums.single().album.trackCount)
        assertEquals(5, overlaid.sumOf { it.genreValues.size })
        assertEquals(
            listOf("Rock", "Jazz", "Blues"),
            overlaid.first { it.id == "local_1" }.genreValues,
        )
        assertEquals(
            listOf("Metal", "Rock"),
            overlaid.first { it.id == "local_2" }.genreValues,
        )
    }

    @Test
    fun favoritesPlaylistsAndHistoryKeepTheSameDurableIdentity() {
        val original = track()
        val overlaid = overlay(original, durable(), evidenceRows = listOf(evidence()))

        // Favourites, playlists and play history are ID-based references; the
        // overlay may change display metadata but never the referenced identity.
        assertEquals(original.id, overlaid.id)
        assertEquals(original.sourceId, overlaid.sourceId)
        assertEquals(original.filepath, overlaid.filepath)
        assertEquals(original.duration, overlaid.duration)
        assertEquals(listOf(original.id), listOf(overlaid.id))
    }

    @Test
    fun aDeletedProviderRowIsNotResurrectedFromTheDurableCache() {
        val overlaid = MetadataLibraryDurableOverlay.apply(
            emptyList(),
            listOf(durable()),
            listOf(marker()),
            listOf(evidence()),
            listOf(observation()),
        )

        assertTrue(overlaid.isEmpty())
    }

    @Test
    fun aWrongObservationOwnerIsNeverAccepted() {
        val original = track()
        val foreignObservation = observation(
            sourceId = "another-library",
            uri = "content://media/external_primary/audio/media/9",
        )

        val overlaid = overlay(
            original,
            durable(),
            evidenceRows = listOf(evidence()),
            observations = listOf(foreignObservation),
        )

        assertSame(original, overlaid)
    }
}
