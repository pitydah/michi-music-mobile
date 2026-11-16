package org.michimusic.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferIntegrityPolicyTest {
    private val goodHash = "ab".repeat(32)

    @Test fun cannotReuseWithoutHashOrWithMismatchedSize() {
        assertFalse(TransferIntegrityPolicy.mayReuse(100, 100, ""))
        assertFalse(TransferIntegrityPolicy.mayReuse(100, 99, goodHash))
        assertFalse(TransferIntegrityPolicy.mayReuse(0, 0, goodHash))
    }

    @Test fun acceptsOnlyWellFormedShaAndVerifiedSize() {
        assertFalse(TransferIntegrityPolicy.validSha256("z".repeat(64)))
        assertFalse(TransferIntegrityPolicy.validSha256("ab".repeat(31)))
        assertTrue(TransferIntegrityPolicy.mayReuse(100, 100, goodHash))
        assertTrue(TransferIntegrityPolicy.mayReuse(100, 0, goodHash))
    }
}
