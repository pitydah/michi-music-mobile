package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicFolderNamePolicyTest {
    @Test fun acceptsOrdinaryAlbumFoldersAndTrimsWhitespace() {
        assertEquals("Jazz 2026", normalizedMusicFolderName("  Jazz 2026  "))
        assertEquals("Música chilena", normalizedMusicFolderName("Música chilena"))
    }

    @Test fun rejectsPathsAndTraversalInsteadOfCreatingNestedDirectories() {
        listOf("", " ", ".", "..", "../Music", "Rock/2026", "Rock\\2026")
            .forEach { assertNull(normalizedMusicFolderName(it)) }
    }

    @Test fun rejectsControlAndInvisibleFormatCharacters() {
        assertNull(normalizedMusicFolderName("Jazz\u0000Rock"))
        assertNull(normalizedMusicFolderName("Jazz\u202eRock"))
    }

    @Test fun rejectsTooLongNameWithoutTruncatingIdentity() {
        assertNull(normalizedMusicFolderName("A".repeat(81)))
        assertEquals("A".repeat(80), normalizedMusicFolderName("A".repeat(80)))
    }
}
