package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataLrcToolkitTest {
    @Test fun shiftsHundredthsAndPreservesLyrics() {
        val input="[ar:Artist]\n[00:01.50]Noches de jazz\n[00:03.25]Hello"
        val result=MetadataLrcToolkit.shift(input,250) as MetadataLrcToolkit.Result.Ready
        assertEquals("[ar:Artist]\n[00:01.75]Noches de jazz\n[00:03.50]Hello",result.proposed)
        assertEquals(2,result.adjusted)
    }
    @Test fun supportsThreeDigitMilliseconds() {
        val result=MetadataLrcToolkit.shift("[02:04.025]Word",5)
            as MetadataLrcToolkit.Result.Ready
        assertEquals("[02:04.030]Word",result.proposed)
    }
    @Test fun underflowBlocksEntireOperation() {
        assertEquals(MetadataLrcToolkit.Result.Blocked(MetadataLrcToolkit.Reason.TIMESTAMP_UNDERFLOW),
            MetadataLrcToolkit.shift("[00:01.25]First\n[00:00.05]Second",-100))
    }
    @Test fun preservesOriginalAndRejectsPrecisionLoss() {
        val original="[00:01.25]Song"
        assertEquals(MetadataLrcToolkit.Result.Blocked(MetadataLrcToolkit.Reason.PRECISION_LOSS),
            MetadataLrcToolkit.shift(original,1))
        assertEquals("[00:01.25]Song",original)
    }
    @Test fun existingOffsetDoesNotGetDoubleShifted() {
        assertEquals(MetadataLrcToolkit.Result.Blocked(MetadataLrcToolkit.Reason.EXISTING_OFFSET_TAG),
            MetadataLrcToolkit.shift("[offset:+100]\n[00:01.50]Song",200))
    }
    @Test fun badSecondsAndCommaTimestampAreRejected() {
        assertEquals(MetadataLrcToolkit.Result.Blocked(MetadataLrcToolkit.Reason.INVALID_TIMESTAMP),
            MetadataLrcToolkit.shift("[00:70.50]Bad",100))
        assertEquals(MetadataLrcToolkit.Result.Blocked(MetadataLrcToolkit.Reason.INVALID_TIMESTAMP),
            MetadataLrcToolkit.shift("[00:01,25]Ambiguous",100))
    }
    @Test fun zeroOffsetLeavesSourceUnchanged() {
        val input="[ti:Song]\n[00:00.00]Hi"
        val result=MetadataLrcToolkit.shift(input,0) as MetadataLrcToolkit.Result.Ready
        assertEquals(input,result.proposed)
        assertTrue(result.adjusted == 1)
    }
}
