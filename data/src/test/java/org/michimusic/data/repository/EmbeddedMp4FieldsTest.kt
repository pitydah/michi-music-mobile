package org.michimusic.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedMp4FieldsTest {
    private fun atom(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size + 8).array())
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }
    private fun field(type: String, value: String): ByteArray =
        atom(type, atom("data", byteArrayOf(0,0,0,1,0,0,0,0) + value.toByteArray(Charsets.UTF_8)))

    @Test fun readsMp4IlstTextFields() {
        val ftyp = atom("ftyp", "M4A ".toByteArray())
        val ilst = atom("ilst", field("©gen", "Jazz") + field("©nam", "Canción") + field("aART", "Artista álbum"))
        val bytes = ftyp + atom("moov", atom("udta", atom("meta", byteArrayOf(0,0,0,0) + ilst)))
        val tags = EmbeddedTagFieldsReader.read(ByteArrayInputStream(bytes))
        assertEquals("Jazz", tags?.genre)
        assertEquals("Canción", tags?.title)
        assertEquals("Artista álbum", tags?.albumArtist)
    }

    @Test fun numericItunesGenreAtomUsesStandardIdRatherThanInventingStyle() {
        val ftyp = atom("ftyp", "M4A ".toByteArray())
        // iTunes gnre is 1-based, so Rock (ID3 index 17) is encoded as 18.
        val genre = atom("gnre", atom("data", byteArrayOf(0,0,0,0,0,0,0,0,0,18)))
        val bytes = ftyp + atom("moov", atom("udta", atom("meta", ByteArray(4) + atom("ilst",genre))))
        assertEquals("Rock", EmbeddedTagFieldsReader.read(ByteArrayInputStream(bytes))?.genre)
    }

    @Test fun rejectsUnknownData() {
        assertNull(EmbeddedTagFieldsReader.read(ByteArrayInputStream(byteArrayOf(1,2,3))))
    }
}
