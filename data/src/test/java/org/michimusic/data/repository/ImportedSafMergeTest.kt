package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track

class ImportedSafMergeTest {
    private val saf = Track(id = "saf_123", title = "Audio autorizado", filepath = "content://picker/audio/123")
    private val local = Track(id = "local_5", title = "Audio MediaStore", filepath = "content://media/audio/5")

    @Test fun uriOnlyLibraryStillWorksWithoutBroadPermission() {
        val result = mergeImportedSafTracks(MediaResult.PermissionDenied, listOf(saf))
        assertEquals(listOf(saf), (result as MediaResult.Success).data)
        assertEquals(MediaCoverage.SAF_ONLY_PERMISSION_DENIED, result.coverage)
    }

    @Test fun noPermissionAndNoUriKeepsDeniedTruth() {
        assertSame(MediaResult.PermissionDenied, mergeImportedSafTracks(MediaResult.PermissionDenied, emptyList()))
    }

    @Test fun mergesImportedWithoutDuplicatingSameProviderUri() {
        val duplicate = saf.copy(id = "local_8")
        val result = mergeImportedSafTracks(MediaResult.Success(listOf(local, saf)), listOf(duplicate, saf))
        assertEquals(listOf(local, saf), (result as MediaResult.Success).data)
    }

    @Test fun providesImportedOnlyWhenMediaStoreQueryFails() {
        val result = mergeImportedSafTracks(MediaResult.QueryFailed(IllegalStateException("provider")), listOf(saf))
        assertEquals(listOf(saf), (result as MediaResult.Success).data)
        assertEquals(MediaCoverage.SAF_ONLY_QUERY_FAILED, result.coverage)
    }

    @Test fun preservesQueryErrorIfNoImportedAudio() {
        assertTrue(mergeImportedSafTracks(MediaResult.QueryFailed(IllegalStateException()), emptyList()) is MediaResult.QueryFailed)
    }
}
