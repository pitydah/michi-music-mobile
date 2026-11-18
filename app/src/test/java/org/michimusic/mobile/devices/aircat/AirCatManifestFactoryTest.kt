package org.michimusic.mobile.devices.aircat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.dto.ManifestTrackDto

class AirCatManifestFactoryTest {
    @Test fun preservesLocalAndRemoteIdAndExactlyCountsBytes() {
        val tracks = listOf(
            ManifestTrackDto(trackId = "source-1", size = 8L, title = "Song", format = "flac", checksum = "a".repeat(64)),
            ManifestTrackDto(trackId = "source-2", size = 9L, title = "Other", format = "wav", checksum = "b".repeat(64)),
        )
        val value = AirCatManifestFactory.prepare("micro", "mobile", tracks, 1000L)
        assertTrue(value.intent.transferId.startsWith("aircat_1000_"))
        assertTrue(value.intent.transferId.length > "aircat_1000_".length)
        assertEquals(value.intent.transferId, value.manifest.transferId)
        assertEquals("mobile", value.intent.destinationMichiId)
        assertEquals("lib_synced_micro", value.intent.destinationLibraryId)
        assertEquals(17L, value.manifest.totalBytes)
        assertEquals(2, value.manifest.totalTracks)
        assertEquals("source-1", value.manifest.items[0].logicalTrackId)
        assertEquals("source-1", value.manifest.items[0].remoteTrackId)
        assertTrue(value.manifest.items.all { it.sha256.length == 64 })
    }

    @Test fun negativeSizeAndOverflowFailBeforeCreatingTransfer() {
        // A68-11: every entry here is otherwise valid (format, digest, positive clock),
        // so the refusal can only come from the size. With a zero clock the factory
        // stopped at the clock gate and this test proved nothing about size or overflow.
        assertThrows(IllegalArgumentException::class.java) {
            AirCatManifestFactory.prepare(
                "a", "b",
                listOf(ManifestTrackDto("one", size = -1, format = "flac", checksum = "a".repeat(64))),
                1000L,
            )
        }
        assertThrows(ArithmeticException::class.java) {
            AirCatManifestFactory.prepare("a", "b", listOf(
                ManifestTrackDto("one", size = Long.MAX_VALUE, format = "flac", checksum = "a".repeat(64)),
                ManifestTrackDto("two", size = 1L, format = "flac", checksum = "b".repeat(64)),
            ), 1000L)
        }
    }

    @Test fun aClockThatCannotTimestampATransferIsRefused() {
        val tracks = listOf(ManifestTrackDto("one", size = 4L, format = "flac", checksum = "a".repeat(64)))
        assertThrows(IllegalArgumentException::class.java) {
            AirCatManifestFactory.prepare("a", "b", tracks, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirCatManifestFactory.prepare("a", "b", tracks, -1L)
        }
    }

    @Test fun formatlessAndOversizedBatchesAreRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            AirCatManifestFactory.prepare(
                "a", "b",
                listOf(ManifestTrackDto("one", size = 4L, checksum = "a".repeat(64))),
                1000L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirCatManifestFactory.prepare("a", "b", emptyList(), 1000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirCatManifestFactory.prepare(
                "a", "b",
                (1..10_001).map {
                    ManifestTrackDto("t$it", size = 1L, format = "flac", checksum = "a".repeat(64))
                },
                1000L,
            )
        }
    }

    @Test fun repeatedRequestsInSameMillisecondHaveUniqueTransferIds() {
        val tracks = listOf(ManifestTrackDto("one", size = 42L, format = "flac", checksum = "a".repeat(64)))
        val a = AirCatManifestFactory.prepare("micro", "mobile", tracks, 1000L)
        val b = AirCatManifestFactory.prepare("micro", "mobile", tracks, 1000L)
        assertTrue(a.intent.transferId != b.intent.transferId)
        assertEquals(a.intent.transferId, a.manifest.transferId)
        assertEquals(b.intent.transferId, b.manifest.transferId)
    }

    @Test fun newTransferRejectsHashlessMalformedAndDuplicateSourceEntries() {
        assertThrows(IllegalArgumentException::class.java) {
            AirCatManifestFactory.prepare(
                "micro", "mobile",
                listOf(ManifestTrackDto("no-digest", size = 5L, format = "flac")), 1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirCatManifestFactory.prepare("micro", "mobile",
                listOf(ManifestTrackDto("bad", size = 5L, format = "flac", checksum = "z".repeat(64))), 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirCatManifestFactory.prepare("micro", "mobile",
                listOf(ManifestTrackDto("same", size = 2L, format = "flac", checksum = "a".repeat(64)),
                    ManifestTrackDto("same", size = 2L, format = "flac", checksum = "a".repeat(64))), 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirCatManifestFactory.prepare("micro", "mobile",
                listOf(ManifestTrackDto(" ", size = 2L, format = "flac", checksum = "a".repeat(64))), 1L)
        }
    }
    @Test fun distinctLogicalAndRemoteIdsRemainSeparateAcrossManifestAndRoom() {
        val tracks = listOf(
            ManifestTrackDto(trackId = "remote-catalog-A", logicalTrackId = "local-A",
                remoteTrackId = "remote-file-9", size = 21L,
                format = "flac", checksum = "a".repeat(64)),
            ManifestTrackDto(trackId = "remote-catalog-B", logicalTrackId = "local-B",
                remoteTrackId = "remote-file-10", size = 22L,
                format = "flac", checksum = "b".repeat(64)),
        )
        val prepared = AirCatManifestFactory.prepare("source", "destination", tracks, 77L)
        assertEquals("local-A", prepared.manifest.items[0].logicalTrackId)
        assertEquals("remote-file-9", prepared.manifest.items[0].remoteTrackId)
        assertEquals("local-B", prepared.manifest.items[1].logicalTrackId)
        assertEquals("remote-file-10", prepared.manifest.items[1].remoteTrackId)
        assertEquals(43L, prepared.manifest.totalBytes)
    }

    @Test fun duplicateLogicalOrRemoteIdentityIsNotAnAuthorizedSelection() {
        val good = ManifestTrackDto(trackId = "catalog-A", logicalTrackId = "local",
            remoteTrackId = "remote-A", size = 4L, format = "wav", checksum = "a".repeat(64))
        assertThrows(IllegalArgumentException::class.java) {
            AirCatManifestFactory.prepare("s", "d", listOf(good,
                good.copy(trackId = "catalog-B", remoteTrackId = "remote-B")), 10L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirCatManifestFactory.prepare("s", "d", listOf(good,
                good.copy(trackId = "catalog-B", logicalTrackId = "other")), 10L)
        }
    }

}
