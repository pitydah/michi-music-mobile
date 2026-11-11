package org.michimusic.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreGenreProjectionPolicyTest {
    @Test fun incompatibleGenreColumnRetriesUsingBasicProjection() {
        assertTrue(shouldRetryGenreProjection(true,
            MediaResult.QueryFailed(IllegalArgumentException("Unknown GENRE column"))))
    }

    @Test fun nullGenreCursorIsNotTreatedAsEmptyLibrary() {
        assertTrue(shouldRetryGenreProjection(true, MediaResult.Success(null)))
    }

    @Test fun genuineEmptyCursorDoesNotTriggerExtraQuery() {
        assertFalse(shouldRetryGenreProjection(true, MediaResult.Success("empty cursor")))
    }

    @Test fun permissionDeniedNeverRetries() {
        assertFalse(shouldRetryGenreProjection(true, MediaResult.PermissionDenied))
    }

    @Test fun oldPlatformNeverRetriesForOptionalGenre() {
        assertFalse(shouldRetryGenreProjection(false,
            MediaResult.QueryFailed(IllegalStateException("Unrelated failure"))))
    }
}
