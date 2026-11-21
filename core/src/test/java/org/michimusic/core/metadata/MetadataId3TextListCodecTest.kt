package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataId3TextListCodecTest {
    @Test fun v24RoundtripPreservesLiteralSemicolonAndDuplicates() {
        val original = listOf("Jazz; Fusion", "Jazz", "Jazz")
        val bytes = MetadataId3TextListCodec.encode(4, original)!!
        assertEquals(3, bytes[0].toInt())
        assertEquals(original, MetadataId3TextListCodec.decode(4, bytes))
    }
    @Test fun v23NeverConflatesMultipleEntries() {
        assertNull(MetadataId3TextListCodec.encode(3, listOf("Jazz", "Bebop")))
    }
    @Test fun v23RejectsEmbeddedNulAndV24AllowsSeparator() {
        assertNull(MetadataId3TextListCodec.decode(3,byteArrayOf(0)+"Jazz\u0000Bebop".toByteArray()))
        assertEquals(listOf("Jazz","Bebop"),
            MetadataId3TextListCodec.decode(4,byteArrayOf(3)+"Jazz\u0000Bebop".toByteArray()))
    }
    @Test fun malformedUtf8AndEmptyValuesFailClosed() {
        assertNull(MetadataId3TextListCodec.decode(4,byteArrayOf(3,0xc3.toByte(),0x28)))
        assertNull(MetadataId3TextListCodec.decode(4,byteArrayOf(3)+"Jazz\u0000\u0000Bebop".toByteArray()))
        assertNull(MetadataId3TextListCodec.encode(4, listOf("Jazz", "")))
        assertNull(MetadataId3TextListCodec.decode(4,
            byteArrayOf(3)+"Jazz\u0000\u0000".toByteArray()))
    }
    @Test fun cannotExceedLimit() {
        assertNull(MetadataId3TextListCodec.encode(4,List(33){"Jazz"}))
        assertTrue(MetadataId3TextListCodec.encode(4,listOf("Rock"))!!.isNotEmpty())
    }
}
