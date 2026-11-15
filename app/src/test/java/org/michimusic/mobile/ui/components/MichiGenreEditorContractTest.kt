package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.core.intelligence.MichiGenreDisambiguationPolicy

/**
 * NORM-M1..M3 contract: one receipt, honest states and a gated action.
 *
 * These are pure JVM assertions. They never claim Android certification: the
 * physical write matrix (MediaStore/SAF, MP3/FLAC, Android 31–36) is still
 * pending and the primary action must stay unavailable.
 */
class MichiGenreEditorContractTest {
    private fun track(
        id: String = "t1",
        sourceId: String = "saf-A",
        filepath: String = "content://michi/t1",
        source: TrackSource = TrackSource.LOCAL,
    ) = Track(
        id = id,
        title = "Burn",
        artist = "The Cure",
        album = "The Crow",
        filepath = filepath,
        source = source,
        sourceId = sourceId,
        size = 1024L,
        genre = "Dark Wave",
    )

    @Test
    fun theReceiptIsTheOwnerExactIdentityFromEitherRoute() {
        val genresRoute = MichiGenreEditorContract.target(
            track(), listOf("Dark Wave"), tagsVerified = true, origin = MichiGenreEditorOrigin.GENRES,
        )
        val nowPlayingRoute = MichiGenreEditorContract.target(
            track(), listOf("Dark Wave"), tagsVerified = true,
            origin = MichiGenreEditorOrigin.NOW_PLAYING,
        )
        assertEquals(genresRoute.track, nowPlayingRoute.track)
        assertEquals(genresRoute.sourceId, nowPlayingRoute.sourceId)
        assertEquals(genresRoute.trackId, nowPlayingRoute.trackId)
        assertEquals(genresRoute.filepath, nowPlayingRoute.filepath)
        // Only the invoking route differs; the receipt never does.
        assertEquals(MichiGenreEditorOrigin.GENRES, genresRoute.origin)
        assertEquals(MichiGenreEditorOrigin.NOW_PLAYING, nowPlayingRoute.origin)
    }

    @Test
    fun aDuplicateTrackIdFromAnotherOwnerKeepsADifferentReceipt() {
        val first = MichiGenreEditorContract.target(
            track(id = "shared", sourceId = "saf-A", filepath = "content://a"),
            emptyList(), true, MichiGenreEditorOrigin.GENRES,
        )
        val second = MichiGenreEditorContract.target(
            track(id = "shared", sourceId = "saf-B", filepath = "content://b"),
            emptyList(), true, MichiGenreEditorOrigin.GENRES,
        )
        assertEquals(first.trackId, second.trackId)
        assertTrue(first.sourceId != second.sourceId)
        assertTrue(first.filepath != second.filepath)
    }

    @Test
    fun unverifiedMetadataIsNeverPresentedAsAnEmptyGenre() {
        val evidence = MichiGenreEditorContract.evidenceLabel(tagsVerified = false, genres = emptyList())
        assertTrue(evidence, evidence.startsWith("Metadatos del archivo sin verificar"))
        assertFalse(evidence.contains("Sin género"))
        val original = MichiGenreEditorContract.originalLabel(tagsVerified = false, genres = emptyList())
        assertEquals("Original: sin datos verificados", original)
        assertEquals("Propuesto: ninguno (sin datos verificados)",
            MichiGenreEditorContract.proposedLabel(false, emptyList()))
        assertEquals(
            MichiGenreEditorState.UNVERIFIED_METADATA,
            MichiGenreEditorContract.status(
                tagsVerified = false, verifiedGenres = emptyList(), previewModified = false,
                lookup = MichiGenreLookupPhase.IDLE, writesCertified = false,
                descriptorOpenInPlayer = false,
            ).state,
        )
    }

    @Test
    fun verifiedEmptyIsDistinctFromUnverified() {
        val state = MichiGenreEditorContract.status(
            tagsVerified = true, verifiedGenres = emptyList(), previewModified = false,
            lookup = MichiGenreLookupPhase.IDLE, writesCertified = false,
            descriptorOpenInPlayer = false,
        )
        assertEquals(MichiGenreEditorState.VERIFIED_EMPTY_GENRE, state.state)
        assertEquals(
            "Género verificado: el archivo no declara ningún género.",
            MichiGenreEditorContract.evidenceLabel(true, emptyList()),
        )
        assertEquals("Original: ninguno (verificado)",
            MichiGenreEditorContract.originalLabel(true, emptyList()))
    }

    @Test
    fun verifiedGenresKeepTheLiteralValues() {
        assertEquals(
            "Género verificado: Dark Wave · Coldwave",
            MichiGenreEditorContract.evidenceLabel(true, listOf("Dark Wave", "Coldwave")),
        )
        assertEquals(
            "Original: Dark Wave · Coldwave",
            MichiGenreEditorContract.originalLabel(true, listOf("Dark Wave", "Coldwave")),
        )
        // The editor never rewrites the tag to the canonical MI-26 identity.
        assertEquals(MichiGenreDisambiguationPolicy.key("Dark Wave"), "darkwave")
    }

