package org.michimusic.data.intelligence

import org.junit.Assert.*
import org.junit.Test

class MichiGenreDescriptorPolicyTest {
    @Test fun onlyExactKnownLengthAllowsMutation() {
        assertTrue(MichiGenreDescriptorPolicy.permitsMutation(1024,1024,1024))
        assertFalse(MichiGenreDescriptorPolicy.permitsMutation(-1,1024,1024))
        assertFalse(MichiGenreDescriptorPolicy.permitsMutation(1024,-1,1024))
        assertFalse(MichiGenreDescriptorPolicy.permitsMutation(1024,0,1024))
        assertFalse(MichiGenreDescriptorPolicy.permitsMutation(2048,2048,1024))
        assertFalse(MichiGenreDescriptorPolicy.permitsMutation(0,0,0))
    }
    @Test fun recoveryCanRestoreTruncatedFilesButNeverUnknownPipes() {
        assertTrue(MichiGenreDescriptorPolicy.permitsRecovery(0,0))
        assertTrue(MichiGenreDescriptorPolicy.permitsRecovery(412,412))
        assertFalse(MichiGenreDescriptorPolicy.permitsRecovery(-1,-1))
        assertFalse(MichiGenreDescriptorPolicy.permitsRecovery(-1,0))
        assertFalse(MichiGenreDescriptorPolicy.permitsRecovery(7,8))
    }
}
