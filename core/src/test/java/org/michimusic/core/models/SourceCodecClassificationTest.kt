package org.michimusic.core.models

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceCodecClassificationTest {
    @Test fun mediaStoreAudioMpegIsLossy() {
        assertEquals(CodecClassification.LOSSY, Track("one", "song", format = "mpeg").codecClassification)
        assertEquals(CodecClassification.LOSSY, Track("two", "song", format = "audio/mpeg").codecClassification)
    }

    @Test fun wavAndAiffContainersAreUnknownWithoutCodecEvidence() {
        assertEquals(CodecClassification.UNKNOWN, Track("w", "song", format = "audio/wav").codecClassification)
        assertEquals(CodecClassification.UNKNOWN, Track("a", "song", format = "aiff").codecClassification)
        assertEquals(CodecClassification.LOSSLESS, Track("pcm", "song", format = "wav; codec=pcm").codecClassification)
    }

    @Test fun wavPackHybridIsUnknownWithoutValidatedCorrectionFile() {
        assertEquals(CodecClassification.UNKNOWN, Track("hybrid", "song", format = "wavpack").codecClassification)
        assertEquals(CodecClassification.UNKNOWN, Track("hybrid2", "song", format = "audio/x-wavpack").codecClassification)
    }

}
