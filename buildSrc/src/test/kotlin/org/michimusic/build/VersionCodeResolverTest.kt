package org.michimusic.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VersionCodeResolverTest {
    @Test
    fun `explicit version code has highest priority`() {
        assertEquals(
            2_000_123,
            resolveMichiVersionCode(
                explicitVersionCode = "2000123",
                githubRunNumber = "252",
                gitCommitCount = "1",
            ),
        )
    }

    @Test
    fun `CI version code is monotonic and independent of shallow history`() {
        val current = resolveMichiVersionCode(
            explicitVersionCode = null,
            githubRunNumber = "252",
            gitCommitCount = "1",
        )
        val next = resolveMichiVersionCode(
            explicitVersionCode = null,
            githubRunNumber = "253",
            gitCommitCount = "1",
        )

        assertEquals(1_000_252, current)
        assertEquals(1_000_253, next)
    }

    @Test
    fun `local builds retain full git commit count`() {
        assertEquals(
            441,
            resolveMichiVersionCode(
                explicitVersionCode = null,
                githubRunNumber = null,
                gitCommitCount = "441",
            ),
        )
    }

    @Test
    fun `missing local git metadata keeps development fallback`() {
        assertEquals(
            1,
            resolveMichiVersionCode(
                explicitVersionCode = null,
                githubRunNumber = null,
                gitCommitCount = null,
            ),
        )
    }

    @Test
    fun `invalid authoritative inputs fail instead of silently downgrading`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveMichiVersionCode(
                explicitVersionCode = "0",
                githubRunNumber = "252",
                gitCommitCount = "441",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveMichiVersionCode(
                explicitVersionCode = null,
                githubRunNumber = "not-a-number",
                gitCommitCount = "441",
            )
        }
    }

    @Test
    fun `CI run number is rejected before offset addition can overflow`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveMichiVersionCode(
                explicitVersionCode = null,
                githubRunNumber = Long.MAX_VALUE.toString(),
                gitCommitCount = "441",
            )
        }
    }

    @Test
    fun `explicit release code must exceed the checked in published floor`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveMichiVersionCode(
                explicitVersionCode = "1",
                githubRunNumber = null,
                gitCommitCount = "441",
                lastPublishedVersionCode = "1",
            )
        }
        assertEquals(
            2,
            resolveMichiVersionCode(
                explicitVersionCode = "2",
                githubRunNumber = null,
                gitCommitCount = "441",
                lastPublishedVersionCode = "1",
            ),
        )
    }
}