    @Test
    fun everyContractStateIsReachableAndDistinguishable() {
        val states = buildSet {
            fun record(status: MichiGenreEditorStatus) { add(status.state) }
            record(MichiGenreEditorContract.status(false, emptyList(), false,
                MichiGenreLookupPhase.IDLE, false, false))
            record(MichiGenreEditorContract.status(true, emptyList(), false,
                MichiGenreLookupPhase.IDLE, false, false))
            record(MichiGenreEditorContract.status(true, listOf("Rock"), false,
                MichiGenreLookupPhase.IDLE, true, false))
            record(MichiGenreEditorContract.status(true, listOf("Rock"), true,
                MichiGenreLookupPhase.IDLE, true, false))
            record(MichiGenreEditorContract.status(true, listOf("Rock"), false,
                MichiGenreLookupPhase.PENDING, false, false))
            record(MichiGenreEditorContract.status(true, listOf("Rock"), false,
                MichiGenreLookupPhase.CANCELLED, false, false))
            record(MichiGenreEditorContract.status(true, listOf("Rock"), false,
                MichiGenreLookupPhase.ERROR, false, false))
            record(MichiGenreEditorContract.status(true, listOf("Rock"), false,
                MichiGenreLookupPhase.IDLE, false, false))
            record(MichiGenreEditorContract.status(true, listOf("Rock"), false,
                MichiGenreLookupPhase.IDLE, true, true))
            record(MichiGenreEditorContract.status(true, listOf("Rock"), false,
                MichiGenreLookupPhase.IDLE, false, false, awaitingAndroidPermission = true))
            record(MichiGenreEditorContract.status(true, listOf("Rock"), false,
                MichiGenreLookupPhase.IDLE, false, false, writeVerified = true))
            record(MichiGenreEditorContract.status(true, listOf("Rock"), false,
                MichiGenreLookupPhase.IDLE, false, false, recoveryPending = true))
        }
        assertEquals(MichiGenreEditorState.entries.toSet(), states)
    }

    @Test
    fun theUncertifiedGateOutranksEveryOtherWriteReason() {
        val status = MichiGenreEditorContract.status(
            tagsVerified = true, verifiedGenres = listOf("Rock"), previewModified = true,
            lookup = MichiGenreLookupPhase.RESULTS, writesCertified = false,
            descriptorOpenInPlayer = false,
        )
        assertEquals(MichiGenreWriteAccess.DISABLED_UNCERTIFIED, status.write)
        assertFalse(MichiGenreEditorContract.primaryActionEnabled(status.write))
        assertEquals(
            "No disponible: escritura sin certificar",
            MichiGenreEditorContract.primaryActionLabel(status.write),
        )
        assertTrue(MichiGenreEditorContract.primaryActionReason(status.write)!!.isNotBlank())
        assertEquals("Solo previsualización", MichiGenreEditorContract.modeBadge(false))
    }

    @Test
    fun certifiedEditingStillBlocksTheFileThePlayerHasOpen() {
        val blocked = MichiGenreEditorContract.status(
            tagsVerified = true, verifiedGenres = listOf("Rock"), previewModified = true,
            lookup = MichiGenreLookupPhase.IDLE, writesCertified = true,
            descriptorOpenInPlayer = true,
        )
        assertEquals(MichiGenreWriteAccess.BLOCKED_ACTIVE_DESCRIPTOR, blocked.write)
        assertEquals(MichiGenreEditorState.WRITE_BLOCKED_ACTIVE_DESCRIPTOR, blocked.state)
        assertFalse(MichiGenreEditorContract.primaryActionEnabled(blocked.write))

        val ready = MichiGenreEditorContract.status(
            tagsVerified = true, verifiedGenres = listOf("Rock"), previewModified = true,
            lookup = MichiGenreLookupPhase.IDLE, writesCertified = true,
            descriptorOpenInPlayer = false,
        )
        assertEquals(MichiGenreWriteAccess.READY_CERTIFIED, ready.write)
        assertTrue(MichiGenreEditorContract.primaryActionEnabled(ready.write))
        assertEquals("Guardar cambios", MichiGenreEditorContract.primaryActionLabel(ready.write))
        assertNull(MichiGenreEditorContract.primaryActionReason(ready.write))
        assertEquals("Edición certificada", MichiGenreEditorContract.modeBadge(true))
    }

    @Test
    fun recoveryPendingIsAlwaysReportedBeforeAnyNewWrite() {
        val status = MichiGenreEditorContract.status(
            tagsVerified = true, verifiedGenres = listOf("Rock"), previewModified = true,
            lookup = MichiGenreLookupPhase.IDLE, writesCertified = true,
            descriptorOpenInPlayer = false, recoveryPending = true,
        )
        assertEquals(MichiGenreWriteAccess.FAILED_RECOVERY_PENDING, status.write)
        assertEquals(MichiGenreEditorState.WRITE_FAILED_RECOVERY_PENDING, status.state)
        assertFalse(MichiGenreEditorContract.primaryActionEnabled(status.write))
    }
}
