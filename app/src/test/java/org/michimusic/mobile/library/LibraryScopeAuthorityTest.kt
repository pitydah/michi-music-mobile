package org.michimusic.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.LibraryScope

/**
 * K48-R12-02: authority is a projection of the resolution the store already publishes.
 * These cases pin the rule that matters: no provisional or failed value may hand out a
 * usable scope by accident.
 */
class LibraryScopeAuthorityTest {

    @Test
    fun `loading authorizes nothing`() {
        val authority = LibraryScopeResolution.Loading.authority()

        assertEquals(LibraryScopeAuthority.Loading, authority)
        assertNull("the placeholder scope must not be usable", authority.authorizedScope())
    }

    @Test
    fun `a restored selection is authorized and persisted`() {
        val authority = LibraryScopeResolution
            .Ready(LibraryScope.One("lib_custom_1"), persisted = true)
            .authority()

        assertEquals(
            LibraryScopeAuthority.Authorized(LibraryScope.One("lib_custom_1"), persisted = true),
            authority,
        )
        assertEquals(LibraryScope.One("lib_custom_1"), authority.authorizedScope())
    }

    @Test
    fun `a restored All is authorized only because the store verified it`() {
        val authority = LibraryScopeResolution.Ready(LibraryScope.All, persisted = true).authority()

        assertEquals(LibraryScopeAuthority.Authorized(LibraryScope.All, persisted = true), authority)
        assertEquals(LibraryScope.All, authority.authorizedScope())
    }

    @Test
    fun `a failed read never authorizes All`() {
        val authority = LibraryScopeResolution
            .ReadFailed(LibraryScopeFailure.SETTINGS_UNAVAILABLE)
            .authority()

        assertEquals(
            LibraryScopeAuthority.Unavailable(LibraryScopeFailure.SETTINGS_UNAVAILABLE),
            authority,
        )
        assertNull("a failure is not a decision", authority.authorizedScope())
    }

    @Test
    fun `a selection that could not be saved is active but not persisted`() {
        val authority = LibraryScopeResolution
            .WriteFailed(LibraryScope.One("lib_custom_1"), LibraryScopeFailure.WRITE_UNAVAILABLE)
            .authority()

        val authorized = authority as? LibraryScopeAuthority.Authorized
        assertEquals(LibraryScope.One("lib_custom_1"), authorized?.scope)
        assertTrue("the choice is active but must not be reported as saved", authorized?.persisted == false)
    }

    @Test
    fun `an unavailable authority is never silently downgraded to All`() {
        val reasons = listOf(
            LibraryScopeFailure.SETTINGS_UNAVAILABLE,
            LibraryScopeFailure.LIBRARIES_UNAVAILABLE,
            LibraryScopeFailure.WRITE_UNAVAILABLE,
        )

        reasons.forEach { reason ->
            val authority = LibraryScopeResolution.ReadFailed(reason).authority()
            assertNull("$reason must not authorize a scope", authority.authorizedScope())
        }
    }
}
