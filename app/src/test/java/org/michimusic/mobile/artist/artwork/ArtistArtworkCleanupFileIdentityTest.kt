package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ArtistArtworkCleanupFileIdentityTest {
    private val old = ArtistArtworkFileIdentity(true, "dev:1/ino:123", 500, 10000L)
    @Test fun exactlySameFileMayAdvance() = assertTrue(sameArtworkFileForUnlink(old, old.copy()))
    @Test fun changedInodeFails() = assertFalse(sameArtworkFileForUnlink(old, old.copy(key="other")))
    @Test fun nullBeforeKeyFails() = assertFalse(sameArtworkFileForUnlink(old.copy(key=null),old))
    @Test fun nullAfterKeyFails() = assertFalse(sameArtworkFileForUnlink(old,old.copy(key=null)))
    @Test fun changedBytesFails() = assertFalse(sameArtworkFileForUnlink(old,old.copy(bytes=501)))
    @Test fun changedTimestampFails() = assertFalse(sameArtworkFileForUnlink(old,old.copy(lastModifiedEpochMs=10001L)))
    @Test fun symlinkOrDirectoryBeforeFails() = assertFalse(sameArtworkFileForUnlink(old.copy(regular=false),old))
    @Test fun symlinkOrDirectoryAfterFails() = assertFalse(sameArtworkFileForUnlink(old,old.copy(regular=false)))
    @Test fun emptyFileFails() = assertFalse(sameArtworkFileForUnlink(old.copy(bytes=0),old.copy(bytes=0)))
}
