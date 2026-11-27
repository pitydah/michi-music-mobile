package org.michimusic.data.metadata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.MichiDatabase

@RunWith(AndroidJUnit4::class)
class MetadataTrackDaoCasInstrumentedTest {
    @Test fun exactVerifiedCasUpdatesOneRowAndInvalidatesEvidence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.trackDao()
            val sha = "a".repeat(64)
            val track = CachedTrack(
                id = "local_1", title = "Antes", artist = "Artista",
                album = "Álbum", sourceId = "lib_device_local",
                filepath = "content://media/external_primary/audio/media/1",
                size = 100, verifiedSize = 100, verifiedSha256 = sha,
                verifiedAt = 77, verifiedAlgorithm = "sha256", genre = "Rock",
                trackNumber = 1, year = 1990,
            )
            dao.insertAll(listOf(track))
            val updated = dao.commitMetadataAfterVerifiedWrite(
                sourceId = track.sourceId, trackId = track.id, filepath = track.filepath,
                previousSize = track.size, previousVerifiedAt = track.verifiedAt,
                previousSha256 = track.verifiedSha256,
                previousTitle = track.title, previousArtist = track.artist,
                previousAlbum = track.album, previousAlbumArtist = track.albumArtist,
                previousGenre = track.genre, previousTrackNumber = track.trackNumber,
                previousYear = track.year, newSize = 120, newTitle = "Después",
                newArtist = "Artista", newAlbum = "Álbum",
                newAlbumArtist = track.albumArtist, newGenre = "Rock",
                newTrackNumber = 1, newYear = 1990,
            )
            assertTrue(updated)
            val row = dao.getTrackBySource(track.sourceId, track.id)!!
            assertEquals("Después", row.title)
            assertEquals(120L, row.size)
            assertEquals(0L, row.verifiedAt)
            assertEquals("", row.verifiedSha256)
        } finally { db.close() }
    }

    @Test fun staleIdentityIsRejectedWithoutOverwrite() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.trackDao()
            val sha = "b".repeat(64)
            val track = CachedTrack(
                id = "local_2", title = "Keep", sourceId = "lib_device_local",
                filepath = "content://media/external_primary/audio/media/2",
                size = 100, verifiedSize = 100, verifiedSha256 = sha,
                verifiedAt = 88, verifiedAlgorithm = "sha256",
            )
            dao.insertAll(listOf(track))
            val updated = dao.commitMetadataAfterVerifiedWrite(
                sourceId = track.sourceId, trackId = track.id, filepath = track.filepath,
                previousSize = 101, previousVerifiedAt = track.verifiedAt,
                previousSha256 = track.verifiedSha256,
                previousTitle = track.title, previousArtist = track.artist,
                previousAlbum = track.album, previousAlbumArtist = track.albumArtist,
                previousGenre = track.genre, previousTrackNumber = track.trackNumber,
                previousYear = track.year, newSize = 120, newTitle = "Injected",
                newArtist = "", newAlbum = "", newAlbumArtist = "", newGenre = "",
                newTrackNumber = 0, newYear = 0,
            )
            assertEquals(false, updated)
            assertEquals("Keep", dao.getTrackBySource(track.sourceId, track.id)!!.title)
        } finally { db.close() }
    }
}
