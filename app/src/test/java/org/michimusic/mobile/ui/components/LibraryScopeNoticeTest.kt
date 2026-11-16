package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.core.models.LibraryScope
import org.michimusic.mobile.library.LibraryScopeFailure
import org.michimusic.mobile.library.LibraryScopeResolution

/**
 * K48-R02: the library selector must never describe a failed restore as a saved choice.
 * The label and the retry action are derived from one closed notice, not from free text.
 */
class LibraryScopeNoticeTest {
    @Test
    fun aFailedReadIsReportedWithARetry() {
        val notice = LibraryScopeResolution
            .ReadFailed(LibraryScopeFailure.SETTINGS_UNAVAILABLE).notice()

        assertEquals(LibraryScopeNotice.NOT_RESTORED, notice)
        assertEquals("No se pudo recuperar la selección", notice?.message())
        assertEquals("Reintentar", notice?.retryLabel())
    }

    @Test
    fun anUnsavedSelectionIsNotReportedAsSaved() {
        val notice = LibraryScopeResolution.WriteFailed(
            activeScope = LibraryScope.One("library-a"),
            reason = LibraryScopeFailure.WRITE_UNAVAILABLE,
        ).notice()

        assertEquals(LibraryScopeNotice.NOT_SAVED, notice)
        assertEquals("Selección no guardada", notice?.message())
        assertEquals("Reintentar", notice?.retryLabel())
    }

    @Test
    fun restoringIsInformativeButNotRetryable() {
        val notice = LibraryScopeResolution.Loading.notice()

        assertEquals(LibraryScopeNotice.RESTORING, notice)
        assertEquals("Recuperando la biblioteca guardada…", notice?.message())
        assertNull("Restoring is not a failure, so it offers no retry", notice?.retryLabel())
    }

    @Test
    fun aVerifiedSelectionIsSilent() {
        assertNull(LibraryScopeResolution.Ready(LibraryScope.All, persisted = true).notice())
    }
}
