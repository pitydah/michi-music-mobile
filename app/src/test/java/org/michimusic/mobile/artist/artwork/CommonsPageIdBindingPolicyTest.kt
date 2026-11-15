package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommonsPageIdBindingPolicyTest {
    @Test fun exactFileOnlyGetsItsOwnPageId() {
        assertEquals("42", CommonsPageIdBindingPolicy.verifiedId(
            "Actual_artist.jpg", "File:Actual artist.jpg", "6", "42"))
    }
    @Test fun mismatchedOrMalformedPageNeverProvesDepiction() {
        for ((title,ns,id) in listOf(
            Triple("File:Other_artist.jpg", "6", "42"),
            Triple("File:Actual_artist.jpg", "0", "42"),
            Triple("File:Actual_artist.jpg", "6", "-1"),
            Triple("File:Actual_artist.jpg", "6", "0"),
            Triple("File:Actual_artist.jpg", "6", "42x"),
            Triple(null, "6", "42"),
        )) assertNull(CommonsPageIdBindingPolicy.verifiedId("Actual_artist.jpg",title,ns,id))
    }
}
